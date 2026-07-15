"""Unit tests for the analysis queue and its background worker (task 3.2).

Covers:
- the process-wide queue singleton and lazy creation;
- ``enqueue_analysis`` normalizing to ``(user_id, file_id)`` and never raising;
- ``enqueue_reanalysis_all`` querying only the user's own live, non-reference
  files (user isolation, skips references/deleted);
- ``analysis_worker_task`` draining the queue, isolating per-file failures, and
  handling cancellation.
"""

import asyncio
import types

import aiosqlite
import pytest

from app.core.database import init_db
from app.services import analysis_queue
from app.services.analysis_queue import (
    enqueue_analysis,
    enqueue_reanalysis_all,
    get_analysis_queue,
    reset_analysis_queue,
)
from app.services.background_tasks import analysis_worker_task


@pytest.fixture(autouse=True)
def _fresh_queue():
    """Give each test an isolated queue singleton."""
    reset_analysis_queue()
    yield
    reset_analysis_queue()


def test_get_analysis_queue_is_singleton():
    q1 = get_analysis_queue()
    q2 = get_analysis_queue()
    assert q1 is q2


def test_enqueue_analysis_normalizes_order():
    """Called as (file_id, user_id) but stored as (user_id, file_id)."""
    assert enqueue_analysis(file_id=10, user_id=1) is True
    queue = get_analysis_queue()
    assert queue.qsize() == 1
    assert queue.get_nowait() == (1, 10)


def test_enqueue_analysis_never_raises(monkeypatch):
    """A broken queue must not propagate errors to the caller."""

    class _Boom:
        def put_nowait(self, item):
            raise RuntimeError("queue exploded")

    monkeypatch.setattr(analysis_queue, "_queue", _Boom())
    # Should swallow the error and report failure rather than raise.
    assert enqueue_analysis(file_id=5, user_id=2) is False


async def _setup_db(db_url: str) -> None:
    await init_db(db_url)
    db = await aiosqlite.connect(db_url)
    try:
        await db.execute("PRAGMA foreign_keys=ON;")
        await db.execute(
            "INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'h')"
        )
        await db.execute(
            "INSERT INTO users (id, username, password_hash) VALUES (2, 'bob', 'h')"
        )
        # Two live originals for user 1.
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name)
               VALUES (10, 1, 'h1', '/p/a.jpg', '/o', 'dev', 1, 'a.jpg')"""
        )
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name)
               VALUES (11, 1, 'h2', '/p/b.jpg', '/o', 'dev', 1, 'b.jpg')"""
        )
        # A reference record for user 1 (should be skipped).
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name, is_reference, reference_to)
               VALUES (12, 1, 'h1', '/p/ref.jpg', '/o', 'dev', 1, 'a.jpg', 1, 10)"""
        )
        # A deleted record for user 1 (should be skipped).
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name, deleted_at)
               VALUES (13, 1, 'h3', '/p/c.jpg', '/o', 'dev', 1, 'c.jpg', '2024-01-01')"""
        )
        # A file belonging to user 2 (must not leak into user 1's re-analysis).
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name)
               VALUES (20, 2, 'h9', '/p/z.jpg', '/o', 'dev', 1, 'z.jpg')"""
        )
        await db.commit()
    finally:
        await db.close()


async def test_enqueue_reanalysis_all_enqueues_only_live_own_originals(tmp_path):
    db_url = str(tmp_path / "photovault.db")
    await _setup_db(db_url)
    settings = types.SimpleNamespace(database_url=db_url)

    queued = await enqueue_reanalysis_all(user_id=1, settings=settings)

    assert queued == 2  # ids 10 and 11 only
    queue = get_analysis_queue()
    items = {queue.get_nowait() for _ in range(queue.qsize())}
    assert items == {(1, 10), (1, 11)}


async def test_enqueue_reanalysis_all_user_isolation(tmp_path):
    db_url = str(tmp_path / "photovault.db")
    await _setup_db(db_url)
    settings = types.SimpleNamespace(database_url=db_url)

    queued = await enqueue_reanalysis_all(user_id=2, settings=settings)

    assert queued == 1
    queue = get_analysis_queue()
    assert queue.get_nowait() == (2, 20)


async def test_worker_processes_queued_items():
    """The worker drains the queue, calling analyze_file(user_id, file_id)."""
    calls: list[tuple[int, int]] = []
    done = asyncio.Event()

    class FakeService:
        async def analyze_file(self, user_id, file_id):
            calls.append((user_id, file_id))
            if len(calls) == 2:
                done.set()

    with pytest.MonkeyPatch.context() as mp:
        # AnalysisService is imported inside the task from its own module, so
        # patch the symbol there.
        mp.setattr(
            "app.services.analysis_service.AnalysisService",
            lambda: FakeService(),
        )
        enqueue_analysis(file_id=10, user_id=1)
        enqueue_analysis(file_id=11, user_id=1)

        task = asyncio.create_task(analysis_worker_task())
        await asyncio.wait_for(done.wait(), timeout=2.0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    assert calls == [(1, 10), (1, 11)]


async def test_worker_isolates_per_file_failure():
    """A single file's analysis failure must not stop the worker."""
    calls: list[tuple[int, int]] = []
    done = asyncio.Event()

    class FakeService:
        async def analyze_file(self, user_id, file_id):
            calls.append((user_id, file_id))
            if file_id == 10:
                raise RuntimeError("boom")
            if file_id == 11:
                done.set()

    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(
            "app.services.analysis_service.AnalysisService",
            lambda: FakeService(),
        )
        enqueue_analysis(file_id=10, user_id=1)  # fails
        enqueue_analysis(file_id=11, user_id=1)  # still processed

        task = asyncio.create_task(analysis_worker_task())
        await asyncio.wait_for(done.wait(), timeout=2.0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    assert calls == [(1, 10), (1, 11)]


async def test_worker_handles_cancellation_while_idle():
    """Cancelling a worker blocked on an empty queue raises CancelledError."""
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(
            "app.services.analysis_service.AnalysisService",
            lambda: types.SimpleNamespace(
                analyze_file=lambda *a, **k: asyncio.sleep(0)
            ),
        )
        task = asyncio.create_task(analysis_worker_task())
        await asyncio.sleep(0.05)  # let it block on queue.get()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
