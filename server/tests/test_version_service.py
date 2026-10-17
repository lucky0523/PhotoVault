"""Tests for app.services.version_service (file versioning, FEAT-001).

Integration tests on a real SQLite database and a real temporary media_root:
classification (design section 4), atomic replacement with fault injection
and compensation (5.3, 5.5, design-gate #1/#4/#5), crash recovery (5.6,
gate #3), inter-process locks and version-file deletion (5.4, 9).
"""

from __future__ import annotations

import asyncio
import errno
import fcntl
import hashlib
import logging
import os
import re
import socket
import sqlite3
import subprocess
import sys
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import aiosqlite
import pytest

from app.core.config import reset_settings
from app.core.database import init_db
from app.services import version_service as vs
from app.services.file_browse_service import FileBrowseService
from app.services.version_service import (
    OWNER,
    VERSION_RETENTION_DAYS,
    NeedsReclassify,
    ReplaceOutcome,
    VersionReplaceError,
    VersionService,
    _same_origin,
    ensure_server_presence,
    try_maintenance_lock,
)

DEVICE = "OPPO Find X9 Ultra"
ORIGIN = "content://media/external/images/media/1000042"
NAME = "IMG20260917154337.jpg"
REL_DIR = "alice/OPPO_Find_X9_Ultra/DCIM/Camera"
OLD = b"\xff\xd8old-jpeg-bytes" * 64
NEW = OLD + b"appended-vendor-trailer" * 8
THIRD = OLD + b"another-edit" * 8


class SimulatedCrash(BaseException):
    """Stands in for the process dying: no handler below the test catches it."""


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _isolation(monkeypatch):
    # app.core.logging.setup_logging (run by other tests) stops propagation.
    monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
    monkeypatch.setattr(vs, "FILE_LOCK_RETRY_SECONDS", 0.01)
    yield
    for fd in vs._presence_fds.values():
        try:
            os.close(fd)
        except OSError:
            pass
    vs._presence_fds.clear()


@pytest.fixture
async def env(tmp_path, monkeypatch):
    """Temp database (one user, alice) + media_root, like test_upload_service's db."""
    storage_root = tmp_path / "storage"
    storage_root.mkdir()
    db_path = str(tmp_path / "test.db")
    monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(storage_root))
    monkeypatch.setenv("PHOTOVAULT_DATABASE_URL", db_path)
    monkeypatch.setenv("PHOTOVAULT_JWT_SECRET_KEY", "test-secret-key-for-version-tests")
    monkeypatch.delenv("PHOTOVAULT_MEDIA_ROOT", raising=False)
    reset_settings()
    await init_db(db_path)
    conn = await aiosqlite.connect(db_path)
    await conn.execute("PRAGMA foreign_keys=ON;")
    conn.row_factory = aiosqlite.Row
    await conn.execute(
        "INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'x')"
    )
    await conn.commit()
    media_root = str(storage_root)
    try:
        yield SimpleNamespace(
            db=conn,
            db_path=db_path,
            media_root=media_root,
            svc=VersionService(conn, media_root, role="server"),
        )
    finally:
        # Let deferred recoveries finish before the loop goes away.
        if vs._background_recoveries:
            await asyncio.gather(*list(vs._background_recoveries), return_exceptions=True)
        await conn.close()
        reset_settings()


@pytest.fixture
def capture(caplog):
    caplog.set_level(logging.DEBUG, logger="photovault.versions")
    return caplog


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def fetch_record(env, record_id: int):
    cursor = await env.db.execute("SELECT * FROM file_records WHERE id = ?", (record_id,))
    return await cursor.fetchone()


async def make_record(
    env,
    content: bytes = OLD,
    *,
    name: str = NAME,
    device: str = DEVICE,
    origin: str = ORIGIN,
    rel_dir: str = REL_DIR,
):
    directory = Path(env.media_root) / rel_dir
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_bytes(content)
    cursor = await env.db.execute(
        """INSERT INTO file_records
           (user_id, file_hash, file_path, original_path, device_name, file_size,
            file_name, mime_type, media_type)
           VALUES (1, ?, ?, ?, ?, ?, ?, 'image/jpeg', 'image')""",
        (sha(content), str(path), origin, device, len(content), name),
    )
    await env.db.commit()
    return await fetch_record(env, cursor.lastrowid)


async def insert_record(env, path: str, content_hash: str, **cols):
    values = {
        "user_id": 1, "file_hash": content_hash, "file_path": path,
        "original_path": ORIGIN, "device_name": DEVICE, "file_size": 1,
        "file_name": os.path.basename(path), **cols,
    }
    names = ", ".join(values)
    marks = ", ".join("?" for _ in values)
    cursor = await env.db.execute(
        f"INSERT INTO file_records ({names}) VALUES ({marks})", tuple(values.values())
    )
    await env.db.commit()
    return cursor.lastrowid


def make_incoming(env, content: bytes = NEW, suffix: str = ".jpg") -> str:
    directory = Path(env.media_root) / ".chunks" / uuid.uuid4().hex
    directory.mkdir(parents=True)
    path = directory / f"merged-{uuid.uuid4().hex[:12]}{suffix}"
    path.write_bytes(content)
    return str(path)


def session_for(record, content: bytes = NEW, **overrides) -> dict:
    session = {
        "file_hash": sha(content),
        "device_name": record["device_name"],
        "original_path": record["original_path"],
        "file_name": record["file_name"],
    }
    session.update(overrides)
    return session


async def replace(env, record, content: bytes = NEW, *, svc=None, incoming=None, **kwargs):
    svc = svc or env.svc
    return await svc.replace_record_content(
        op="upload",
        username="alice",
        record=record,
        incoming_path=incoming or make_incoming(env, content),
        new_hash=sha(content),
        new_size=len(content),
        content_values={"mime_type": "image/jpeg"},
        media_meta=None,
        **kwargs,
    )


async def count(env, table: str) -> int:
    cursor = await env.db.execute(f"SELECT COUNT(*) FROM {table}")
    return (await cursor.fetchone())[0]


async def journal_rows(env):
    cursor = await env.db.execute("SELECT * FROM file_version_journal ORDER BY id")
    return await cursor.fetchall()


def raw_journal(env):
    """Read the journal on an independent connection (committed state only)."""
    raw = sqlite3.connect(env.db_path)
    raw.row_factory = sqlite3.Row
    try:
        return raw.execute("SELECT * FROM file_version_journal ORDER BY id").fetchall()
    finally:
        raw.close()


def version_files(env) -> list[Path]:
    root = Path(os.path.realpath(env.media_root)) / ".versions"
    if not root.exists():
        return []
    return sorted(
        p for d in root.iterdir() if d.is_dir() and d.name.isdigit() for p in d.rglob("*")
    )


def pv_temps(record) -> list[Path]:
    return sorted(Path(record["file_path"]).parent.glob(".pv-*"))


def lock_is_free(env, file_hash: str) -> bool:
    browse = FileBrowseService(env.db, env.media_root)
    if not browse.acquire_file_lock("alice", file_hash):
        return False
    browse.release_file_lock("alice", file_hash)
    return True


async def assert_untouched(env, record, old: bytes = OLD):
    """The replacement left no trace: bytes, row, tables, files, locks."""
    assert Path(record["file_path"]).read_bytes() == old
    row = await fetch_record(env, record["id"])
    for column in ("file_hash", "file_size", "file_path", "deleted_at", "purged_at"):
        assert row[column] == record[column], column
    assert await count(env, "file_versions") == 0
    assert await count(env, "file_version_journal") == 0
    assert version_files(env) == []
    assert pv_temps(record) == []
    assert not env.db.in_transaction
    assert lock_is_free(env, record["file_hash"])


def raise_at(stage: str, exc: BaseException):
    def hook(current: str) -> None:
        if current == stage:
            raise exc
    return hook


async def crash_at(env, record, stage: str, content: bytes = NEW):
    """Run a replacement that 'dies' at ``stage``: no compensation, txn lost."""
    svc = VersionService(env.db, env.media_root, role="script")
    svc._fault_hook = raise_at(stage, SimulatedCrash())

    async def die(ctx, exc):
        raise exc

    svc._handle_failure = die
    with pytest.raises(SimulatedCrash):
        await replace(env, record, content, svc=svc)
    await env.db.rollback()  # the dead process' open transaction is gone
    rows = await journal_rows(env)
    assert len(rows) == 1
    return rows[0]


def dead_pid() -> int:
    proc = subprocess.Popen([sys.executable, "-c", "pass"])
    proc.wait()
    return proc.pid


# ---------------------------------------------------------------------------
# classify_target (design section 4)
# ---------------------------------------------------------------------------


class TestClassify:
    async def test_no_record_is_legacy(self, env):
        path = str(Path(env.media_root) / REL_DIR / NAME)
        session = {"file_hash": sha(NEW), "device_name": DEVICE, "original_path": ORIGIN,
                   "file_name": NAME}
        decision = await env.svc.classify_target(1, session, path)
        assert (decision.kind, decision.record, decision.force_suffix) == ("legacy", None, False)

    async def test_multiple_active_records_is_legacy(self, env, capture):
        record = await make_record(env)
        await insert_record(env, record["file_path"], sha(b"other"))
        decision = await env.svc.classify_target(1, session_for(record), record["file_path"])
        assert decision.kind == "legacy" and not decision.force_suffix
        assert "multiple active records" in capture.text

    async def test_missing_file_forces_suffix(self, env, capture):
        record = await make_record(env)
        os.unlink(record["file_path"])
        decision = await env.svc.classify_target(1, session_for(record), record["file_path"])
        assert (decision.kind, decision.force_suffix) == ("legacy", True)
        assert "is missing; storing new content with suffix" in capture.text
        # Same content but file missing: still forced suffix (row 3 before row 5).
        decision = await env.svc.classify_target(1, session_for(record, OLD), record["file_path"])
        assert (decision.kind, decision.force_suffix) == ("legacy", True)

    async def test_directory_at_path_forces_suffix(self, env):
        record = await make_record(env)
        os.unlink(record["file_path"])
        os.mkdir(record["file_path"])
        decision = await env.svc.classify_target(1, session_for(record), record["file_path"])
        assert (decision.kind, decision.force_suffix) == ("legacy", True)

    async def test_same_hash_is_same_content(self, env):
        record = await make_record(env)
        decision = await env.svc.classify_target(1, session_for(record, OLD), record["file_path"])
        assert decision.kind == "same_content" and decision.record["id"] == record["id"]

    async def test_referenced_record_is_legacy(self, env):
        record = await make_record(env)
        await insert_record(
            env, str(Path(env.media_root) / "alice/x/ref.jpg"), record["file_hash"],
            is_reference=1, reference_to=record["id"],
        )
        decision = await env.svc.classify_target(1, session_for(record), record["file_path"])
        assert (decision.kind, decision.reason) == ("legacy", "referenced")

    async def test_new_hash_taken_at_path_is_legacy(self, env):
        record = await make_record(env)
        await insert_record(
            env, record["file_path"], sha(NEW),
            deleted_at="2026-01-01T00:00:00+00:00", purged_at="2026-01-02T00:00:00+00:00",
        )
        decision = await env.svc.classify_target(1, session_for(record), record["file_path"])
        assert (decision.kind, decision.reason) == ("legacy", "new_hash_taken")

    @pytest.mark.parametrize(
        "override",
        [
            {"device_name": "Pixel 9"},
            {"original_path": "content://media/external/images/media/7"},
            {"file_name": "other.jpg"},
        ],
    )
    async def test_different_origin_is_legacy(self, env, override):
        record = await make_record(env)
        decision = await env.svc.classify_target(
            1, session_for(record, **override), record["file_path"]
        )
        assert (decision.kind, decision.reason) == ("legacy", "origin_differs")

    async def test_same_origin_new_content_is_replace(self, env):
        record = await make_record(env)
        decision = await env.svc.classify_target(1, session_for(record), Path(record["file_path"]))
        assert decision.kind == "replace" and decision.record["id"] == record["id"]

    async def test_classify_uses_same_origin(self, env, monkeypatch):
        record = await make_record(env)
        calls = []

        def spy(*args):
            calls.append(args)
            return _same_origin(*args)

        monkeypatch.setattr(vs, "_same_origin", spy)
        await env.svc.classify_target(1, session_for(record), record["file_path"])
        assert calls == [(DEVICE, ORIGIN, DEVICE, ORIGIN)]

    async def test_query_plan_has_no_full_scan(self, env):
        cursor = await env.db.execute(
            "EXPLAIN QUERY PLAN " + vs._CANDIDATE_SQL,
            {"user_id": 1, "final_path": "/x", "new_hash": "h"},
        )
        details = [row[3] for row in await cursor.fetchall()]
        assert not [d for d in details if d.startswith("SCAN")], details
        assert any("idx_file_records_reference_to" in d for d in details), details


@pytest.mark.parametrize(
    "a_device, a_origin, b_device, b_origin, expected",
    [
        ("P", "u1", "P", "u1", True),
        ("P", "u1", "Q", "u1", False),   # device differs
        ("P", "u1", "P", "u2", False),   # origin differs
        ("P", "u1", "Q", "u2", False),   # both differ
        ("P", "", "P", "", False),       # empty origin = unknown source
        ("P", "u1", "P", "", False),     # one side empty
    ],
)
def test_same_origin(a_device, a_origin, b_device, b_origin, expected):
    assert _same_origin(a_device, a_origin, b_device, b_origin) is expected


# ---------------------------------------------------------------------------
# replace_record_content: success (acceptance 1, 2, 5)
# ---------------------------------------------------------------------------


class TestReplaceSuccess:
    async def test_replaces_in_place_and_keeps_old_version(self, env):
        """Acceptance 1, 2: same id, new bytes at the same path, one version row."""
        record = await make_record(env)
        thumbs = Path(env.media_root) / ".thumbnails" / "alice"
        thumbs.mkdir(parents=True)
        stale_thumb = thumbs / f"{record['file_hash']}_small_v2.jpg"
        stale_thumb.write_bytes(b"thumb")
        rows_before = await count(env, "file_records")

        outcome = await replace(env, record)

        assert isinstance(outcome, ReplaceOutcome)
        assert outcome.record_id == record["id"]
        assert await count(env, "file_records") == rows_before
        row = await fetch_record(env, record["id"])
        assert row["file_hash"] == sha(NEW) and row["file_size"] == len(NEW)
        assert row["file_path"] == record["file_path"]
        assert Path(record["file_path"]).read_bytes() == NEW
        assert not list(Path(record["file_path"]).parent.glob("*_1.jpg"))

        cursor = await env.db.execute("SELECT * FROM file_versions")
        versions = await cursor.fetchall()
        assert len(versions) == 1
        version = versions[0]
        assert version["id"] == outcome.version_id
        assert (version["file_record_id"], version["user_id"]) == (record["id"], 1)
        assert (version["file_hash"], version["file_size"]) == (record["file_hash"], len(OLD))
        replaced_at = datetime.fromisoformat(version["replaced_at"])
        expires_at = datetime.fromisoformat(version["expires_at"])
        assert expires_at - replaced_at == timedelta(days=VERSION_RETENTION_DAYS)
        assert re.search(
            rf"/\.versions/{record['id']}/\d{{8}}T\d{{6}}Z_{record['file_hash'][:8]}\.jpg$",
            version["stored_path"],
        )
        assert version["stored_path"] == outcome.version_path
        assert Path(version["stored_path"]).read_bytes() == OLD

        assert await count(env, "file_version_journal") == 0
        assert pv_temps(record) == []
        assert not env.db.in_transaction
        assert lock_is_free(env, record["file_hash"])
        assert not stale_thumb.exists()  # cache key is the hash (acceptance 4)

    async def test_analysis_rows_keep_pointing_to_the_record(self, env):
        """Acceptance 5: photo_gps / photo_scenes / faces reference the same id."""
        record = await make_record(env)
        rid = record["id"]
        await env.db.execute(
            "INSERT INTO photo_gps (file_id, user_id, latitude, longitude) VALUES (?, 1, 30.0, 120.0)",
            (rid,),
        )
        await env.db.execute(
            "INSERT INTO photo_scenes (file_id, user_id, scene_label, confidence) VALUES (?, 1, 'beach', 0.9)",
            (rid,),
        )
        await env.db.execute(
            "INSERT INTO faces (file_id, user_id, bbox, det_score, embedding) VALUES (?, 1, '[0,0,1,1]', 0.99, x'00')",
            (rid,),
        )
        await env.db.commit()

        await replace(env, record)

        for table in ("photo_gps", "photo_scenes", "faces"):
            cursor = await env.db.execute(f"SELECT file_id FROM {table}")
            assert [r[0] for r in await cursor.fetchall()] == [rid], table


# ---------------------------------------------------------------------------
# Fault injection and compensation (acceptance 6, design 5.5, 18.1)
# ---------------------------------------------------------------------------

FAULT_STAGES = ["after_version_file", "after_version_row", "after_swap", "before_commit"]


class TestFaultInjection:
    @pytest.mark.parametrize("stage", FAULT_STAGES)  # F1..F4
    async def test_fault_restores_previous_state(self, env, stage):
        record = await make_record(env)
        env.svc._fault_hook = raise_at(stage, RuntimeError(f"injected at {stage}"))
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert info.value.code in ("io", "db")
        await assert_untouched(env, record)

    @pytest.mark.parametrize("stage", ["after_swap", "before_commit"])  # F3, F4
    async def test_cancellation_at_fault_point_restores_and_propagates(self, env, stage):
        record = await make_record(env)
        env.svc._fault_hook = raise_at(stage, asyncio.CancelledError())
        with pytest.raises(asyncio.CancelledError):
            await replace(env, record)
        await assert_untouched(env, record)

    async def _assert_committed_but_cleanup_deferred(self, env, record):
        row = await fetch_record(env, record["id"])
        assert row["file_hash"] == sha(NEW)
        assert Path(record["file_path"]).read_bytes() == NEW
        cursor = await env.db.execute("SELECT stored_path FROM file_versions")
        stored = [r[0] for r in await cursor.fetchall()]
        assert len(stored) == 1 and Path(stored[0]).read_bytes() == OLD
        journal = await journal_rows(env)
        assert len(journal) == 1 and journal[0]["state"] == "committed"
        rollback_path = journal[0]["rollback_path"]
        assert os.path.exists(rollback_path)
        assert await env.svc.recover_for_path(record["file_path"]) == (0, [])
        assert not os.path.exists(rollback_path)
        assert await count(env, "file_version_journal") == 0
        assert Path(stored[0]).read_bytes() == OLD

    async def test_f5a_commit_cancelled_after_enqueue_is_not_compensated(self, env, monkeypatch):
        record = await make_record(env)
        real_commit = env.db.commit
        calls = {"n": 0}

        async def commit_then_cancel():
            calls["n"] += 1
            if calls["n"] == 2:  # 1st commit is the journal row (step 3)
                asyncio.ensure_future(real_commit())
                await asyncio.sleep(0)
                raise asyncio.CancelledError()
            return await real_commit()

        monkeypatch.setattr(env.db, "commit", commit_then_cancel)
        with pytest.raises(asyncio.CancelledError):
            await replace(env, record)
        monkeypatch.setattr(env.db, "commit", real_commit)
        await self._assert_committed_but_cleanup_deferred(env, record)

    async def test_f5b_cancel_while_awaiting_commit(self, env, capture):
        """Real cancellation during ``await COMMIT``: the commit still happens.

        Also the regression test for aiosqlite's semantics this module relies
        on (FIFO worker queue, queued calls run even if their future was
        cancelled). If aiosqlite changes that, this test fails.
        """
        record = await make_record(env)
        holder: dict[str, asyncio.Task] = {}

        def hook(stage):
            if stage == "before_commit":
                asyncio.get_running_loop().call_soon(holder["task"].cancel)

        env.svc._fault_hook = hook
        holder["task"] = asyncio.create_task(replace(env, record))
        with pytest.raises(asyncio.CancelledError):
            await holder["task"]
        assert "confirmed after cancellation" in capture.text
        await self._assert_committed_but_cleanup_deferred(env, record)

    async def test_f5c_commit_error_not_committed_is_compensated(self, env, monkeypatch):
        record = await make_record(env)
        real_commit = env.db.commit
        calls = {"n": 0}

        async def failing_commit():
            calls["n"] += 1
            if calls["n"] == 2:
                raise sqlite3.OperationalError("disk I/O error")
            return await real_commit()

        monkeypatch.setattr(env.db, "commit", failing_commit)
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        monkeypatch.setattr(env.db, "commit", real_commit)
        assert info.value.code == "db"
        await assert_untouched(env, record)

    async def test_f5d_commit_outcome_unknown_touches_nothing(self, env, monkeypatch, capture):
        record = await make_record(env)
        svc = VersionService(env.db, env.media_root, role="script")
        real_commit = env.db.commit
        calls = {"n": 0}

        async def failing_commit():
            calls["n"] += 1
            if calls["n"] == 2:
                raise sqlite3.OperationalError("disk I/O error")
            return await real_commit()

        async def failing_probe(jid):
            raise sqlite3.OperationalError("connection broken")

        monkeypatch.setattr(env.db, "commit", failing_commit)
        monkeypatch.setattr(svc, "_probe_commit", failing_probe)
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record, svc=svc)
        assert info.value.code == "db"
        assert "MANUAL RECOVERY NEEDED commit outcome unknown" in capture.text
        journal = raw_journal(env)
        assert len(journal) == 1 and journal[0]["state"] == "prepared"
        assert Path(record["file_path"]).read_bytes() == NEW  # switched, not undone
        assert os.path.exists(journal[0]["rollback_path"])
        assert os.path.exists(journal[0]["version_path"])
        assert vs._background_recoveries == set()  # role=script schedules nothing

        monkeypatch.setattr(env.db, "commit", real_commit)
        await env.db.rollback()  # the "repaired" connection
        assert await svc.recover_for_path(record["file_path"]) == (0, [])
        await assert_untouched(env, record)

    async def test_compensation_never_deletes_committed_journal(self, env):
        record = await make_record(env)
        await crash_at(env, record, "after_version_row")
        await env.db.execute("UPDATE file_version_journal SET state = 'committed'")
        await env.db.commit()
        row = (await journal_rows(env))[0]
        assert await env.svc._delete_journal_row(row["id"], "prepared") is True
        assert await count(env, "file_version_journal") == 1

    async def test_compensation_keeps_version_file_referenced_by_committed_row(
        self, env, monkeypatch, capture
    ):
        record = await make_record(env)
        env.svc._fault_hook = raise_at("after_version_row", RuntimeError("boom"))
        real_probe = env.svc._db_rollback_and_probe

        async def referenced(version_path):
            await real_probe(version_path)
            return True

        monkeypatch.setattr(env.svc, "_db_rollback_and_probe", referenced)
        with pytest.raises(VersionReplaceError):
            await replace(env, record)
        assert "is referenced by a committed row; keeping it" in capture.text
        kept = version_files(env)
        assert len(kept) == 1 and kept[0].read_bytes() == OLD
        assert pv_temps(record) == []
        assert await count(env, "file_version_journal") == 0

    async def test_cancel_during_journal_commit(self, env, monkeypatch):
        """gate #1: cancellation on step 3's commit leaves no row and no file."""
        record = await make_record(env)
        real_commit = env.db.commit
        holder: dict[str, asyncio.Task] = {}
        calls = {"n": 0}

        async def commit_with_cancel():
            calls["n"] += 1
            if calls["n"] == 1:
                asyncio.get_running_loop().call_soon(holder["task"].cancel)
            return await real_commit()

        monkeypatch.setattr(env.db, "commit", commit_with_cancel)
        holder["task"] = asyncio.create_task(replace(env, record))
        with pytest.raises(asyncio.CancelledError):
            await holder["task"]
        monkeypatch.setattr(env.db, "commit", real_commit)
        assert calls["n"] >= 2  # the abort committed its DELETE
        assert raw_journal(env) == []
        await assert_untouched(env, record)

    @pytest.mark.parametrize("target", ["version", "rollback", "staging"])  # 4a, 4b, 5
    async def test_cancel_while_copying_waits_for_the_thread(self, env, monkeypatch, target):
        record = await make_record(env)

        def no_links(src, dst, *args, **kwargs):
            raise OSError(errno.EPERM, "hard links not supported")

        monkeypatch.setattr(os, "link", no_links)
        entered, release = threading.Event(), threading.Event()
        real_copy = vs._copy_fsync
        marker = {"version": f"{os.sep}.versions{os.sep}", "rollback": ".pv-rollback-",
                  "staging": ".pv-staging-"}[target]

        def blocking_copy(src, dst):
            if marker in dst:
                Path(dst + ".part").write_bytes(b"partial")
                entered.set()
                release.wait(10)
            return real_copy(src, dst)

        monkeypatch.setattr(vs, "_copy_fsync", blocking_copy)
        task = asyncio.create_task(replace(env, record))
        assert await asyncio.to_thread(entered.wait, 10)
        task.cancel()
        await asyncio.sleep(0.05)
        assert not task.done()  # the cancellation waits for the thread
        assert await count(env, "file_version_journal") == 1
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        await assert_untouched(env, record)

    async def test_crash_before_cleanup_stage_then_recovery(self, env, monkeypatch):
        record = await make_record(env)
        svc = VersionService(env.db, env.media_root, role="script")
        svc._fault_hook = raise_at("after_version_row", RuntimeError("boom"))

        async def die(*args, **kwargs):
            raise SimulatedCrash()

        monkeypatch.setattr(svc, "_discard_temps_and_journal", die)
        with pytest.raises(SimulatedCrash):
            await replace(env, record, svc=svc)
        journal = await journal_rows(env)
        assert len(journal) == 1 and journal[0]["state"] == "prepared"
        assert os.path.exists(journal[0]["rollback_path"])
        assert os.path.exists(journal[0]["version_path"])

        # "Restart": the row now belongs to a dead process on this host.
        owner = f"{socket.gethostname()}:{dead_pid()}:0123456789ab"
        await env.db.execute("UPDATE file_version_journal SET owner = ?", (owner,))
        await env.db.commit()
        fresh = VersionService(env.db, env.media_root, role="server")
        assert await fresh.recover_for_path(record["file_path"]) == (0, [])
        await assert_untouched(env, record)

    async def test_crash_before_journal_delete_then_recovery(self, env, monkeypatch):
        record = await make_record(env)
        svc = VersionService(env.db, env.media_root, role="script")
        svc._fault_hook = raise_at("after_version_row", RuntimeError("boom"))

        async def die(*args, **kwargs):
            raise SimulatedCrash()

        monkeypatch.setattr(svc, "_delete_journal_row", die)
        with pytest.raises(SimulatedCrash):
            await replace(env, record, svc=svc)
        journal = await journal_rows(env)
        assert len(journal) == 1 and journal[0]["state"] == "prepared"
        assert pv_temps(record) == [] and version_files(env) == []
        assert await env.svc.recover_for_path(record["file_path"]) == (0, [])
        await assert_untouched(env, record)

    async def test_stage1_failure_keeps_journal_and_schedules_recovery(
        self, env, monkeypatch, capture
    ):
        """gate #5: compensation stage 1 fails -> MANUAL log + schedule_path_recovery."""
        record = await make_record(env)
        env.svc._fault_hook = raise_at("after_swap", RuntimeError("boom"))
        scheduled = []
        monkeypatch.setattr(vs, "schedule_path_recovery", lambda fp, mr: scheduled.append((fp, mr)))
        real_replace = os.replace

        def failing_rollback(src, dst, *args, **kwargs):
            if ".pv-rollback-" in str(src):
                raise OSError(errno.EIO, "rename failed")
            return real_replace(src, dst, *args, **kwargs)

        monkeypatch.setattr(os, "replace", failing_rollback)
        with pytest.raises(VersionReplaceError):
            await replace(env, record)
        assert "MANUAL RECOVERY NEEDED" in capture.text
        assert not env.db.in_transaction
        journal = await journal_rows(env)
        assert len(journal) == 1 and journal[0]["state"] == "prepared"
        assert scheduled == [(record["file_path"], env.media_root)]
        assert Path(record["file_path"]).read_bytes() == NEW

        monkeypatch.setattr(os, "replace", real_replace)
        assert await env.svc.recover_for_path(record["file_path"]) == (0, [])
        await assert_untouched(env, record)

    async def test_lost_claim_returns_conflict(self, env):
        record = await make_record(env)

        def claim_by_recovery(stage):
            if stage == "after_version_file":
                raw = sqlite3.connect(env.db_path)
                raw.execute(
                    "UPDATE file_version_journal SET state = 'recovering', owner = 'otherhost:1:abcdefabcdef'"
                )
                raw.commit()
                raw.close()

        env.svc._fault_hook = claim_by_recovery
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert (info.value.code, info.value.reclassify) == ("conflict", False)
        assert await count(env, "file_versions") == 0
        assert Path(record["file_path"]).read_bytes() == OLD
        assert pv_temps(record) == [] and version_files(env) == []
        journal = await journal_rows(env)
        assert len(journal) == 1 and journal[0]["state"] == "recovering"

# ---------------------------------------------------------------------------
# Concurrency, link fallbacks, durability, guards
# ---------------------------------------------------------------------------


class TestReplaceGuards:
    async def test_two_connections_race_on_one_record(self, env):
        """H2 and H3 on separate connections/instances without a shared path_lock."""
        record = await make_record(env)
        db2 = await aiosqlite.connect(env.db_path)
        await db2.execute("PRAGMA foreign_keys=ON;")
        db2.row_factory = aiosqlite.Row
        try:
            svc1 = VersionService(env.db, env.media_root, role="server")
            svc2 = VersionService(db2, env.media_root, role="server")
            results = await asyncio.gather(
                replace(env, record, NEW, svc=svc1),
                replace(env, record, THIRD, svc=svc2),
                return_exceptions=True,
            )
        finally:
            await db2.close()
        wins = [i for i, r in enumerate(results) if isinstance(r, ReplaceOutcome)]
        assert len(wins) == 1, results
        loser = results[1 - wins[0]]
        assert isinstance(loser, NeedsReclassify) or (
            isinstance(loser, VersionReplaceError) and loser.code in ("busy", "conflict")
        ), loser
        winner_content = (NEW, THIRD)[wins[0]]
        assert Path(record["file_path"]).read_bytes() == winner_content
        assert (await fetch_record(env, record["id"]))["file_hash"] == sha(winner_content)
        cursor = await env.db.execute("SELECT stored_path FROM file_versions")
        stored = [r[0] for r in await cursor.fetchall()]
        assert len(stored) == 1 and Path(stored[0]).read_bytes() == OLD
        assert version_files(env) == [Path(stored[0])]
        assert pv_temps(record) == []
        assert await count(env, "file_version_journal") == 0

    @staticmethod
    def _exdev_for_versions(monkeypatch):
        real_link = os.link

        def link(src, dst, *args, **kwargs):
            if f"{os.sep}.versions{os.sep}" in str(dst):
                raise OSError(errno.EXDEV, "cross-device link")
            return real_link(src, dst, *args, **kwargs)

        monkeypatch.setattr(os, "link", link)

    async def test_cross_volume_version_file_is_copied(self, env, monkeypatch):
        record = await make_record(env)
        self._exdev_for_versions(monkeypatch)
        outcome = await replace(env, record)
        assert Path(outcome.version_path).read_bytes() == OLD
        assert Path(record["file_path"]).read_bytes() == NEW
        assert not Path(outcome.version_path + ".part").exists()
        assert pv_temps(record) == []

    @pytest.mark.parametrize("stage", ["after_swap", "before_commit"])
    async def test_cross_volume_rollback_needs_no_manual_step(self, env, monkeypatch, capture, stage):
        record = await make_record(env)
        self._exdev_for_versions(monkeypatch)
        env.svc._fault_hook = raise_at(stage, RuntimeError("boom"))
        with pytest.raises(VersionReplaceError):
            await replace(env, record)
        assert "MANUAL RECOVERY NEEDED" not in capture.text
        await assert_untouched(env, record)

    async def test_no_hard_links_at_all(self, env, monkeypatch):
        record = await make_record(env)

        def no_links(src, dst, *args, **kwargs):
            raise OSError(errno.EPERM, "operation not permitted")

        monkeypatch.setattr(os, "link", no_links)
        env.svc._fault_hook = raise_at("after_swap", RuntimeError("boom"))
        with pytest.raises(VersionReplaceError):
            await replace(env, record)
        await assert_untouched(env, record)
        env.svc._fault_hook = lambda stage: None
        outcome = await replace(env, record)
        assert Path(outcome.version_path).read_bytes() == OLD
        assert Path(record["file_path"]).read_bytes() == NEW

    async def test_staging_is_fsynced_before_the_switch(self, env, monkeypatch):
        record = await make_record(env)
        events: list[tuple[str, str]] = []
        real_fsync, real_replace = vs._fsync_file, os.replace

        def fsync(path):
            events.append(("fsync", path))
            return real_fsync(path)

        def rename(src, dst, *args, **kwargs):
            events.append(("replace", str(src)))
            return real_replace(src, dst, *args, **kwargs)

        monkeypatch.setattr(vs, "_fsync_file", fsync)
        monkeypatch.setattr(os, "replace", rename)
        await replace(env, record)
        staging = [p for kind, p in events if kind == "fsync" and ".pv-staging-" in p]
        assert len(staging) == 1
        assert events.index(("fsync", staging[0])) < events.index(("replace", staging[0]))

    async def test_staging_fsync_failure_is_io(self, env, monkeypatch):
        record = await make_record(env)
        real_fsync = vs._fsync_file

        def fsync(path):
            if ".pv-staging-" in path:
                raise OSError(errno.EIO, "fsync failed")
            return real_fsync(path)

        monkeypatch.setattr(vs, "_fsync_file", fsync)
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert info.value.code == "io"
        await assert_untouched(env, record)

    async def test_replaced_at_is_frozen_at_step_3(self, env, monkeypatch):
        record = await make_record(env)
        clock = [datetime(2026, 10, 17, 15, 30, 12, tzinfo=timezone.utc)]
        monkeypatch.setattr(vs, "_utc_now", lambda: clock[0])
        seen = {}

        def hook(stage):
            if stage == "after_version_file":
                clock[0] += timedelta(seconds=5)
            if stage == "before_commit":
                seen["journal"] = raw_journal(env)[0]["replaced_at"]

        env.svc._fault_hook = hook
        outcome = await replace(env, record)
        assert Path(outcome.version_path).name.startswith("20261017T153012Z_")
        cursor = await env.db.execute("SELECT replaced_at, expires_at FROM file_versions")
        version = await cursor.fetchone()
        assert version["replaced_at"] == seen["journal"] == "2026-10-17T15:30:12+00:00"
        assert version["expires_at"] == "2026-11-16T15:30:12+00:00"

    async def test_requires_connection_without_pending_writes(self, env):
        record = await make_record(env)
        await env.db.execute("UPDATE users SET is_admin = 1 WHERE id = 1")
        with pytest.raises(RuntimeError):
            await replace(env, record)
        raw = sqlite3.connect(env.db_path)
        assert raw.execute("SELECT is_admin FROM users WHERE id = 1").fetchone()[0] == 0
        raw.close()
        await env.db.rollback()
        await assert_untouched(env, record)

    async def test_busy_when_file_lock_is_held(self, env):
        record = await make_record(env)
        holder = FileBrowseService(env.db, env.media_root)
        assert holder.acquire_file_lock("alice", record["file_hash"])
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert info.value.code == "busy"
        holder.release_file_lock("alice", record["file_hash"])
        await assert_untouched(env, record)

    async def test_busy_when_presence_lock_is_held_exclusively(self, env):
        record = await make_record(env)
        versions = Path(os.path.realpath(env.media_root)) / ".versions"
        versions.mkdir()
        fd = os.open(versions / ".server-presence.lock", os.O_RDWR | os.O_CREAT, 0o644)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)  # the repair script
            with pytest.raises(VersionReplaceError) as info:
                await replace(env, record)
            assert info.value.code == "busy"
            assert ensure_server_presence(env.media_root) is False
        finally:
            os.close(fd)
        await assert_untouched(env, record)
        assert ensure_server_presence(env.media_root) is True

    async def test_post_lock_cas_record_trashed(self, env):
        record = await make_record(env)
        await env.db.execute(
            "UPDATE file_records SET deleted_at = '2026-10-17T00:00:00+00:00' WHERE id = ?",
            (record["id"],),
        )
        await env.db.commit()
        with pytest.raises(NeedsReclassify):
            await replace(env, record)
        assert await count(env, "file_version_journal") == 0
        assert version_files(env) == [] and pv_temps(record) == []

    async def test_post_lock_cas_file_removed(self, env):
        record = await make_record(env)
        os.unlink(record["file_path"])
        with pytest.raises(NeedsReclassify):
            await replace(env, record)
        assert not os.path.exists(record["file_path"])
        assert await count(env, "file_version_journal") == 0
        assert version_files(env) == []

    @pytest.mark.parametrize("change", ["new_hash_taken", "referenced"])
    async def test_post_lock_cas_other_changes(self, env, change):
        record = await make_record(env)
        if change == "new_hash_taken":
            await insert_record(
                env, record["file_path"], sha(NEW),
                deleted_at="2026-01-01T00:00:00+00:00", purged_at="2026-01-02T00:00:00+00:00",
            )
        else:
            await insert_record(
                env, str(Path(env.media_root) / "alice/x/ref.jpg"), record["file_hash"],
                is_reference=1, reference_to=record["id"],
            )
        with pytest.raises(NeedsReclassify):
            await replace(env, record)
        await assert_untouched(env, record)

    async def test_in_transaction_cas_new_hash_taken(self, env):
        record = await make_record(env)

        def insert_conflicting_record(stage):
            if stage == "after_version_file":
                raw = sqlite3.connect(env.db_path)
                raw.execute(
                    """INSERT INTO file_records (user_id, file_hash, file_path, original_path,
                       device_name, file_size, file_name, deleted_at, purged_at)
                       VALUES (1, ?, ?, ?, ?, 1, ?, '2026-01-01', '2026-01-02')""",
                    (sha(NEW), record["file_path"], ORIGIN, DEVICE, NAME),
                )
                raw.commit()
                raw.close()

        env.svc._fault_hook = insert_conflicting_record
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert (info.value.code, info.value.reclassify) == ("conflict", True)
        await assert_untouched(env, record)

    async def test_malformed_hash_is_io(self, env):
        record = await make_record(env)
        await env.db.execute(
            "UPDATE file_records SET file_hash = '../../x/abcdef' WHERE id = ?", (record["id"],)
        )
        await env.db.commit()
        record = await fetch_record(env, record["id"])
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert (info.value.code, info.value.retryable) == ("io", False)
        assert await count(env, "file_version_journal") == 0
        assert version_files(env) == []

    async def test_existing_version_file_name_is_io(self, env, monkeypatch):
        record = await make_record(env)
        moment = datetime(2026, 10, 17, 15, 30, 12, tzinfo=timezone.utc)
        monkeypatch.setattr(vs, "_utc_now", lambda: moment)
        existing = (
            Path(os.path.realpath(env.media_root)) / ".versions" / str(record["id"])
            / f"20261017T153012Z_{record['file_hash'][:8]}.jpg"
        )
        existing.parent.mkdir(parents=True)
        existing.write_bytes(b"earlier version")
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert info.value.code == "io"
        assert existing.read_bytes() == b"earlier version"
        assert Path(record["file_path"]).read_bytes() == OLD
        assert await count(env, "file_version_journal") == 0
        assert await count(env, "file_versions") == 0
        assert pv_temps(record) == []

    async def test_pre_switch_inode_check(self, env):
        """final_path moved away by another worker: no switch, nothing 'filled in'."""
        record = await make_record(env)
        moved = record["file_path"] + ".moved"

        def move_away(stage):
            if stage == "after_version_row":
                os.rename(record["file_path"], moved)

        env.svc._fault_hook = move_away
        with pytest.raises(VersionReplaceError) as info:
            await replace(env, record)
        assert (info.value.code, info.value.reclassify) == ("conflict", True)
        assert not os.path.exists(record["file_path"])
        assert Path(moved).read_bytes() == OLD
        row = await fetch_record(env, record["id"])
        assert row["file_hash"] == record["file_hash"]
        assert await count(env, "file_versions") == 0
        assert await count(env, "file_version_journal") == 0
        assert version_files(env) == [] and pv_temps(record) == []

# ---------------------------------------------------------------------------
# Crash recovery (design 5.6, gate #3)
# ---------------------------------------------------------------------------


async def insert_journal(env, record, *, owner: str, created_at: str | None = None,
                         state: str = "prepared") -> int:
    """A journal row whose temp files do not exist (nothing was switched)."""
    final = record["file_path"]
    parent = os.path.dirname(final)
    created = created_at or vs._iso(vs._utc_now())
    version_path = os.path.join(
        os.path.realpath(env.media_root), ".versions", str(record["id"]),
        f"20261017T153012Z_{record['file_hash'][:8]}.jpg",
    )
    cursor = await env.db.execute(
        """INSERT INTO file_version_journal
           (op, state, owner, user_id, file_record_id, old_hash, new_hash, final_path,
            version_path, rollback_path, staging_path, replaced_at, created_at)
           VALUES ('upload', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (state, owner, record["id"], record["file_hash"], sha(NEW), final, version_path,
         os.path.join(parent, ".pv-rollback-aaaaaaaaaaaa.jpg"),
         os.path.join(parent, ".pv-staging-bbbbbbbbbbbb.jpg"), created, created),
    )
    await env.db.commit()
    return cursor.lastrowid


class TestRecovery:
    async def test_a_not_switched(self, env):
        record = await make_record(env)
        row = await crash_at(env, record, "after_version_row")
        assert os.path.exists(row["rollback_path"]) and os.path.exists(row["version_path"])
        assert await env.svc.recover_pending() == 1
        await assert_untouched(env, record)

    async def test_b_switched_rolls_back_from_rollback_path(self, env):
        record = await make_record(env)
        row = await crash_at(env, record, "after_swap")
        assert Path(record["file_path"]).read_bytes() == NEW
        thumbs = Path(env.media_root) / ".thumbnails" / "alice"
        thumbs.mkdir(parents=True)
        polluted = thumbs / f"{record['file_hash']}_medium_v2.jpg"
        polluted.write_bytes(b"made from new bytes")
        assert await env.svc.recover_pending() == 1
        await assert_untouched(env, record)
        assert not polluted.exists()

    async def test_b_switched_without_rollback_path_goes_manual(self, env, capture):
        """gate #3: rollback_path is the only rollback source; version_path is reported."""
        record = await make_record(env)
        row = await crash_at(env, record, "after_swap")
        os.unlink(row["rollback_path"])
        assert await env.svc.recover_pending() == 0
        assert Path(record["file_path"]).read_bytes() == NEW  # not touched
        journal = await journal_rows(env)
        assert [r["state"] for r in journal] == ["manual"]
        errors = [r.getMessage() for r in capture.records if r.levelno == logging.ERROR]
        assert any(
            "MANUAL RECOVERY NEEDED" in m and "verified (sha256 matches old_hash)" in m
            for m in errors
        ), errors
        assert Path(row["version_path"]).read_bytes() == OLD
        # manual rows no longer block the path and are summarised as WARNING
        assert await env.svc.recover_for_path(record["file_path"]) == (0, [])
        capture.clear()
        await env.svc.recover_pending()
        assert "need manual recovery" in capture.text

    async def test_c_committed_only_cleans_up(self, env, monkeypatch):
        record = await make_record(env)
        svc = VersionService(env.db, env.media_root, role="script")
        real_discard = svc._discard_temps_and_journal

        async def die_after_commit(row, expect_state, keep_version):
            if expect_state == "committed":
                raise SimulatedCrash()
            return await real_discard(row, expect_state, keep_version)

        monkeypatch.setattr(svc, "_discard_temps_and_journal", die_after_commit)
        with pytest.raises(SimulatedCrash):
            await replace(env, record, svc=svc)
        cleanup = Path(record["file_path"]).with_name("IMG20260917154337_1.jpg")
        cleanup.write_bytes(NEW)
        await env.db.execute("UPDATE file_version_journal SET cleanup_path = ?", (str(cleanup),))
        await env.db.commit()
        row = (await journal_rows(env))[0]
        assert row["state"] == "committed" and os.path.exists(row["rollback_path"])

        assert await env.svc.recover_pending() == 1
        assert not os.path.exists(row["rollback_path"])
        assert not cleanup.exists()
        assert Path(row["version_path"]).read_bytes() == OLD
        assert Path(record["file_path"]).read_bytes() == NEW
        assert await count(env, "file_version_journal") == 0
        assert await count(env, "file_versions") == 1

    async def test_d_version_referenced_goes_manual(self, env):
        record = await make_record(env)
        row = await crash_at(env, record, "after_version_row")
        await env.db.execute(
            """INSERT INTO file_versions (file_record_id, user_id, file_hash, file_size,
               stored_path, replaced_at, expires_at) VALUES (?, 1, ?, 1, ?, 'x', 'y')""",
            (record["id"], record["file_hash"], row["version_path"]),
        )
        await env.db.commit()
        await env.svc.recover_pending()
        assert [r["state"] for r in await journal_rows(env)] == ["manual"]
        assert os.path.exists(row["version_path"])

    async def test_e_record_changed_goes_manual_without_file_ops(self, env, capture):
        record = await make_record(env)
        row = await crash_at(env, record, "after_version_row")
        await env.db.execute(
            "UPDATE file_records SET deleted_at = '2026-10-17T00:00:00+00:00' WHERE id = ?",
            (record["id"],),
        )
        await env.db.commit()
        assert await env.svc.recover_for_path(record["file_path"]) == (0, [])
        assert [r["state"] for r in await journal_rows(env)] == ["manual"]
        for key in ("rollback_path", "staging_path", "version_path"):
            assert os.path.exists(row[key]), key
        assert "MANUAL RECOVERY NEEDED" in capture.text

    async def test_f_failed_rename_retried_next_round(self, env, monkeypatch, capture):
        record = await make_record(env)
        await crash_at(env, record, "after_swap")
        real_replace = os.replace
        failures = {"n": 0}

        def flaky(src, dst, *args, **kwargs):
            if str(dst) == record["file_path"] and failures["n"] == 0:
                failures["n"] += 1
                raise OSError(errno.EIO, "rename failed")
            return real_replace(src, dst, *args, **kwargs)

        monkeypatch.setattr(os, "replace", flaky)
        assert await env.svc.recover_pending() == 0
        assert [r["state"] for r in await journal_rows(env)] == ["recovering"]
        assert "will retry" in capture.text
        assert await env.svc.recover_pending() == 1
        await assert_untouched(env, record)

    async def test_reads_records_before_touching_files(self, env, monkeypatch):
        record = await make_record(env)
        await crash_at(env, record, "after_swap")
        events: list[str] = []
        real_execute = env.db.execute
        real_unlink, real_replace = os.unlink, os.replace

        async def execute(sql, *args, **kwargs):
            if "FROM file_records" in sql:
                events.append("read_record")
            return await real_execute(sql, *args, **kwargs)

        def unlink(path, *args, **kwargs):
            events.append("unlink")
            return real_unlink(path, *args, **kwargs)

        def rename(src, dst, *args, **kwargs):
            events.append("replace")
            return real_replace(src, dst, *args, **kwargs)

        monkeypatch.setattr(env.db, "execute", execute)
        monkeypatch.setattr(os, "unlink", unlink)
        monkeypatch.setattr(os, "replace", rename)
        assert await env.svc.recover_pending() == 1
        first_file_op = min(events.index(e) for e in ("unlink", "replace") if e in events)
        assert events.index("read_record") < first_file_op

    async def test_hashes_are_computed_in_threads(self, env, monkeypatch):
        record = await make_record(env)
        await crash_at(env, record, "after_swap")
        seen = []
        real_to_thread = asyncio.to_thread

        def to_thread(fn, *args, **kwargs):
            seen.append(fn)
            return real_to_thread(fn, *args, **kwargs)

        monkeypatch.setattr(asyncio, "to_thread", to_thread)
        assert await env.svc.recover_pending() == 1
        assert seen.count(vs._sha256_file) == 2  # final_path and rollback_path

    @pytest.mark.parametrize(
        "owner_kind, processed",
        [
            ("self", True),
            ("same_pid_other_token", True),
            ("live_pid_in_lease", False),
            ("live_pid_lease_expired", True),
            ("other_host_in_lease", False),
        ],
    )
    async def test_owner_and_lease(self, env, capture, owner_kind, processed):
        record = await make_record(env)
        host = socket.gethostname()
        created = None
        owner = {
            "self": OWNER,
            "same_pid_other_token": f"{host}:{os.getpid()}:deadbeef0000",
            "live_pid_in_lease": f"{host}:{os.getppid()}:deadbeef0000",
            "live_pid_lease_expired": f"{host}:{os.getppid()}:deadbeef0000",
            "other_host_in_lease": f"not-{host}:{os.getpid()}:deadbeef0000",
        }[owner_kind]
        if owner_kind == "live_pid_lease_expired":
            created = vs._iso(vs._utc_now() - timedelta(minutes=31))
        jid = await insert_journal(env, record, owner=owner, created_at=created)
        remaining = await env.svc.recover_for_path(record["file_path"])
        if processed:
            assert remaining == (0, [])
            assert await count(env, "file_version_journal") == 0
        else:
            assert remaining == (1, [jid])
            row = (await journal_rows(env))[0]
            assert (row["state"], row["owner"]) == ("prepared", owner)
        if owner_kind == "live_pid_lease_expired":
            assert "assuming pid reuse" in capture.text
        assert Path(record["file_path"]).read_bytes() == OLD

    async def test_commit_unknown_is_recovered_in_the_background(self, env, monkeypatch):
        record = await make_record(env)
        real_commit = env.db.commit
        calls = {"n": 0}

        async def failing_commit():
            calls["n"] += 1
            if calls["n"] == 2:
                raise sqlite3.OperationalError("disk I/O error")
            return await real_commit()

        async def broken_probe(jid):
            await env.db.rollback()  # releases the write lock, then "breaks"
            raise sqlite3.OperationalError("connection broken")

        monkeypatch.setattr(env.db, "commit", failing_commit)
        monkeypatch.setattr(env.svc, "_probe_commit", broken_probe)
        with pytest.raises(VersionReplaceError):
            await replace(env, record)
        monkeypatch.setattr(env.db, "commit", real_commit)
        assert len(vs._background_recoveries) == 1
        await asyncio.gather(*list(vs._background_recoveries))
        assert vs._background_recoveries == set()
        await assert_untouched(env, record)

    async def test_background_recovery_connection_failure_only_warns(
        self, env, monkeypatch, capture
    ):
        record = await make_record(env)
        await crash_at(env, record, "after_swap")

        async def no_connection(url):
            raise sqlite3.OperationalError("unable to open database file")

        monkeypatch.setattr(vs, "_create_connection", no_connection)
        task = vs.schedule_path_recovery(record["file_path"], env.media_root)
        await task
        assert "deferred recovery for" in capture.text and "failed" in capture.text
        assert await count(env, "file_version_journal") == 1

    async def test_background_recovery_connection_is_synchronous_full(self, env, monkeypatch):
        seen = []

        async def probe(self, final_path):
            cursor = await self._db.execute("PRAGMA synchronous")
            seen.append((await cursor.fetchone())[0])
            return 0, []

        monkeypatch.setattr(VersionService, "recover_for_path", probe)
        await vs.schedule_path_recovery(str(Path(env.media_root) / "x.jpg"), env.media_root)
        assert seen == [2]


# ---------------------------------------------------------------------------
# Locks in .versions/ and version file deletion (design 5.4, 9)
# ---------------------------------------------------------------------------


class TestLocksAndDeletion:
    async def test_maintenance_lock_is_exclusive_and_kept_on_disk(self, env):
        with try_maintenance_lock(env.media_root) as first:
            assert first is True
            with try_maintenance_lock(env.media_root) as second:
                assert second is False
        with try_maintenance_lock(env.media_root) as again:
            assert again is True
        lock = Path(os.path.realpath(env.media_root)) / ".versions" / ".maintenance.lock"
        assert lock.exists()

    async def test_delete_version_files_only_removes_its_own_directory(self, env, capture):
        root = Path(os.path.realpath(env.media_root)) / ".versions"
        record_dir = root / "12"
        record_dir.mkdir(parents=True)
        version = record_dir / "20261017T153012Z_abcdef01.jpg"
        version.write_bytes(b"v")
        lock = root / ".maintenance.lock"
        lock.write_bytes(b"")
        outside = Path(env.media_root) / "alice" / "photo.jpg"
        outside.parent.mkdir(parents=True)
        outside.write_bytes(b"photo")

        deleted = VersionService.delete_version_files(
            env.media_root,
            [str(version), str(record_dir / "missing.jpg"), str(outside), str(lock)],
        )

        assert deleted == 1
        assert not record_dir.exists()
        assert root.is_dir() and lock.exists()
        assert outside.read_bytes() == b"photo"
        errors = [r.getMessage() for r in capture.records if r.levelno == logging.ERROR]
        assert sum("refusing to delete version file" in m for m in errors) == 2

    async def test_unlink_version_file_results(self, env):
        root = Path(os.path.realpath(env.media_root)) / ".versions" / "3"
        root.mkdir(parents=True)
        target = root / "20261017T153012Z_abcdef01.jpg"
        target.write_bytes(b"v")
        assert VersionService._unlink_version_file(env.media_root, str(target)) == "deleted"
        assert VersionService._unlink_version_file(env.media_root, str(target)) == "missing"
        assert (
            VersionService._unlink_version_file(env.media_root, str(Path(env.media_root) / "x"))
            == "out_of_bounds"
        )
