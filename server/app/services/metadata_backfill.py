"""Background backfill of statistics metadata for existing file records.

Two phases (design §4.8):

1. ``seed_capture_times`` — database only: copies the client ``exif_time`` into
   ``capture_time`` / ``capture_time_kind`` so weekday/hour charts work right
   away. Does not set ``meta_version``; the rows stay pending.
2. ``run_backfill_round`` — reads each pending file (EXIF / ffprobe) in batches
   and stores the result through ``apply_media_metadata_many``.

Progress lives in ``meta_version``: rows that were attempted are never selected
again (``no_ffprobe`` rows only once ffprobe becomes available), so the task is
interruptible and idempotent. It does not depend on the analysis queue or
onnxruntime.
"""

from __future__ import annotations

import asyncio
import dataclasses
import logging
import os
import time
from dataclasses import dataclass

import aiosqlite

from app.core.config import get_settings, sqlite_path_from_url
from app.services.background_tasks import _database_ready
from app.services.media_metadata import (
    BACKFILL_FFPROBE_TIMEOUT,
    CURRENT_META_VERSION,
    MediaMetadata,
    apply_media_metadata_many,
    extract_media_metadata,
    ffprobe_path,
    resolve_capture_time,
)

logger = logging.getLogger("photovault.metadata_backfill")

BATCH_SIZE = 50
SEED_PAGE_SIZE = 1000
ROUND_INTERVAL_SECONDS = 3600
BATCH_PAUSE_SECONDS = 0.1


@dataclass
class BackfillStats:
    processed: int = 0
    ok: int = 0
    error: int = 0
    no_ffprobe: int = 0
    skipped: int = 0          # optimistic guard failed (trashed / deleted meanwhile)
    elapsed: float = 0.0


_SEED_SELECT_SQL = """
SELECT id, exif_time, media_type FROM file_records
WHERE id > ? AND capture_time IS NULL AND exif_time IS NOT NULL AND meta_version IS NULL
ORDER BY id LIMIT ?
"""

_SEED_UPDATE_SQL = """
UPDATE file_records SET capture_time = ?, capture_time_kind = ?
WHERE id = ? AND capture_time IS NULL AND meta_version IS NULL
"""

_PENDING_WHERE = """
deleted_at IS NULL AND purged_at IS NULL
AND (is_reference IS NULL OR is_reference = 0)
AND ((meta_version IS NULL OR meta_version < ?)
     OR (meta_status = 'no_ffprobe' AND ? = 1))
"""

_PENDING_COUNT_SQL = f"SELECT COUNT(*) FROM file_records WHERE {_PENDING_WHERE}"

_BATCH_SELECT_SQL = f"""
SELECT id, file_path, media_type, exif_time FROM file_records
WHERE id > ? AND {_PENDING_WHERE}
ORDER BY id LIMIT ?
"""


def _client_capture_time(
    exif_time: str | None, media_type: str | None
) -> tuple[str | None, str | None]:
    try:
        return resolve_capture_time(None, exif_time, media_type)
    except Exception:  # noqa: BLE001 - pure function, defensive only
        return None, None


async def seed_capture_times(db: aiosqlite.Connection) -> int:
    """Phase 1: seed capture_time from the client exif_time. Returns rows written."""
    written = 0
    last_id = 0
    while True:
        cursor = await db.execute(_SEED_SELECT_SQL, (last_id, SEED_PAGE_SIZE))
        rows = await cursor.fetchall()
        if not rows:
            break
        updates = []
        for row in rows:
            file_id, exif_time, media_type = row[0], row[1], row[2]
            capture_time, kind = _client_capture_time(exif_time, media_type)
            if capture_time is not None:
                updates.append((capture_time, kind, file_id))
        last_id = rows[-1][0]
        if updates:
            cursor = await db.executemany(_SEED_UPDATE_SQL, updates)
            written += max(cursor.rowcount, 0)
        await db.commit()
        if len(rows) < SEED_PAGE_SIZE:
            break
    return written


async def _extract_one(
    file_path: str, media_type: str | None, exif_time: str | None
) -> MediaMetadata:
    """Extract one file for the backfill; never raises (except cancellation)."""
    exists = "/.trash/" not in file_path and await asyncio.to_thread(
        os.path.isfile, file_path
    )
    if not exists:
        # Missing file or a zombie row under .trash: mark attempted so it is not
        # selected again every round.
        capture_time, kind = _client_capture_time(exif_time, media_type)
        logger.debug("Backfill: file missing or in trash: %s", file_path)
        return MediaMetadata(
            status="error", capture_time=capture_time, capture_time_kind=kind
        )
    try:
        meta = await asyncio.to_thread(
            extract_media_metadata, file_path, media_type, exif_time,
            ffprobe_timeout=BACKFILL_FFPROBE_TIMEOUT,
        )
    except Exception:  # defensive: extract_media_metadata is documented not to raise
        logger.debug("Backfill extraction raised for %s", file_path, exc_info=True)
        capture_time, kind = _client_capture_time(exif_time, media_type)
        meta = MediaMetadata(
            status="error", capture_time=capture_time, capture_time_kind=kind
        )
    if meta.status == "timeout":
        # 15 s is already generous: treat as broken and stop retrying.
        meta = dataclasses.replace(meta, status="error")
    if meta.status != "ok":
        logger.debug("Backfill: %s for %s", meta.status, file_path)
    return meta


async def run_backfill_round(db: aiosqlite.Connection) -> BackfillStats:
    """Phase 2: extract every pending row once, in id order, batch by batch."""
    stats = BackfillStats()
    started_at = time.monotonic()
    has_ffprobe = 1 if ffprobe_path() else 0
    started_logged = False
    try:
        cursor = await db.execute(_PENDING_COUNT_SQL, (CURRENT_META_VERSION, has_ffprobe))
        pending = (await cursor.fetchone())[0]
        if not pending:
            return stats
        logger.info("Metadata backfill started: pending=%d", pending)
        started_logged = True

        last_id = 0
        while True:
            cursor = await db.execute(
                _BATCH_SELECT_SQL,
                (last_id, CURRENT_META_VERSION, has_ffprobe, BATCH_SIZE),
            )
            rows = await cursor.fetchall()
            if not rows:
                break

            results: list[tuple[int, str, MediaMetadata]] = []
            for row in rows:
                file_id, file_path, media_type, exif_time = row[0], row[1], row[2], row[3]
                meta = await _extract_one(file_path, media_type, exif_time)
                results.append((file_id, file_path, meta))

            # One call per row (same transaction, one commit per batch) so the
            # per-status counters only include rows that were actually written.
            for entry in results:
                if await apply_media_metadata_many(db, [entry]):
                    stats.processed += 1
                    status = entry[2].status
                    if status == "ok":
                        stats.ok += 1
                    elif status == "no_ffprobe":
                        stats.no_ffprobe += 1
                    else:
                        stats.error += 1
                else:
                    stats.skipped += 1
            await db.commit()
            last_id = rows[-1][0]
            await asyncio.sleep(BATCH_PAUSE_SECONDS)
    except aiosqlite.Error:
        logger.warning(
            "Metadata backfill round aborted by a database error; will retry next round",
            exc_info=True,
        )
        try:
            await db.rollback()
        except aiosqlite.Error:
            pass
    finally:
        stats.elapsed = time.monotonic() - started_at
        if started_logged:
            logger.info(
                "Metadata backfill finished: processed=%d ok=%d error=%d "
                "no_ffprobe=%d skipped=%d in %.1fs",
                stats.processed,
                stats.ok,
                stats.error,
                stats.no_ffprobe,
                stats.skipped,
                stats.elapsed,
            )
    return stats


async def metadata_backfill_task(interval_seconds: int = ROUND_INTERVAL_SECONDS) -> None:
    """Periodically backfill statistics metadata (loop like cleanup_expired_trash_task)."""
    settings = get_settings()
    # Log the missing-ffprobe WARNING at startup rather than on first use.
    ffprobe_path()
    seeded = False

    while True:
        try:
            if _database_ready():
                db = await aiosqlite.connect(sqlite_path_from_url(settings.database_url))
                db.row_factory = aiosqlite.Row
                try:
                    if not seeded:
                        count = await seed_capture_times(db)
                        seeded = True
                        if count > 0:
                            logger.info(
                                "Metadata backfill seeded capture_time for %d rows", count
                            )
                    await run_backfill_round(db)
                finally:
                    await db.close()
        except asyncio.CancelledError:
            logger.info("Metadata backfill task cancelled")
            raise
        except Exception:
            logger.exception("Error during metadata backfill")

        await asyncio.sleep(interval_seconds)
