"""Tests for background tasks: expired session cleanup and disk space monitoring."""

import asyncio
import shutil
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch

import aiosqlite
import pytest

from app.services.background_tasks import (
    _run_session_cleanup,
    _check_disk_space,
    get_disk_stats,
    start_background_tasks,
    stop_background_tasks,
    cleanup_expired_sessions_task,
    disk_space_monitor_task,
    DISK_WARNING_THRESHOLD_GB,
    DISK_ERROR_THRESHOLD_GB,
)


@pytest.fixture
async def db_with_sessions(tmp_path):
    """Create a temporary database with upload_sessions table and test data."""
    db_path = str(tmp_path / "test.db")
    storage_root = str(tmp_path / "storage")
    Path(storage_root).mkdir()

    db = await aiosqlite.connect(db_path)
    db.row_factory = aiosqlite.Row

    await db.execute("""
        CREATE TABLE IF NOT EXISTS upload_sessions (
            id TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL,
            file_hash TEXT NOT NULL,
            file_name TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            total_chunks INTEGER NOT NULL,
            received_chunks TEXT DEFAULT '[]',
            target_path TEXT NOT NULL,
            device_name TEXT NOT NULL,
            original_path TEXT NOT NULL,
            status TEXT DEFAULT 'active',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            expires_at TIMESTAMP NOT NULL
        )
    """)
    await db.commit()

    yield db, db_path, storage_root

    await db.close()


class TestExpiredSessionCleanup:
    """Tests for expired upload session cleanup."""

    async def test_cleanup_removes_expired_sessions(self, db_with_sessions):
        """Expired active sessions are cleaned up and marked as 'expired'."""
        db, db_path, storage_root = db_with_sessions
        now = datetime.now(timezone.utc)
        expired_time = (now - timedelta(hours=1)).isoformat()

        # Create an expired session with chunk files
        session_id = "expired-session-001"
        chunk_dir = Path(storage_root) / ".chunks" / session_id
        chunk_dir.mkdir(parents=True)
        (chunk_dir / "chunk_000000").write_bytes(b"test data")

        await db.execute(
            """INSERT INTO upload_sessions
               (id, user_id, file_hash, file_name, file_size, total_chunks,
                target_path, device_name, original_path, status, expires_at)
               VALUES (?, 1, 'abc123', 'photo.jpg', 1024, 1,
                       '/photos', 'phone', '/DCIM/photo.jpg', 'active', ?)""",
            (session_id, expired_time),
        )
        await db.commit()

        # Run cleanup
        count = await _run_session_cleanup(db_path, storage_root)

        assert count == 1
        # Chunk directory should be removed
        assert not chunk_dir.exists()
        # Session status should be 'expired'
        cursor = await db.execute(
            "SELECT status FROM upload_sessions WHERE id = ?", (session_id,)
        )
        row = await cursor.fetchone()
        assert row["status"] == "expired"

    async def test_cleanup_ignores_active_non_expired_sessions(self, db_with_sessions):
        """Sessions that have not expired yet should not be cleaned up."""
        db, db_path, storage_root = db_with_sessions
        future_time = (datetime.now(timezone.utc) + timedelta(days=3)).isoformat()

        session_id = "active-session-001"
        chunk_dir = Path(storage_root) / ".chunks" / session_id
        chunk_dir.mkdir(parents=True)
        (chunk_dir / "chunk_000000").write_bytes(b"data")

        await db.execute(
            """INSERT INTO upload_sessions
               (id, user_id, file_hash, file_name, file_size, total_chunks,
                target_path, device_name, original_path, status, expires_at)
               VALUES (?, 1, 'def456', 'photo2.jpg', 2048, 1,
                       '/photos', 'phone', '/DCIM/photo2.jpg', 'active', ?)""",
            (session_id, future_time),
        )
        await db.commit()

        count = await _run_session_cleanup(db_path, storage_root)

        assert count == 0
        assert chunk_dir.exists()
        cursor = await db.execute(
            "SELECT status FROM upload_sessions WHERE id = ?", (session_id,)
        )
        row = await cursor.fetchone()
        assert row["status"] == "active"

    async def test_cleanup_ignores_already_completed_sessions(self, db_with_sessions):
        """Sessions with status != 'active' should not be cleaned up even if expired."""
        db, db_path, storage_root = db_with_sessions
        expired_time = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()

        session_id = "completed-session-001"
        await db.execute(
            """INSERT INTO upload_sessions
               (id, user_id, file_hash, file_name, file_size, total_chunks,
                target_path, device_name, original_path, status, expires_at)
               VALUES (?, 1, 'ghi789', 'photo3.jpg', 4096, 2,
                       '/photos', 'phone', '/DCIM/photo3.jpg', 'completed', ?)""",
            (session_id, expired_time),
        )
        await db.commit()

        count = await _run_session_cleanup(db_path, storage_root)
        assert count == 0

    async def test_cleanup_handles_missing_chunk_directory(self, db_with_sessions):
        """Cleanup works even if the chunk directory was already removed."""
        db, db_path, storage_root = db_with_sessions
        expired_time = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()

        session_id = "no-chunks-session"
        await db.execute(
            """INSERT INTO upload_sessions
               (id, user_id, file_hash, file_name, file_size, total_chunks,
                target_path, device_name, original_path, status, expires_at)
               VALUES (?, 1, 'jkl012', 'photo4.jpg', 512, 1,
                       '/photos', 'phone', '/DCIM/photo4.jpg', 'active', ?)""",
            (session_id, expired_time),
        )
        await db.commit()

        # No chunk dir exists - should still succeed
        count = await _run_session_cleanup(db_path, storage_root)
        assert count == 1

        cursor = await db.execute(
            "SELECT status FROM upload_sessions WHERE id = ?", (session_id,)
        )
        row = await cursor.fetchone()
        assert row["status"] == "expired"

    async def test_cleanup_multiple_expired_sessions(self, db_with_sessions):
        """Multiple expired sessions are all cleaned up in one run."""
        db, db_path, storage_root = db_with_sessions
        expired_time = (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat()

        for i in range(5):
            session_id = f"multi-expired-{i:03d}"
            chunk_dir = Path(storage_root) / ".chunks" / session_id
            chunk_dir.mkdir(parents=True)
            (chunk_dir / "chunk_000000").write_bytes(b"data")

            await db.execute(
                """INSERT INTO upload_sessions
                   (id, user_id, file_hash, file_name, file_size, total_chunks,
                    target_path, device_name, original_path, status, expires_at)
                   VALUES (?, 1, ?, 'photo.jpg', 1024, 1,
                           '/photos', 'phone', '/DCIM/photo.jpg', 'active', ?)""",
                (session_id, f"hash_{i}", expired_time),
            )
        await db.commit()

        count = await _run_session_cleanup(db_path, storage_root)
        assert count == 5


class TestDiskSpaceMonitor:
    """Tests for disk space monitoring."""

    def test_check_disk_space_returns_stats(self, tmp_path):
        """_check_disk_space returns disk usage information."""
        storage_root = str(tmp_path)
        stats = _check_disk_space(storage_root)

        assert "total_gb" in stats
        assert "used_gb" in stats
        assert "available_gb" in stats
        assert stats["total_gb"] > 0
        assert stats["available_gb"] >= 0

    def test_check_disk_space_updates_module_stats(self, tmp_path):
        """_check_disk_space updates the module-level _disk_stats."""
        storage_root = str(tmp_path)
        _check_disk_space(storage_root)

        disk_stats = get_disk_stats()
        assert disk_stats["total_gb"] > 0

    def test_check_disk_space_nonexistent_path(self, tmp_path, caplog):
        """Logs warning when storage root does not exist."""
        import logging

        with caplog.at_level(logging.WARNING, logger="photovault.background_tasks"):
            _check_disk_space(str(tmp_path / "nonexistent"))

        assert "Storage root does not exist" in caplog.text

    def test_check_disk_space_warning_logged(self, tmp_path, caplog):
        """Logs WARNING when available space is below warning threshold."""
        import logging

        # Mock shutil.disk_usage to return low available space
        mock_usage = shutil._ntuple_diskusage(
            total=100 * 1024**3,
            used=99.5 * 1024**3,
            free=0.8 * 1024**3,  # 0.8 GB - below 1GB warning threshold
        )
        with patch("app.services.background_tasks.shutil.disk_usage", return_value=mock_usage):
            with caplog.at_level(logging.WARNING, logger="photovault.background_tasks"):
                _check_disk_space(str(tmp_path))

        assert "Disk space low" in caplog.text

    def test_check_disk_space_error_logged(self, tmp_path, caplog):
        """Logs ERROR when available space is below error threshold."""
        import logging

        # Mock shutil.disk_usage to return very low available space
        mock_usage = shutil._ntuple_diskusage(
            total=100 * 1024**3,
            used=99.7 * 1024**3,
            free=0.3 * 1024**3,  # 0.3 GB - below 500MB error threshold
        )
        with patch("app.services.background_tasks.shutil.disk_usage", return_value=mock_usage):
            with caplog.at_level(logging.ERROR, logger="photovault.background_tasks"):
                _check_disk_space(str(tmp_path))

        assert "CRITICAL" in caplog.text

    def test_get_disk_stats_returns_copy(self, tmp_path):
        """get_disk_stats returns a copy, not a reference to the internal dict."""
        _check_disk_space(str(tmp_path))
        stats1 = get_disk_stats()
        stats2 = get_disk_stats()
        assert stats1 is not stats2
        assert stats1 == stats2


class TestBackgroundTaskLifecycle:
    """Tests for starting and stopping background tasks."""

    async def test_start_and_stop_tasks(self):
        """Background tasks start and can be stopped cleanly."""
        with patch("app.services.background_tasks.get_settings") as mock_settings:
            mock_settings.return_value.storage_root = "/tmp"
            mock_settings.return_value.database_url = "/tmp/test.db"

            tasks = await start_background_tasks()
            assert len(tasks) == 2
            assert all(not t.done() for t in tasks)

            # Stop immediately
            await stop_background_tasks(tasks)
            assert all(t.done() for t in tasks)

    async def test_cleanup_task_handles_cancellation(self):
        """The cleanup task handles CancelledError gracefully."""
        with patch("app.services.background_tasks.get_settings") as mock_settings:
            mock_settings.return_value.database_url = "/tmp/nonexistent.db"
            mock_settings.return_value.storage_root = "/tmp"

            task = asyncio.create_task(
                cleanup_expired_sessions_task(interval_seconds=3600)
            )
            # Give task a moment to start
            await asyncio.sleep(0.05)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task

    async def test_disk_monitor_task_handles_cancellation(self):
        """The disk monitor task handles CancelledError gracefully."""
        with patch("app.services.background_tasks.get_settings") as mock_settings:
            mock_settings.return_value.storage_root = "/tmp"

            task = asyncio.create_task(
                disk_space_monitor_task(interval_seconds=3600)
            )
            await asyncio.sleep(0.05)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
