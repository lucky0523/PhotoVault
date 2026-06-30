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
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

import aiosqlite

from app.core.config import get_settings

logger = logging.getLogger("photovault.background_tasks")

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
            await _run_session_cleanup(settings.database_url, settings.storage_root)
        except asyncio.CancelledError:
            logger.info("Expired session cleanup task cancelled")
            raise
        except Exception:
            logger.exception("Error during expired session cleanup")

        await asyncio.sleep(interval_seconds)


async def _run_session_cleanup(database_url: str, storage_root: str) -> int:
    """Execute one round of expired session cleanup.

    Args:
        database_url: Path to the SQLite database.
        storage_root: Root storage directory.

    Returns:
        Number of sessions cleaned up.
    """
    now = datetime.now(timezone.utc).isoformat()

    db = await aiosqlite.connect(database_url)
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

        chunks_base = Path(storage_root) / ".chunks"
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
            await _run_thumbnail_cleanup(settings.database_url, settings.storage_root)
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
            _check_disk_space(settings.storage_root)
        except asyncio.CancelledError:
            logger.info("Disk space monitor task cancelled")
            raise
        except Exception:
            logger.exception("Error during disk space check")

        await asyncio.sleep(interval_seconds)


def _check_disk_space(storage_root: str) -> dict[str, float]:
    """Check disk space and update module-level stats.

    Args:
        storage_root: The storage root directory to check.

    Returns:
        Dictionary with total_gb, used_gb, available_gb.
    """
    global _disk_stats

    storage_path = Path(storage_root)
    if not storage_path.exists():
        logger.warning("Storage root does not exist: %s", storage_root)
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
            storage_root,
        )
    elif available_gb < DISK_WARNING_THRESHOLD_GB:
        logger.warning(
            "Disk space low! Available: %.2f GB (< %.1f GB threshold) on %s",
            available_gb,
            DISK_WARNING_THRESHOLD_GB,
            storage_root,
        )

    return _disk_stats


async def _run_thumbnail_cleanup(database_url: str, storage_root: str) -> dict[str, int]:
    """Execute one round of thumbnail cache cleanup.

    Cleans up:
    - Orphaned thumbnails (no corresponding file_hash in file_records)
    - Expired thumbnails (not accessed in THUMBNAIL_EXPIRY_DAYS days)
    - Oldest thumbnails when cache exceeds MAX_THUMBNAIL_CACHE_SIZE_MB

    Args:
        database_url: Path to the SQLite database.
        storage_root: Root storage directory.

    Returns:
        Dictionary with cleanup counts: {'orphaned': N, 'expired': N, 'size_limited': N}.
    """
    thumbnails_root = Path(storage_root) / ".thumbnails"
    if not thumbnails_root.exists():
        return {"orphaned": 0, "expired": 0, "size_limited": 0}

    db_path = database_url
    if db_path.startswith("sqlite+aiosqlite://"):
        db_path = db_path[len("sqlite+aiosqlite://"):]
    elif db_path.startswith("sqlite://"):
        db_path = db_path[len("sqlite://"):]

    db = await aiosqlite.connect(db_path)
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


async def _run_trash_purge(database_url: str, storage_root: str, retention_days: int) -> int:
    """Execute one round of trash auto-purge.

    Purges (marks purged_at + deletes physical file) trash items older
    than retention_days. Records are kept for client sync.

    Args:
        database_url: Database URL or path.
        storage_root: Storage root directory.
        retention_days: Trash retention period in days.

    Returns:
        Number of items purged.
    """
    db_path = database_url
    if db_path.startswith("sqlite+aiosqlite://"):
        db_path = db_path[len("sqlite+aiosqlite://"):]
    elif db_path.startswith("sqlite://"):
        db_path = db_path[len("sqlite://"):]

    db = await aiosqlite.connect(db_path)
    db.row_factory = aiosqlite.Row
    try:
        await db.execute("PRAGMA foreign_keys=ON;")
        from app.services.file_browse_service import FileBrowseService
        service = FileBrowseService(db, storage_root, trash_retention_days=retention_days)
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
            count = await _run_trash_purge(
                settings.database_url, settings.storage_root, settings.trash_retention_days
            )
            if count > 0:
                logger.info("Trash auto-purge: %d items purged", count)
        except asyncio.CancelledError:
            logger.info("Trash purge task cancelled")
            raise
        except Exception:
            logger.exception("Error during trash purge")

        await asyncio.sleep(interval_seconds)


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
