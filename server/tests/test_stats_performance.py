"""Performance: GET /stats/media on 100k rows, P95 <= 2 s (NFR-1, AC30).

Run with: ``pytest -m performance tests/test_stats_performance.py -s``.
Marked ``slow`` like tests/test_performance.py: the wall-clock assertion is
sensitive to machine load, so routine runs can skip it with ``-m "not slow"``.

The data covers all eleven aggregations: photos and videos mixed, ~30 cameras,
~20 lenses, random ISO / focal / aperture / shutter / duration, capture times
over ten years with both 'local' and 'utc' kinds, several directories.
"""

from __future__ import annotations

import math
import os
import random
import time
from unittest.mock import patch

import aiosqlite
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.stats import router as stats_router
from app.core.config import get_settings, reset_settings
from app.core.database import get_db, init_db
from app.services import stats_buckets
from app.services.auth_service import AuthService

ROWS = 100_000
CALLS = 20
P95_LIMIT_SECONDS = 2.0

pytestmark = [pytest.mark.slow, pytest.mark.performance]


@pytest.fixture
async def db_path(tmp_path):
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-stats-performance"
    saved_tz = os.environ.pop("PHOTOVAULT_DEFAULT_TIMEZONE", None)
    reset_settings()
    path = str(tmp_path / "test.db")
    await init_db(path)
    yield path
    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    if saved_tz is not None:
        os.environ["PHOTOVAULT_DEFAULT_TIMEZONE"] = saved_tz
    reset_settings()


def _rows(user_id: int, base: str):
    rng = random.Random(42)
    cameras = [(f"Make{i % 6}", f"Model {i:02d}") for i in range(30)]
    lenses = [f"Lens {i:02d}" for i in range(20)]
    devices = ["Pixel9Pro/DCIM/Camera", "Pixel9Pro/Pictures", "iPhone/DCIM", "Canon"]
    for i in range(ROWS):
        is_video = rng.random() < 0.2
        ext = rng.choice(["mp4", "mov"]) if is_video else rng.choice(["jpg", "jpg", "heic", "dng", "png"])
        year = 2015 + rng.randrange(10)
        ts = (
            f"{year:04d}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}"
            f"T{rng.randrange(24):02d}:{rng.randrange(60):02d}:{rng.randrange(60):02d}"
        )
        has_time = rng.random() < 0.95
        kind = ("utc" if is_video else "local") if has_time else None
        make, model = rng.choice(cameras) if rng.random() < 0.8 else (None, None)
        yield (
            user_id,
            f"h{i}",
            f"{base}/{rng.choice(devices)}/{i}.{ext}",
            "/orig",
            "dev",
            rng.randint(100_000, 50_000_000),
            f"{i}.{ext}",
            "video/mp4" if is_video else "image/jpeg",
            "video" if is_video else "image",
            ts if has_time else None,
            kind,
            None if is_video else rng.choice([50, 100, 200, 400, 800, 1600, 3200, 6400, None]),
            None if is_video else rng.choice([13.0, 24.0, 26.0, 35.0, 50.0, 85.0, 120.0, 240.0, None]),
            None if is_video else rng.choice(["exif", "derived"]),
            rng.uniform(1, 900) if is_video else None,
            None if is_video else rng.choice([1.4, 1.8, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, None]),
            None if is_video else rng.choice([1 / 4000, 1 / 1000, 1 / 250, 1 / 60, 1 / 30, 0.5, 2.0, None]),
            make,
            model,
            None if is_video else rng.choice(lenses + [None]),
            1 if rng.random() < 0.97 else None,
            "ok",
        )


async def _seed(db_path: str) -> str:
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        auth = AuthService(db)
        user = await auth.create_user("alice", "password123")
        token = (await auth.login("alice", "password123")).access_token
        base = f"{get_settings().media_root}/alice"
        await db.executemany(
            """INSERT INTO file_records
               (user_id, file_hash, file_path, original_path, device_name, file_size,
                file_name, mime_type, media_type, capture_time, capture_time_kind, iso,
                focal_35mm, focal_35mm_source, duration_sec, f_number, exposure_time,
                camera_make, camera_model, lens_model, meta_version, meta_status)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            list(_rows(user.id, base)),
        )
        await db.commit()
    return token


def _p95(samples: list[float]) -> float:
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]


async def test_media_stats_p95_100k(db_path):
    token = await _seed(db_path)

    app = FastAPI()

    async def _override_get_db():
        db = await aiosqlite.connect(db_path)
        db.row_factory = aiosqlite.Row
        try:
            yield db
        finally:
            await db.close()

    app.dependency_overrides[get_db] = _override_get_db
    app.include_router(stats_router)
    headers = {"Authorization": f"Bearer {token}"}
    params = {"tz": "Asia/Shanghai"}

    with patch("app.services.media_metadata.ffprobe_path", return_value="/usr/bin/ffprobe"):
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://testserver"
        ) as client:
            # First request with a cold interval-table cache: printed only.
            stats_buckets._intervals_cached.cache_clear()
            start = time.perf_counter()
            resp = await client.get("/stats/media", params=params, headers=headers)
            cold = time.perf_counter() - start
            assert resp.status_code == 200
            assert resp.json()["overview"]["total"]["count"] == ROWS

            samples = []
            for i in range(CALLS):
                call_params = dict(params)
                if i % 2:
                    call_params["path"] = "Pixel9Pro"
                start = time.perf_counter()
                resp = await client.get("/stats/media", params=call_params, headers=headers)
                samples.append(time.perf_counter() - start)
                assert resp.status_code == 200

    p95 = _p95(samples)
    print(
        f"\n[stats perf] rows={ROWS} calls={CALLS} cold_first={cold:.3f}s "
        f"p50={sorted(samples)[len(samples) // 2]:.3f}s p95={p95:.3f}s max={max(samples):.3f}s"
    )
    assert p95 <= P95_LIMIT_SECONDS
