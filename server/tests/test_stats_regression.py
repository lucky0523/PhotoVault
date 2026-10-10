"""Regression: existing endpoints keep their focal_length / exif_time semantics (AC29).

Rows carry the new metadata columns (capture_time, focal_35mm, ...) with values
that differ from the legacy ``exif_time`` / ``focal_length`` columns. The
existing endpoints must keep returning the legacy columns untouched.
"""

from __future__ import annotations

import os

import aiosqlite
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.explore import router as explore_router
from app.api.files import router as files_router
from app.core.config import get_settings, reset_settings
from app.core.database import get_db, init_db
from app.services.analysis_queue import reset_analysis_queue
from app.services.auth_service import AuthService

LEGACY = {
    1: {"exif_time": "2024-05-01T08:30:00", "focal_length": 26.0},
    2: {"exif_time": "2024-05-02T09:00:00+00:00", "focal_length": 6.9},
    3: {"exif_time": None, "focal_length": None},
}


@pytest.fixture(autouse=True)
def _reset_singletons():
    reset_settings()
    reset_analysis_queue()
    yield
    reset_settings()
    reset_analysis_queue()


@pytest.fixture
async def db_path(tmp_path):
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-stats-regression"
    reset_settings()
    path = str(tmp_path / "test.db")
    await init_db(path)
    yield path
    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    reset_settings()


@pytest.fixture
async def client(db_path):
    app = FastAPI()

    async def _override_get_db():
        db = await aiosqlite.connect(db_path)
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        try:
            yield db
        finally:
            await db.close()

    app.dependency_overrides[get_db] = _override_get_db
    app.include_router(files_router)
    app.include_router(explore_router)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver") as c:
        yield c


async def _seed(db_path: str) -> str:
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        auth = AuthService(db)
        user = await auth.create_user("alice", "password123")
        token = (await auth.login("alice", "password123")).access_token
        base = f"{get_settings().media_root}/alice/Pixel"
        for fid, legacy in LEGACY.items():
            await db.execute(
                """INSERT INTO file_records
                   (id, user_id, file_hash, file_path, original_path, device_name,
                    file_size, file_name, mime_type, media_type, exif_time, focal_length,
                    capture_time, capture_time_kind, focal_35mm, focal_35mm_source,
                    focal_actual, iso, meta_version, meta_status)
                   VALUES (?, ?, ?, ?, '/orig', 'Pixel', 1000, ?, 'image/jpeg', 'image',
                           ?, ?, '2020-01-01T00:00:00', 'local', 99.0, 'exif', 3.3, 400,
                           1, 'ok')""",
                (fid, user.id, f"h{fid}", f"{base}/{fid}.jpg", f"{fid}.jpg",
                 legacy["exif_time"], legacy["focal_length"]),
            )
            await db.execute(
                """INSERT INTO photo_gps (file_id, user_id, latitude, longitude, city)
                   VALUES (?, ?, 1.0, 2.0, '北京')""",
                (fid, user.id),
            )
        await db.commit()
    return token


def _by_id(files: list[dict]) -> dict[int, dict]:
    return {f["id"]: f for f in files}


class TestLegacyFieldsUnchanged:
    async def test_files_browse(self, client, db_path):
        token = await _seed(db_path)
        resp = await client.get(
            "/files/browse", params={"path": "Pixel"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 200
        files = _by_id(resp.json()["files"])
        assert set(files) == set(LEGACY)
        for fid, legacy in LEGACY.items():
            assert files[fid]["exif_time"] == legacy["exif_time"]
            # /files/browse never populated focal_length; it still does not.
            assert files[fid]["focal_length"] is None

    async def test_files_all(self, client, db_path):
        token = await _seed(db_path)
        resp = await client.get("/files/all", headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200
        files = _by_id(resp.json()["files"])
        assert set(files) == set(LEGACY)
        for fid, legacy in LEGACY.items():
            assert files[fid]["exif_time"] == legacy["exif_time"]
            assert files[fid]["focal_length"] == legacy["focal_length"]

    async def test_explore_photo_list(self, client, db_path):
        token = await _seed(db_path)
        resp = await client.get(
            "/explore/places/北京", headers={"Authorization": f"Bearer {token}"}
        )
        assert resp.status_code == 200
        files = _by_id(resp.json()["files"])
        assert set(files) == set(LEGACY)
        for fid, legacy in LEGACY.items():
            assert files[fid]["exif_time"] == legacy["exif_time"]
            assert files[fid]["focal_length"] == legacy["focal_length"]
