"""Background tasks for PhotoVault server.

Provides periodic background tasks that run during the application lifecycle:
- Expired upload session cleanup (every 6 hours)
- Disk space monitoring (every hour)

Tasks are started via asyncio.create_task in the FastAPI lifespan and cancelled
on shutdown. No external scheduler dependency required.
"""

from __future__ import annotations

import asyncio
import logging
import os
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

import aiosqlite

from app.core.config import get_settings, needs_provisioning, sqlite_path_from_url

logger = logging.getLogger("photovault.background_tasks")


def _database_ready() -> bool:
    """Whether the periodic tasks may touch the database.

    Guards first-run provisioning. Until an administrator picks a working
    directory there is deliberately no database, and ``aiosqlite.connect``
    *creates* the file it is pointed at — so an unguarded cleanup round would
    materialise an empty database at the pre-provisioning path, which is exactly
    the misplacement the wizard exists to prevent.

    Re-checked every round rather than once at startup, so the tasks resume by
    themselves as soon as setup finishes; no restart needed.
    """
    settings = get_settings()
    if needs_provisioning(settings):
        return False
    return os.path.isfile(settings.database_path)

# Module-level state for disk usage stats accessible by the health endpoint
_disk_stats: dict[str, float] = {
    "total_gb": 0.0,
    "used_gb": 0.0,
    "available_gb": 0.0,
}

# Configuration defaults (can be overridden for testing)
CLEANUP_INTERVAL_SECONDS: int = 6 * 60 * 60  # 6 hours
DISK_CHECK_INTERVAL_SECONDS: int = 60 * 60  # 1 hour
DISK_WARNING_THRESHOLD_GB: float = 1.0
DISK_ERROR_THRESHOLD_GB: float = 0.5

# Thumbnail cache cleanup configuration
THUMBNAIL_CLEANUP_INTERVAL_SECONDS: int = 24 * 60 * 60  # 24 hours
THUMBNAIL_EXPIRY_DAYS: int = 30  # Delete thumbnails not accessed in N days
MAX_THUMBNAIL_CACHE_SIZE_MB: int = 500  # Max cache size before cleanup


def get_disk_stats() -> dict[str, float]:
    """Return the latest disk usage statistics.

    Returns:
        Dictionary with keys: total_gb, used_gb, available_gb.
    """
    return _disk_stats.copy()


def refresh_disk_stats(media_root: Optional[str] = None) -> dict[str, float]:
    """Recompute disk usage statistics immediately and return them.

    The periodic monitor only refreshes once an hour, and it has not run at all
    when the background tasks were never started (tests, or the first moments
    after boot). The "关于服务端" page needs a real number rather than the
    all-zeros initial state, so it can ask for a fresh reading.

    Args:
        media_root: Directory to measure. Defaults to the configured photo
            storage directory, since that is the volume that actually fills up.

    Returns:
        Dictionary with keys: total_gb, used_gb, available_gb.
    """
    root = media_root if media_root is not None else get_settings().media_root
    return _check_disk_space(root)


async def cleanup_expired_sessions_task(
    interval_seconds: int = CLEANUP_INTERVAL_SECONDS,
) -> None:
    """Periodically clean up expired upload sessions.

    Runs in an infinite loop, sleeping for `interval_seconds` between each run.
    For each expired session (expires_at < now, status = 'active'):
    - Deletes temporary chunk files from disk
    - Updates session status to 'expired' in the database

    Args:
        interval_seconds: Time between cleanup runs in seconds.
    """
    settings = get_settings()

    while True:
        try:
            if _database_ready():
                await _run_session_cleanup(settings.database_url, settings.media_root)
        except asyncio.CancelledError:
            logger.info("Expired session cleanup task cancelled")
            raise
        except Exception:
            logger.exception("Error during expired session cleanup")

        await asyncio.sleep(interval_seconds)


async def _run_session_cleanup(database_url: str, media_root: str) -> int:
    """Execute one round of expired session cleanup.

    Args:
        database_url: SQLite URL or path for the database.
        media_root: Photo storage directory (parent of ``.chunks``).

    Returns:
        Number of sessions cleaned up.
    """
    now = datetime.now(timezone.utc).isoformat()

    db = await aiosqlite.connect(sqlite_path_from_url(database_url))
    db.row_factory = aiosqlite.Row
    try:
        await db.execute("PRAGMA foreign_keys=ON;")

        # Find expired active sessions
        cursor = await db.execute(
            "SELECT id FROM upload_sessions WHERE expires_at < ? AND status = 'active'",
            (now,),
        )
        expired_rows = await cursor.fetchall()

        if not expired_rows:
            return 0

        chunks_base = Path(media_root) / ".chunks"
        count = 0

        for row in expired_rows:
            session_id = row["id"]
            # Remove chunk directory from disk
            chunk_dir = chunks_base / session_id
            if chunk_dir.exists():
                shutil.rmtree(chunk_dir)

            # Update session status to 'expired'
            await db.execute(
                "UPDATE upload_sessions SET status = 'expired', updated_at = ? WHERE id = ?",
                (now, session_id),
            )
            count += 1

        await db.commit()
        logger.info("Cleaned up %d expired upload session(s)", count)
        return count
    finally:
        await db.close()


async def cleanup_thumbnail_cache_task(
    interval_seconds: int = THUMBNAIL_CLEANUP_INTERVAL_SECONDS,
) -> None:
    """Periodically clean up stale thumbnail cache files.

    Runs in an infinite loop, sleeping for `interval_seconds` between each run.
    Cleans up:
    - Orphaned thumbnails (no corresponding file record in database)
    - Expired thumbnails (not accessed in THUMBNAIL_EXPIRY_DAYS days)
    - Oldest thumbnails when cache exceeds MAX_THUMBNAIL_CACHE_SIZE_MB

    Args:
        interval_seconds: Time between cleanup runs in seconds.
    """
    settings = get_settings()

    while True:
        try:
            if _database_ready():
                await _run_thumbnail_cleanup(settings.database_url, settings.media_root)
        except asyncio.CancelledError:
            logger.info("Thumbnail cache cleanup task cancelled")
            raise
        except Exception:
            logger.exception("Error during thumbnail cache cleanup")

        await asyncio.sleep(interval_seconds)


async def disk_space_monitor_task(
    interval_seconds: int = DISK_CHECK_INTERVAL_SECONDS,
) -> None:
    """Periodically check available disk space on the storage partition.

    Runs in an infinite loop, sleeping for `interval_seconds` between each check.
    - If available space < 1GB: logs WARNING
    - If available space < 500MB: logs ERROR

    Also updates the module-level _disk_stats dict for the health endpoint.

    Args:
        interval_seconds: Time between disk checks in seconds.
    """
    settings = get_settings()

    while True:
        try:
            _check_disk_space(settings.media_root)
        except asyncio.CancelledError:
            logger.info("Disk space monitor task cancelled")
            raise
        except Exception:
            logger.exception("Error during disk space check")

        await asyncio.sleep(interval_seconds)


def _check_disk_space(media_root: str) -> dict[str, float]:
    """Check disk space and update module-level stats.

    Args:
        media_root: The photo storage directory to measure. This is the volume
            uploads consume, which is not necessarily the one holding the
            database, logs or models.

    Returns:
        Dictionary with total_gb, used_gb, available_gb.
    """
    global _disk_stats

    storage_path = Path(media_root)
    if not storage_path.exists():
        logger.warning("Photo storage directory does not exist: %s", media_root)
        return _disk_stats

    usage = shutil.disk_usage(str(storage_path))
    total_gb = usage.total / (1024 ** 3)
    used_gb = usage.used / (1024 ** 3)
    available_gb = usage.free / (1024 ** 3)

    _disk_stats = {
        "total_gb": round(total_gb, 2),
        "used_gb": round(used_gb, 2),
        "available_gb": round(available_gb, 2),
    }

    if available_gb < DISK_ERROR_THRESHOLD_GB:
        logger.error(
            "CRITICAL: Disk space very low! Available: %.2f GB (< %.1f GB threshold) on %s",
            available_gb,
            DISK_ERROR_THRESHOLD_GB,
            media_root,
        )
    elif available_gb < DISK_WARNING_THRESHOLD_GB:
        logger.warning(
            "Disk space low! Available: %.2f GB (< %.1f GB threshold) on %s",
            available_gb,
            DISK_WARNING_THRESHOLD_GB,
            media_root,
        )

    return _disk_stats


async def _run_thumbnail_cleanup(database_url: str, media_root: str) -> dict[str, int]:
    """Execute one round of thumbnail cache cleanup.

    Cleans up:
    - Orphaned thumbnails (no corresponding file_hash in file_records)
    - Expired thumbnails (not accessed in THUMBNAIL_EXPIRY_DAYS days)
    - Oldest thumbnails when cache exceeds MAX_THUMBNAIL_CACHE_SIZE_MB

    Args:
        database_url: SQLite URL or path for the database.
        media_root: Photo storage directory (parent of ``.thumbnails``).

    Returns:
        Dictionary with cleanup counts: {'orphaned': N, 'expired': N, 'size_limited': N}.
    """
    thumbnails_root = Path(media_root) / ".thumbnails"
    if not thumbnails_root.exists():
        return {"orphaned": 0, "expired": 0, "size_limited": 0}

    db = await aiosqlite.connect(sqlite_path_from_url(database_url))
    db.row_factory = aiosqlite.Row
    try:
        await db.execute("PRAGMA foreign_keys=ON;")

        cursor = await db.execute("SELECT DISTINCT file_hash FROM file_records")
        rows = await cursor.fetchall()
        valid_hashes = {row["file_hash"] for row in rows}

        expired_time = datetime.now(timezone.utc) - timedelta(days=THUMBNAIL_EXPIRY_DAYS)

        orphaned_count = 0
        expired_count = 0
        size_limited_count = 0

        for username_dir in thumbnails_root.iterdir():
            if not username_dir.is_dir():
                continue

            thumbnails = []
            for thumb_file in username_dir.glob("*.jpg"):
                try:
                    file_hash = thumb_file.stem.rsplit("_", 1)[0]
                    atime = datetime.fromtimestamp(thumb_file.stat().st_atime, timezone.utc)
                    thumbnails.append((thumb_file, file_hash, atime))
                except Exception:
                    continue

            for thumb_file, file_hash, atime in thumbnails:
                if file_hash not in valid_hashes:
                    thumb_file.unlink()
                    orphaned_count += 1
                    continue

                if atime < expired_time:
                    thumb_file.unlink()
                    expired_count += 1
                    continue

            remaining = [t for t in thumbnails if t[1] in valid_hashes and t[2] >= expired_time]
            if remaining:
                remaining.sort(key=lambda x: x[2])
                total_size_mb = sum(t[0].stat().st_size for t in remaining) / (1024 ** 2)
                if total_size_mb > MAX_THUMBNAIL_CACHE_SIZE_MB:
                    target_size_mb = MAX_THUMBNAIL_CACHE_SIZE_MB * 0.8
                    current_size_mb = total_size_mb
                    for thumb_file, _, _ in remaining:
                        if current_size_mb <= target_size_mb:
                            break
                        thumb_file.unlink()
                        current_size_mb -= thumb_file.stat().st_size / (1024 ** 2)
                        size_limited_count += 1

        if orphaned_count > 0 or expired_count > 0 or size_limited_count > 0:
            logger.info(
                "Thumbnail cache cleanup completed: "
                "orphaned=%d, expired=%d, size_limited=%d",
                orphaned_count, expired_count, size_limited_count
            )

        return {
            "orphaned": orphaned_count,
            "expired": expired_count,
            "size_limited": size_limited_count,
        }
    finally:
        await db.close()


# ---------------------------------------------------------------------------
# Trash auto-purge task
# ---------------------------------------------------------------------------

TRASH_PURGE_INTERVAL_SECONDS: int = 24 * 60 * 60  # 24 hours


async def _run_trash_purge(database_url: str, media_root: str, retention_days: int) -> int:
    """Execute one round of trash auto-purge.

    Purges (marks purged_at + deletes physical file) trash items older
    than retention_days. Records are kept for client sync.

    Args:
        database_url: SQLite URL or path for the database.
        media_root: Photo storage directory (parent of the per-user ``.trash``).
        retention_days: Trash retention period in days.

    Returns:
        Number of items purged.
    """
    db = await aiosqlite.connect(sqlite_path_from_url(database_url))
    db.row_factory = aiosqlite.Row
    try:
        await db.execute("PRAGMA foreign_keys=ON;")
        from app.services.file_browse_service import FileBrowseService
        service = FileBrowseService(db, media_root, trash_retention_days=retention_days)
        return await service.auto_purge_expired(expiry_days=retention_days)
    finally:
        await db.close()


async def cleanup_expired_trash_task(
    interval_seconds: int = TRASH_PURGE_INTERVAL_SECONDS,
) -> None:
    """Periodically purge expired trash items.

    Runs in an infinite loop, sleeping for `interval_seconds` between each run.
    Purges trash items older than trash_retention_days from settings (marks purged_at,
    deletes physical file, keeps record for client sync).
    """
    settings = get_settings()

    while True:
        try:
            count = 0
            if _database_ready():
                count = await _run_trash_purge(
                    settings.database_url,
                    settings.media_root,
                    settings.trash_retention_days,
                )
            if count > 0:
                logger.info("Trash auto-purge: %d items purged", count)
        except asyncio.CancelledError:
            logger.info("Trash purge task cancelled")
            raise
        except Exception:
            logger.exception("Error during trash purge")

        await asyncio.sleep(interval_seconds)


# ---------------------------------------------------------------------------
# Analysis worker task
# ---------------------------------------------------------------------------


async def analysis_worker_task() -> None:
    """Consume the analysis queue and analyze each photo in order.

    Runs in an infinite loop, awaiting ``(user_id, file_id)`` items from the
    process-wide analysis queue and dispatching each to
    :meth:`AnalysisService.analyze_file`. A single photo's failure is logged and
    swallowed so the worker keeps draining the queue rather than crashing
    (requirement 7.4). Cancellation (on shutdown) is propagated like the other
    background tasks.
    """
    from app.services.analysis_queue import get_analysis_queue
    from app.services.analysis_service import AnalysisService

    queue = get_analysis_queue()
    service = AnalysisService()

    while True:
        try:
            user_id, file_id = await queue.get()
        except asyncio.CancelledError:
            logger.info("Analysis worker task cancelled")
            raise

        try:
            await service.analyze_file(user_id, file_id)
        except asyncio.CancelledError:
            logger.info("Analysis worker task cancelled")
            raise
        except Exception:
            logger.exception(
                "Error analyzing file_id=%s user_id=%s", file_id, user_id
            )
        finally:
            queue.task_done()


async def start_background_tasks() -> list[asyncio.Task]:
    """Start all background tasks and return task references.

    Returns:
        List of asyncio.Task objects that should be cancelled on shutdown.
    """
    tasks = [
        asyncio.create_task(
            cleanup_expired_sessions_task(),
            name="cleanup_expired_sessions",
        ),
        asyncio.create_task(
            disk_space_monitor_task(),
            name="disk_space_monitor",
        ),
        asyncio.create_task(
            cleanup_thumbnail_cache_task(),
            name="cleanup_thumbnail_cache",
        ),
        asyncio.create_task(
            cleanup_expired_trash_task(),
            name="cleanup_expired_trash",
        ),
        asyncio.create_task(
            analysis_worker_task(),
            name="analysis_worker",
        ),
    ]
    logger.info("Background tasks started: %s", [t.get_name() for t in tasks])
    return tasks


async def stop_background_tasks(tasks: list[asyncio.Task]) -> None:
    """Cancel and await all background tasks.

    Args:
        tasks: List of asyncio.Task objects to cancel.
    """
    for task in tasks:
        task.cancel()

    # Wait for all tasks to finish cancellation
    results = await asyncio.gather(*tasks, return_exceptions=True)
    for task, result in zip(tasks, results):
        if isinstance(result, asyncio.CancelledError):
            logger.debug("Task %s cancelled successfully", task.get_name())
        elif isinstance(result, Exception):
            logger.error("Task %s raised error during shutdown: %s", task.get_name(), result)

    logger.info("All background tasks stopped")
