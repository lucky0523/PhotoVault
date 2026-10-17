"""Tests for background tasks: expired session cleanup and disk space monitoring."""

import asyncio
import fcntl
import logging
import os
import shutil
from datetime import datetime, timezone, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import aiosqlite
import pytest

from app.core.config import load_settings, needs_provisioning
from app.services import background_tasks as bt
from app.services.background_tasks import (
    _run_session_cleanup,
    _check_disk_space,
    get_disk_stats,
    start_background_tasks,
    stop_background_tasks,
    cleanup_expired_sessions_task,
    cleanup_expired_trash_task,
    cleanup_expired_versions_task,
    cleanup_thumbnail_cache_task,
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
        """Logs warning when the photo storage directory does not exist."""
        import logging

        with caplog.at_level(logging.WARNING, logger="photovault.background_tasks"):
            _check_disk_space(str(tmp_path / "nonexistent"))

        assert "Photo storage directory does not exist" in caplog.text

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
            assert len(tasks) == 8
            names = {t.get_name() for t in tasks}
            assert "metadata_backfill" in names
            assert {"cleanup_expired_versions", "hold_server_presence"} <= names
            assert all(not t.done() for t in tasks)

            # Stop immediately
            await stop_background_tasks(tasks)
            assert all(t.done() for t in tasks)

    @pytest.mark.parametrize(
        "order",
        [
            ("app.services.background_tasks", "app.services.metadata_backfill"),
            ("app.services.metadata_backfill", "app.services.background_tasks"),
        ],
        ids=["background-first", "backfill-first"],
    )
    def test_no_circular_import(self, order):
        """Both import orders work (background_tasks imports backfill lazily)."""
        import importlib
        import sys

        names = ("app.services.background_tasks", "app.services.metadata_backfill")
        saved = {name: sys.modules.get(name) for name in names}
        try:
            for name in names:
                sys.modules.pop(name, None)
            for name in order:
                importlib.import_module(name)
            assert hasattr(sys.modules["app.services.metadata_backfill"], "metadata_backfill_task")
            assert hasattr(sys.modules["app.services.background_tasks"], "start_background_tasks")
        finally:
            # Restore the original module objects (in sys.modules and as package
            # attributes) so later patches target the modules the code uses.
            import app.services as services_pkg

            for name, module in saved.items():
                attr = name.rsplit(".", 1)[1]
                if module is not None:
                    sys.modules[name] = module
                    setattr(services_pkg, attr, module)
                else:
                    sys.modules.pop(name, None)
                    if hasattr(services_pkg, attr):
                        delattr(services_pkg, attr)

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


_GUARDED_TASKS = [
    pytest.param(cleanup_expired_sessions_task, "_run_session_cleanup", "Expired session cleanup",
                 "Error during expired session cleanup", id="sessions"),
    pytest.param(cleanup_thumbnail_cache_task, "_run_thumbnail_cleanup", "Thumbnail cache cleanup",
                 "Error during thumbnail cache cleanup", id="thumbnails"),
    pytest.param(cleanup_expired_trash_task, "_run_trash_purge", "Trash purge",
                 "Error during trash purge", id="trash"),
    pytest.param(cleanup_expired_versions_task, "_run_version_maintenance",
                 "Version maintenance", "Error during version maintenance", id="versions"),
]


async def _cancel(*tasks: asyncio.Task) -> None:
    for task in tasks:
        task.cancel()
    for task in tasks:
        with pytest.raises(asyncio.CancelledError):
            await task


class TestDatabaseReadinessPolling:
    """Guarded tasks re-check readiness quickly instead of sleeping a full interval."""

    @pytest.fixture(autouse=True)
    def _setup(self, monkeypatch, tmp_path):
        monkeypatch.setattr(bt, "DB_NOT_READY_POLL_SECONDS", 0.01)
        # setup_logging() elsewhere in the suite turns propagation off.
        monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
        self.settings = SimpleNamespace(
            database_url=str(tmp_path / "x.db"),
            media_root=str(tmp_path),
            trash_retention_days=30,
        )

    @staticmethod
    def _runner(side_effect=None):
        called = asyncio.Event()
        effects = list(side_effect or [])

        async def run(*args):
            called.set()
            if effects:
                effect = effects.pop(0)
                if isinstance(effect, BaseException):
                    raise effect
            return 0

        return AsyncMock(side_effect=run), called

    @staticmethod
    def _ready_lines(caplog, label):
        return [
            r for r in caplog.records
            if r.getMessage() == f"{label}: database ready, running first round"
        ]

    @pytest.mark.parametrize("task_fn, runner_name, label, _err", _GUARDED_TASKS)
    async def test_runs_promptly_once_database_becomes_ready(
        self, task_fn, runner_name, label, _err, caplog
    ):
        ready = iter([False, False])
        runner, called = self._runner()
        with patch("app.services.background_tasks.get_settings", return_value=self.settings), \
                patch("app.services.background_tasks._database_ready",
                      side_effect=lambda: next(ready, True)), \
                patch(f"app.services.background_tasks.{runner_name}", runner), \
                caplog.at_level(logging.INFO, logger="photovault.background_tasks"):
            task = asyncio.create_task(task_fn(interval_seconds=3600))
            try:
                await asyncio.wait_for(called.wait(), timeout=2)
                await asyncio.sleep(0.1)
                assert runner.await_count == 1
            finally:
                await _cancel(task)
        assert len(self._ready_lines(caplog, label)) == 1

    @pytest.mark.parametrize("task_fn, runner_name, label, _err", _GUARDED_TASKS)
    async def test_always_ready_sleeps_normal_interval(
        self, task_fn, runner_name, label, _err, caplog
    ):
        runner, called = self._runner()
        with patch("app.services.background_tasks.get_settings", return_value=self.settings), \
                patch("app.services.background_tasks._database_ready", return_value=True), \
                patch(f"app.services.background_tasks.{runner_name}", runner), \
                caplog.at_level(logging.INFO, logger="photovault.background_tasks"):
            task = asyncio.create_task(task_fn(interval_seconds=3600))
            try:
                await asyncio.wait_for(called.wait(), timeout=2)
                await asyncio.sleep(0.2)
                # Many poll intervals have passed; only the first round ran.
                assert runner.await_count == 1
            finally:
                await _cancel(task)
        assert self._ready_lines(caplog, label) == []

    @pytest.mark.parametrize("task_fn, runner_name, label, err", _GUARDED_TASKS)
    async def test_work_exception_is_logged_and_loop_continues(
        self, task_fn, runner_name, label, err, caplog
    ):
        runner, _ = self._runner(side_effect=[RuntimeError("boom")])
        with patch("app.services.background_tasks.get_settings", return_value=self.settings), \
                patch("app.services.background_tasks._database_ready", return_value=True), \
                patch(f"app.services.background_tasks.{runner_name}", runner), \
                caplog.at_level(logging.INFO, logger="photovault.background_tasks"):
            task = asyncio.create_task(task_fn(interval_seconds=0))
            try:
                for _ in range(400):
                    if runner.await_count >= 2:
                        break
                    await asyncio.sleep(0.005)
                assert runner.await_count >= 2
            finally:
                await _cancel(task)
        assert err in caplog.text

    async def test_never_ready_never_touches_database(self, tmp_path):
        """Unprovisioned: polling must never connect to, and so create, the DB."""
        from app.services import metadata_backfill as mb

        share = tmp_path / "share"
        share.mkdir()
        settings = load_settings(
            storage_root=str(share),
            database_url=str(share / "photovault.db"),
            require_workdir_setup=True,
        )
        assert needs_provisioning(settings)

        connect = MagicMock()
        with patch("app.services.background_tasks.get_settings", return_value=settings), \
                patch("app.services.metadata_backfill.get_settings", return_value=settings), \
                patch.object(mb, "ffprobe_path", return_value=None), \
                patch("aiosqlite.connect", connect):
            tasks = [
                asyncio.create_task(fn(interval_seconds=3600))
                for fn in (
                    cleanup_expired_sessions_task,
                    cleanup_thumbnail_cache_task,
                    cleanup_expired_trash_task,
                    cleanup_expired_versions_task,
                    mb.metadata_backfill_task,
                )
            ]
            try:
                await asyncio.sleep(0.2)
                assert all(not t.done() for t in tasks)
            finally:
                await _cancel(*tasks)

        connect.assert_not_called()
        assert not Path(settings.database_path).exists()


# ---------------------------------------------------------------------------
# File version maintenance and server presence (design 5.4, section 10)
# ---------------------------------------------------------------------------


class TestVersionMaintenance:
    """_run_version_maintenance, cleanup_expired_versions and hold_server_presence."""

    @pytest.fixture(autouse=True)
    def _isolation(self, monkeypatch):
        from app.services import version_service as vs

        monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
        self.vs = vs
        yield
        for fd in vs._presence_fds.values():
            try:
                os.close(fd)
            except OSError:
                pass
        vs._presence_fds.clear()

    @pytest.fixture
    async def store(self, tmp_path):
        """Initialised database with one expired and one live version."""
        from app.core.database import init_db

        db_path = str(tmp_path / "pv.db")
        media_root = tmp_path / "media"
        media_root.mkdir()
        await init_db(db_path)
        root = Path(os.path.realpath(media_root)) / ".versions" / "1"
        root.mkdir(parents=True)
        expired = root / "20200101T000000Z_aaaaaaaa.jpg"
        live = root / "20990101T000000Z_bbbbbbbb.jpg"
        expired.write_bytes(b"old")
        live.write_bytes(b"older")
        async with aiosqlite.connect(db_path) as db:
            await db.execute("INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'x')")
            await db.execute(
                """INSERT INTO file_records (id, user_id, file_hash, file_path, original_path,
                   device_name, file_size, file_name)
                   VALUES (1, 1, 'h', ?, 'content://1', 'Pixel', 1, 'a.jpg')""",
                (str(media_root / "alice" / "a.jpg"),),
            )
            for path, expires in ((expired, "2020-01-31T00:00:00+00:00"),
                                  (live, "2099-01-31T00:00:00+00:00")):
                await db.execute(
                    """INSERT INTO file_versions (file_record_id, user_id, file_hash, file_size,
                       stored_path, replaced_at, expires_at)
                       VALUES (1, 1, ?, 3, ?, '2020-01-01T00:00:00+00:00', ?)""",
                    (path.name, str(path), expires),
                )
            await db.commit()
        return SimpleNamespace(
            db_path=db_path, media_root=str(media_root), expired=expired, live=live
        )

    async def test_one_round(self, store):
        result = await bt._run_version_maintenance(store.db_path, store.media_root)
        assert result == {"recovered": 0, "expired": 1, "orphans": 0, "temps": 0}
        assert not store.expired.exists()
        assert store.live.exists()
        async with aiosqlite.connect(store.db_path) as db:
            rows = await (await db.execute("SELECT stored_path FROM file_versions")).fetchall()
            records = await (await db.execute("SELECT COUNT(*) FROM file_records")).fetchone()
        assert [r[0] for r in rows] == [str(store.live)]
        assert records[0] == 1

    async def test_maintenance_lock_held_skips_round(self, store):
        with self.vs.try_maintenance_lock(store.media_root) as acquired:
            assert acquired
            assert await bt._run_version_maintenance(store.db_path, store.media_root) is None
        assert store.expired.exists()

    async def test_presence_lock_held_exclusively_skips_round(self, store, caplog):
        lock = Path(os.path.realpath(store.media_root)) / ".versions" / ".server-presence.lock"
        fd = os.open(lock, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with caplog.at_level(logging.WARNING, logger="photovault.background_tasks"):
                assert await bt._run_version_maintenance(store.db_path, store.media_root) is None
        finally:
            os.close(fd)
        assert "maintenance script holds .versions lock" in caplog.text
        assert store.expired.exists()

    async def test_maintenance_connection_is_synchronous_full(self, store, monkeypatch):
        """gate #2: the maintenance connection comes from _create_connection."""
        seen = {}
        original = self.vs.VersionService.recover_pending

        async def spy(service):
            cursor = await service._db.execute("PRAGMA synchronous")
            seen["synchronous"] = (await cursor.fetchone())[0]
            cursor = await service._db.execute("PRAGMA foreign_keys")
            seen["foreign_keys"] = (await cursor.fetchone())[0]
            seen["row_factory"] = service._db.row_factory
            return await original(service)

        monkeypatch.setattr(self.vs.VersionService, "recover_pending", spy)
        await bt._run_version_maintenance(store.db_path, store.media_root)
        assert seen == {"synchronous": 2, "foreign_keys": 1, "row_factory": aiosqlite.Row}

    async def test_hold_server_presence_retries_until_free(self, tmp_path, monkeypatch, caplog):
        media_root = tmp_path / "media"
        versions = media_root / ".versions"
        versions.mkdir(parents=True)
        monkeypatch.setattr(bt, "SERVER_PRESENCE_POLL_SECONDS", 0.01)
        monkeypatch.setattr(bt, "_database_ready", lambda: True)
        monkeypatch.setattr(bt, "get_settings", lambda: SimpleNamespace(media_root=str(media_root)))
        fd = os.open(versions / ".server-presence.lock", os.O_RDWR | os.O_CREAT, 0o644)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with caplog.at_level(logging.INFO, logger="photovault.background_tasks"):
            task = asyncio.create_task(bt.hold_server_presence_task())
            try:
                await asyncio.sleep(0.1)
                assert not task.done()
                assert "Server presence lock busy" in caplog.text
            finally:
                os.close(fd)
            await asyncio.wait_for(task, timeout=2)
        assert os.path.realpath(media_root) in self.vs._presence_fds
        assert "Holding server presence lock" in caplog.text

    async def test_hold_server_presence_waits_for_database(self, tmp_path, monkeypatch):
        media_root = tmp_path / "media"
        media_root.mkdir()
        monkeypatch.setattr(bt, "SERVER_PRESENCE_POLL_SECONDS", 0.01)
        monkeypatch.setattr(bt, "_database_ready", lambda: False)
        monkeypatch.setattr(bt, "get_settings", lambda: SimpleNamespace(media_root=str(media_root)))
        task = asyncio.create_task(bt.hold_server_presence_task())
        try:
            await asyncio.sleep(0.1)
            assert not task.done()
        finally:
            await _cancel(task)
        assert not (media_root / ".versions").exists()
        assert self.vs._presence_fds == {}
