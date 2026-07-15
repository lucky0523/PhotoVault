"""In-process analysis work queue.

A single module-level :class:`asyncio.Queue` holds photos waiting to be
analyzed. Producers (the upload link and the "re-analyze" endpoint / backfill)
enqueue work with :func:`enqueue_analysis` / :func:`enqueue_reanalysis_all`; the
``analysis_worker_task`` in :mod:`app.services.background_tasks` is the sole
consumer.

Queue item contract
-------------------
Every item on the queue is a ``(user_id, file_id)`` tuple. The order is chosen
to match :meth:`AnalysisService.analyze_file(user_id, file_id)` so the worker
can unpack and dispatch directly.

Design notes
------------
- The queue is process-local; work not yet drained when the process stops is
  recovered by the backfill mechanism, not persisted here (requirement 7.3).
- Enqueueing MUST NOT fail the caller (e.g. must not break an upload response).
  :func:`enqueue_analysis` therefore swallows and logs any error, including the
  case where the queue has not been created yet or there is no running loop.
- On Python 3.11+ an :class:`asyncio.Queue` can be constructed and have
  ``put_nowait`` called without a running event loop, so lazy creation on first
  access is safe for both the worker (running on the loop) and producers.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

logger = logging.getLogger("photovault.analysis_queue")

# Module-level singleton queue, created lazily on first access.
_queue: Optional["asyncio.Queue[tuple[int, int]]"] = None


def get_analysis_queue() -> "asyncio.Queue[tuple[int, int]]":
    """Return the process-wide analysis queue, creating it on first use.

    The queue holds ``(user_id, file_id)`` tuples. It is unbounded so that
    producers never block.

    Returns:
        The shared :class:`asyncio.Queue` singleton.
    """
    global _queue
    if _queue is None:
        _queue = asyncio.Queue()
    return _queue


def reset_analysis_queue() -> None:
    """Drop the current queue singleton.

    Intended for tests that need an isolated queue per case. A subsequent
    :func:`get_analysis_queue` call will create a fresh queue.
    """
    global _queue
    _queue = None


def enqueue_analysis(file_id: int, user_id: int) -> bool:
    """Enqueue a single photo for analysis (non-blocking, best-effort).

    This is safe to call from any producer, including request handlers on the
    hot path: it never raises and never blocks. If the queue cannot accept the
    item for any reason (not yet created, no running loop, full), the failure is
    logged and ``False`` is returned so the caller can continue unaffected
    (requirement 7.1).

    Note the argument order is ``(file_id, user_id)`` for call-site ergonomics;
    the item stored on the queue is normalized to ``(user_id, file_id)`` to
    match :meth:`AnalysisService.analyze_file`.

    Args:
        file_id: ``file_records.id`` of the photo to analyze.
        user_id: Owner of the file (for user isolation).

    Returns:
        ``True`` if the item was enqueued, ``False`` if it was dropped.
    """
    try:
        queue = get_analysis_queue()
        queue.put_nowait((user_id, file_id))
        return True
    except Exception:  # noqa: BLE001 - enqueue must never break the caller
        logger.warning(
            "Failed to enqueue analysis for file_id=%s user_id=%s; skipping",
            file_id,
            user_id,
            exc_info=True,
        )
        return False


async def enqueue_reanalysis_all(user_id: int, settings: object = None) -> int:
    """Enqueue all of a user's live photos for re-analysis.

    Queries ``file_records`` for the user's own, non-reference, non-deleted
    files and enqueues each ``file_id`` for analysis. Used by the "re-analyze
    existing photos" flow (requirement 7.3). References are skipped because they
    resolve to the same physical file as their target, and deleted/purged
    records are skipped because they no longer have analyzable content.

    Args:
        user_id: The user whose files should be re-analyzed.
        settings: Optional settings object exposing ``database_url``; defaults to
            the application settings singleton. Injectable for testing.

    Returns:
        The number of files enqueued.
    """
    if settings is None:
        from app.core.config import get_settings

        settings = get_settings()

    from app.core.database import _create_connection

    db = await _create_connection(settings.database_url)
    try:
        cursor = await db.execute(
            """SELECT id FROM file_records
               WHERE user_id = ?
                 AND (is_reference = 0 OR is_reference IS NULL)
                 AND deleted_at IS NULL
                 AND purged_at IS NULL""",
            (user_id,),
        )
        rows = await cursor.fetchall()
    finally:
        await db.close()

    queued = 0
    for row in rows:
        if enqueue_analysis(row["id"], user_id):
            queued += 1

    logger.info("Enqueued %d file(s) for re-analysis (user_id=%s)", queued, user_id)
    return queued
