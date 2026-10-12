"""Tests for app.services.metadata_backfill (design §4.8; FR-7.3, AC28)."""

import asyncio
import json
import logging
import sqlite3
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import aiosqlite
import pytest
from PIL import Image
from PIL.ExifTags import IFD
from PIL.TiffImagePlugin import IFDRational

from app.core.database import init_db
from app.services import background_tasks as bt
from app.services import media_metadata as mm
from app.services import metadata_backfill as mb
from app.services.media_metadata import CURRENT_META_VERSION, MediaMetadata


@pytest.fixture(autouse=True)
def _fast(monkeypatch):
    monkeypatch.setattr(mb, "BATCH_PAUSE_SECONDS", 0)
    monkeypatch.setattr(bt, "DB_NOT_READY_POLL_SECONDS", 0)
    mm._reset_ffprobe_cache()
    yield
    mm._reset_ffprobe_cache()


@pytest.fixture
def db_path(tmp_path):
    return str(tmp_path / "backfill.db")


@pytest.fixture
async def db(db_path):
    await init_db(db_path)
    conn = await aiosqlite.connect(db_path)
    conn.row_factory = aiosqlite.Row
    await conn.execute("INSERT INTO users (username, password_hash) VALUES ('alice', 'h')")
    await conn.commit()
    try:
        yield conn
    finally:
        await conn.close()


def _jpeg(path: Path) -> Path:
    img = Image.new("RGB", (32, 24), "green")
    exif = Image.Exif()
    exif[271] = "NIKON CORPORATION"
    exif[272] = "NIKON D850"
    ifd = exif.get_ifd(IFD.Exif)
    ifd[34855] = 64
    ifd[33437] = IFDRational(56, 10)
    ifd[33434] = IFDRational(1, 125)
    ifd[36867] = "2023:11:05 07:08:09"
    ifd[41989] = 85
    ifd[42036] = "AF-S NIKKOR 85mm f/1.8G"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="JPEG", exif=exif.tobytes())
    return path


_counter = 0


async def _insert(db, file_path, *, media_type="image", exif_time=None, **cols) -> int:
    global _counter
    _counter += 1
    fields = {
        "user_id": 1,
        "file_hash": f"hash{_counter}",
        "file_path": str(file_path),
        "original_path": f"/DCIM/{Path(file_path).name}",
        "device_name": "Pixel",
        "file_size": 10,
        "file_name": Path(file_path).name,
        "media_type": media_type,
        "exif_time": exif_time,
        **cols,
    }
    names = ", ".join(fields)
    marks = ", ".join("?" for _ in fields)
    cursor = await db.execute(
        f"INSERT INTO file_records ({names}) VALUES ({marks})", tuple(fields.values())
    )
    await db.commit()
    return cursor.lastrowid


async def _row(db, file_id) -> dict:
    cursor = await db.execute("SELECT * FROM file_records WHERE id = ?", (file_id,))
    return dict(await cursor.fetchone())


async def _pending(db, has_ffprobe: int) -> int:
    cursor = await db.execute(mb._PENDING_COUNT_SQL, (CURRENT_META_VERSION, has_ffprobe))
    return (await cursor.fetchone())[0]


def _ffprobe_json(duration="3.0", creation="2025-01-15T15:30:00Z"):
    payload = {"format": {"duration": duration, "tags": {"creation_time": creation}}}
    return subprocess.CompletedProcess(
        args=[], returncode=0, stdout=json.dumps(payload).encode(), stderr=b""
    )


class TestSeedCaptureTimes:
    async def test_seeds_from_client_time_without_marking_attempted(self, db, tmp_path):
        photo = await _insert(db, tmp_path / "a.jpg", exif_time="2024-05-01T10:00:00+00:00")
        video = await _insert(
            db, tmp_path / "b.mp4", media_type="video", exif_time="2024-05-01T10:00:00"
        )
        no_time = await _insert(db, tmp_path / "c.jpg")
        already = await _insert(
            db, tmp_path / "d.jpg", exif_time="2024-05-01T10:00:00",
            capture_time="2020-01-01T00:00:00", capture_time_kind="local",
        )
        done = await _insert(
            db, tmp_path / "e.jpg", exif_time="2024-05-01T10:00:00", meta_version=1
        )

        assert await mb.seed_capture_times(db) == 2

        assert (await _row(db, photo))["capture_time"] == "2024-05-01T10:00:00"
        assert (await _row(db, photo))["capture_time_kind"] == "local"
        assert (await _row(db, video))["capture_time_kind"] == "utc"
        assert (await _row(db, photo))["meta_version"] is None
        assert (await _row(db, no_time))["capture_time"] is None
        assert (await _row(db, already))["capture_time"] == "2020-01-01T00:00:00"
        assert (await _row(db, done))["capture_time"] is None

        # Idempotent.
        assert await mb.seed_capture_times(db) == 0

    async def test_pages(self, db, tmp_path, monkeypatch):
        monkeypatch.setattr(mb, "SEED_PAGE_SIZE", 2)
        for i in range(5):
            await _insert(db, tmp_path / f"p{i}.jpg", exif_time="2024-01-01T00:00:00")
        assert await mb.seed_capture_times(db) == 5


class TestRunBackfillRound:
    async def test_old_database_reaches_zero_pending(self, db, tmp_path):
        """AC28: one round clears pending; a second round processes nothing."""
        root = tmp_path / "media" / "alice"
        ok_photo = await _insert(db, _jpeg(root / "a.jpg"), exif_time="2020-01-01T00:00:00")
        untyped = await _insert(db, _jpeg(root / "b.jpg"), media_type=None)
        missing = await _insert(db, root / "gone.jpg", exif_time="2022-02-02T02:02:02")
        zombie_path = _jpeg(root / ".trash" / "z.jpg")
        zombie = await _insert(db, zombie_path)
        trashed = await _insert(
            db, _jpeg(root / "t.jpg"), deleted_at="2025-01-01T00:00:00"
        )
        reference = await _insert(db, _jpeg(root / "r.jpg"), is_reference=1)

        with patch("app.services.media_metadata.shutil.which", return_value=None):
            await mb.seed_capture_times(db)
            stats = await mb.run_backfill_round(db)
            assert stats.processed == 4
            assert stats.ok == 2
            assert stats.error == 2
            assert stats.skipped == 0
            assert await _pending(db, 0) == 0

            again = await mb.run_backfill_round(db)
            assert again.processed == 0

        row = await _row(db, ok_photo)
        assert row["meta_version"] == CURRENT_META_VERSION
        assert row["meta_status"] == "ok"
        assert row["capture_time"] == "2023-11-05T07:08:09"
        assert row["f_number"] == 5.6
        assert row["exposure_time"] == pytest.approx(1 / 125)
        assert row["camera_make"] == "NIKON CORPORATION"
        assert row["camera_model"] == "NIKON D850"
        assert row["lens_model"] == "AF-S NIKKOR 85mm f/1.8G"
        assert row["iso"] == 64
        assert row["focal_35mm"] == 85.0

        # media_type NULL goes through the photo extractor.
        row = await _row(db, untyped)
        assert row["meta_status"] == "ok"
        assert row["iso"] == 64

        row = await _row(db, missing)
        assert row["meta_status"] == "error"
        assert row["meta_version"] == CURRENT_META_VERSION
        assert row["capture_time"] == "2022-02-02T02:02:02"

        assert (await _row(db, zombie))["meta_status"] == "error"
        assert (await _row(db, trashed))["meta_version"] is None
        assert (await _row(db, reference))["meta_version"] is None

    async def test_cancel_and_resume(self, db, db_path, tmp_path, monkeypatch):
        monkeypatch.setattr(mb, "BATCH_SIZE", 1)
        monkeypatch.setattr(mb, "BATCH_PAUSE_SECONDS", 0.05)
        ids = [await _insert(db, _jpeg(tmp_path / f"c{i}.jpg")) for i in range(6)]

        task = asyncio.create_task(mb.run_backfill_round(db))
        async with aiosqlite.connect(db_path) as reader:
            for _ in range(200):
                cursor = await reader.execute(
                    "SELECT COUNT(*) FROM file_records WHERE meta_version IS NOT NULL"
                )
                if (await cursor.fetchone())[0] >= 1:
                    break
                await asyncio.sleep(0.01)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

        cursor = await db.execute(
            "SELECT COUNT(*) FROM file_records WHERE meta_version IS NOT NULL"
        )
        done_before = (await cursor.fetchone())[0]
        assert 1 <= done_before < len(ids)

        monkeypatch.setattr(mb, "BATCH_PAUSE_SECONDS", 0)
        stats = await mb.run_backfill_round(db)
        assert stats.processed == len(ids) - done_before
        assert await _pending(db, 1) == 0

    async def test_no_ffprobe_rows_rescanned_when_ffprobe_appears(self, db, tmp_path):
        """FR-7.3 / AC28: no_ffprobe rows are re-selected once ffprobe exists."""
        clip = tmp_path / "v.mp4"
        clip.write_bytes(b"fake")
        file_id = await _insert(db, clip, media_type="video")

        with patch("app.services.media_metadata.shutil.which", return_value=None):
            stats = await mb.run_backfill_round(db)
            assert stats.no_ffprobe == 1
            assert (await _row(db, file_id))["meta_status"] == "no_ffprobe"
            assert (await mb.run_backfill_round(db)).processed == 0

        mm._reset_ffprobe_cache()
        run = MagicMock(return_value=_ffprobe_json())
        with patch(
            "app.services.media_metadata.shutil.which", return_value="/fake/ffprobe"
        ), patch("app.services.media_metadata.subprocess.run", run):
            stats = await mb.run_backfill_round(db)
            assert stats.processed == 1
            assert stats.ok == 1
            assert (await mb.run_backfill_round(db)).processed == 0

        row = await _row(db, file_id)
        assert row["meta_status"] == "ok"
        assert row["duration_sec"] == 3.0
        assert row["capture_time"] == "2025-01-15T15:30:00"
        assert row["capture_time_kind"] == "utc"

    async def test_upload_timeout_row_is_retried_with_15s(self, db, tmp_path):
        clip = tmp_path / "slow.mp4"
        clip.write_bytes(b"fake")
        file_id = await _insert(
            db, clip, media_type="video", meta_status="timeout",
            capture_time="2025-01-15T15:30:00", capture_time_kind="utc",
        )

        run = MagicMock(return_value=_ffprobe_json(duration="61.5"))
        with patch(
            "app.services.media_metadata.ffprobe_path", return_value="/fake/ffprobe"
        ), patch(
            "app.services.metadata_backfill.ffprobe_path", return_value="/fake/ffprobe"
        ), patch("app.services.media_metadata.subprocess.run", run):
            stats = await mb.run_backfill_round(db)

        assert stats.processed == 1
        assert run.call_args.kwargs["timeout"] == 15
        row = await _row(db, file_id)
        assert row["meta_status"] == "ok"
        assert row["meta_version"] == CURRENT_META_VERSION
        assert row["duration_sec"] == 61.5

    async def test_backfill_timeout_becomes_error_and_stops(self, db, tmp_path):
        clip = tmp_path / "stuck.mp4"
        clip.write_bytes(b"fake")
        file_id = await _insert(
            db, clip, media_type="video", exif_time="2025-01-15T15:30:00",
            meta_status="timeout",
        )

        run = MagicMock(side_effect=subprocess.TimeoutExpired(cmd="ffprobe", timeout=15))
        with patch(
            "app.services.media_metadata.ffprobe_path", return_value="/fake/ffprobe"
        ), patch(
            "app.services.metadata_backfill.ffprobe_path", return_value="/fake/ffprobe"
        ), patch("app.services.media_metadata.subprocess.run", run):
            stats = await mb.run_backfill_round(db)
            assert stats.error == 1
            row = await _row(db, file_id)
            assert row["meta_status"] == "error"
            assert row["meta_version"] == CURRENT_META_VERSION
            assert row["capture_time"] == "2025-01-15T15:30:00"

            run.reset_mock()
            assert (await mb.run_backfill_round(db)).processed == 0
            run.assert_not_called()

    async def test_row_trashed_during_extraction_is_skipped(self, db, db_path, tmp_path):
        """Race (review 3-4): the optimistic guard skips a row moved meanwhile."""
        original = _jpeg(tmp_path / "alice" / "race.jpg")
        file_id = await _insert(db, original)
        trash_path = str(tmp_path / "alice" / ".trash" / "race.jpg")

        def move_to_trash(path, media_type, exif_time, *, ffprobe_timeout):
            with sqlite3.connect(db_path) as conn:
                conn.execute(
                    "UPDATE file_records SET file_path = ?, deleted_at = '2025-01-01T00:00:00' "
                    "WHERE id = ?",
                    (trash_path, file_id),
                )
            return MediaMetadata(iso=100)

        with patch(
            "app.services.metadata_backfill.extract_media_metadata",
            side_effect=move_to_trash,
        ):
            stats = await mb.run_backfill_round(db)

        assert stats.skipped == 1
        assert stats.processed == 0
        row = await _row(db, file_id)
        assert row["meta_version"] is None
        assert row["iso"] is None

        # Restored from the trash: the next round fills it in.
        await db.execute(
            "UPDATE file_records SET file_path = ?, deleted_at = NULL WHERE id = ?",
            (str(original), file_id),
        )
        await db.commit()
        stats = await mb.run_backfill_round(db)
        assert stats.processed == 1
        row = await _row(db, file_id)
        assert row["meta_version"] == CURRENT_META_VERSION
        assert row["iso"] == 64

    async def test_database_error_ends_round_with_warning(self, db, tmp_path, caplog):
        await _insert(db, _jpeg(tmp_path / "x.jpg"))
        with patch(
            "app.services.metadata_backfill.apply_media_metadata_many",
            side_effect=aiosqlite.OperationalError("database is locked"),
        ), caplog.at_level(logging.WARNING, logger="photovault.metadata_backfill"):
            stats = await mb.run_backfill_round(db)
        assert stats.processed == 0
        assert "aborted by a database error" in caplog.text

    async def test_logs_only_when_pending(self, db, tmp_path, caplog):
        with caplog.at_level(logging.INFO, logger="photovault.metadata_backfill"):
            await mb.run_backfill_round(db)
        assert "Metadata backfill started" not in caplog.text
        assert "Metadata backfill finished" not in caplog.text

        await _insert(db, _jpeg(tmp_path / "l.jpg"))
        caplog.clear()
        with caplog.at_level(logging.INFO, logger="photovault.metadata_backfill"):
            await mb.run_backfill_round(db)
            await mb.run_backfill_round(db)
        messages = [r.getMessage() for r in caplog.records]
        assert messages.count("Metadata backfill started: pending=1") == 1
        finished = [m for m in messages if m.startswith("Metadata backfill finished")]
        assert len(finished) == 1
        assert "processed=1 ok=1 error=0 no_ffprobe=0 skipped=0" in finished[0]


class TestMetadataBackfillTask:
    async def _drive(self, db_path, *, ready, seed, rounds_wanted=3):
        """Run the task with interval 0 until enough rounds were attempted."""
        calls: list[str] = []
        ready_iter = iter(ready)

        def database_ready():
            value = next(ready_iter, True)
            calls.append(f"ready:{value}")
            return value

        async def run_round(db):
            calls.append("round")
            return mb.BackfillStats()

        async def seed_wrapper(db):
            calls.append("seed")
            return await seed(db)

        settings = SimpleNamespace(database_url=db_path)
        with patch.object(mb, "_database_ready", side_effect=database_ready), \
                patch.object(mb, "seed_capture_times", side_effect=seed_wrapper), \
                patch.object(mb, "run_backfill_round", side_effect=run_round), \
                patch.object(mb, "get_settings", return_value=settings), \
                patch.object(mb, "ffprobe_path", return_value=None) as probe:
            task = asyncio.create_task(mb.metadata_backfill_task(interval_seconds=0))
            for _ in range(500):
                if calls.count("round") >= rounds_wanted:
                    break
                await asyncio.sleep(0.005)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            probe.assert_called()
        return calls

    async def test_seed_waits_for_database_then_runs_once(self, db_path):
        await init_db(db_path)
        seed = AsyncMock(return_value=0)
        calls = await self._drive(db_path, ready=[False, True], seed=seed)

        assert calls[0] == "ready:False"
        assert calls[1:4] == ["ready:True", "seed", "round"]
        assert calls.count("seed") == 1

    async def test_seed_retried_after_failure(self, db_path, caplog):
        await init_db(db_path)
        seed = AsyncMock(side_effect=[aiosqlite.OperationalError("locked"), 5])
        with caplog.at_level(logging.INFO, logger="photovault.metadata_backfill"):
            calls = await self._drive(db_path, ready=[], seed=seed)

        # Round 1: seed fails, no phase 2. Round 2: seed succeeds, phase 2 runs.
        assert calls[:5] == ["ready:True", "seed", "ready:True", "seed", "round"]
        assert calls.count("seed") == 2
        assert "Metadata backfill seeded capture_time for 5 rows" in caplog.text

    async def test_cancellation_is_propagated(self, db_path, caplog):
        await init_db(db_path)
        in_round = asyncio.Event()

        async def blocking_round(db):
            in_round.set()
            await asyncio.Event().wait()

        settings = SimpleNamespace(database_url=db_path)
        with patch.object(mb, "_database_ready", return_value=True), \
                patch.object(mb, "get_settings", return_value=settings), \
                patch.object(mb, "seed_capture_times", AsyncMock(return_value=0)), \
                patch.object(mb, "run_backfill_round", side_effect=blocking_round), \
                caplog.at_level(logging.INFO, logger="photovault.metadata_backfill"):
            task = asyncio.create_task(mb.metadata_backfill_task(interval_seconds=3600))
            await asyncio.wait_for(in_round.wait(), timeout=5)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        assert "Metadata backfill task cancelled" in caplog.text

    async def test_first_round_runs_promptly_after_database_becomes_ready(
        self, db_path, monkeypatch, caplog
    ):
        """Not ready at start: the first seed + round must not wait the full interval."""
        await init_db(db_path)
        monkeypatch.setattr(bt, "DB_NOT_READY_POLL_SECONDS", 0.01)
        # setup_logging() elsewhere in the suite turns propagation off.
        monkeypatch.setattr(logging.getLogger("photovault"), "propagate", True)
        ready_values = iter([False, False])
        calls: list[str] = []
        round_done = asyncio.Event()

        async def seed(db):
            calls.append("seed")
            return 0

        async def run_round(db):
            calls.append("round")
            round_done.set()
            return mb.BackfillStats()

        settings = SimpleNamespace(database_url=db_path)
        with patch.object(mb, "_database_ready", side_effect=lambda: next(ready_values, True)), \
                patch.object(mb, "seed_capture_times", side_effect=seed), \
                patch.object(mb, "run_backfill_round", side_effect=run_round), \
                patch.object(mb, "get_settings", return_value=settings), \
                patch.object(mb, "ffprobe_path", return_value=None), \
                caplog.at_level(logging.INFO, logger="photovault.metadata_backfill"):
            task = asyncio.create_task(mb.metadata_backfill_task(interval_seconds=3600))
            try:
                await asyncio.wait_for(round_done.wait(), timeout=2)
                assert calls == ["seed", "round"]
                # The normal (one hour) interval is in force after the first round.
                await asyncio.sleep(0.1)
                assert calls.count("round") == 1
            finally:
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task

        messages = [r.getMessage() for r in caplog.records]
        assert messages.count("Metadata backfill: database ready, running first round") == 1
