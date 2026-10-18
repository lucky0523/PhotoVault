"""Background backfill of the file-name search columns on ``file_records``.

``file_name_key`` / ``file_ext`` (``app.services.filename_search``) are written
by the upload paths; rows created before the columns existed, and rows inserted
by scripts or tests without them, are NULL. The timeline queries fall back to
computing NULL values in Python, so results are correct either way: this task is
only a performance optimisation.

Each round walks every row with a NULL derived column in id order, 1000 rows a
batch, and writes the values with ``WHERE id = ? AND file_name = ?`` so a
concurrent rename is never overwritten with a stale key (the rename trigger
``trg_file_records_name_derived_stale`` keeps such rows NULL for the next
round). Trashed rows are filled too: harmless and simpler.
"""

from __future__ import annotations

import asyncio
import logging
import time

import aiosqlite

from app.core.config import get_settings, sqlite_path_from_url
from app.services.background_tasks import _database_ready, run_periodic_db_task
from app.services.filename_search import derived_name_columns

logger = logging.getLogger("photovault.filename_key_backfill")

BATCH_SIZE = 1000
ROUND_INTERVAL_SECONDS = 3600
BATCH_PAUSE_SECONDS = 0.05

_BATCH_SELECT_SQL = """
SELECT id, file_name FROM file_records
WHERE id > ? AND (file_name_key IS NULL OR file_ext IS NULL)
ORDER BY id LIMIT ?
"""

_UPDATE_SQL = """
UPDATE file_records SET file_name_key = ?, file_ext = ?
WHERE id = ? AND file_name = ?
"""


async def run_filename_key_backfill_round(db: aiosqlite.Connection) -> int:
    """Fill every NULL ``file_name_key`` / ``file_ext`` once. Returns rows written.

    A database error aborts the round (WARNING, rollback) and returns the rows
    written so far; the next round picks up the rest.
    """
    written = 0
    started_at = time.monotonic()
    last_id = 0
    try:
        while True:
            cursor = await db.execute(_BATCH_SELECT_SQL, (last_id, BATCH_SIZE))
            rows = await cursor.fetchall()
            if not rows:
                break
            updates = [
                (*derived_name_columns(row[1]), row[0], row[1]) for row in rows
            ]
            cursor = await db.executemany(_UPDATE_SQL, updates)
            written += max(cursor.rowcount, 0)
            await db.commit()
            last_id = rows[-1][0]
            await asyncio.sleep(BATCH_PAUSE_SECONDS)
    except aiosqlite.Error:
        logger.warning(
            "File name search key backfill aborted by a database error; "
            "will retry next round",
            exc_info=True,
        )
        try:
            await db.rollback()
        except aiosqlite.Error:
            pass
        return written
    if written > 0:
        logger.info(
            "File name search keys backfilled: rows=%d in %.1fs",
            written,
            time.monotonic() - started_at,
        )
    return written


async def filename_key_backfill_task(
    interval_seconds: int = ROUND_INTERVAL_SECONDS,
) -> None:
    """Periodically backfill the file-name search columns.

    Shares the loop of the other database tasks (``run_periodic_db_task``): the
    first round runs as soon as the database is ready, then every
    ``interval_seconds`` to pick up rows inserted without the columns.
    """
    settings = get_settings()

    async def _round() -> None:
        db = await aiosqlite.connect(sqlite_path_from_url(settings.database_url))
        try:
            await run_filename_key_backfill_round(db)
        finally:
            await db.close()

    await run_periodic_db_task(
        _round,
        interval_seconds=interval_seconds,
        task_logger=logger,
        task_label="File name search key backfill",
        cancelled_message="File name search key backfill task cancelled",
        error_message="Error during file name search key backfill",
        # This module's own name, read here at call time, so tests can patch it.
        is_ready=_database_ready,
    )
