"""API tests for GET /stats/media (design §5, §6, §10; AC1–24, AC31).

Same pattern as ``test_api_explore.py``: temp SQLite DB via env vars +
``init_db``, a FastAPI app with ``dependency_overrides[get_db]`` and real JWT
tokens minted through ``AuthService``. Rows are inserted directly with the
metadata columns already filled in; ``file_path`` follows the real layout
``{media_root}/{username}/...``.
"""

from __future__ import annotations

import logging
import os
import random
import re
import sqlite3
import statistics
from datetime import datetime, timezone
from unittest.mock import patch

import aiosqlite
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.stats import router as stats_router
from app.core.config import get_settings, reset_settings
from app.core.database import get_db, init_db
from app.services import media_stats_service
from app.services.auth_service import AuthService
from app.services.exposure_value import SQL_EV100_FUNCTION, ev100_value
from app.services.stats_buckets import SCENE_EV_SPEC, bucket_case_sql

URL = "/stats/media"

PHOTO_KEYS = {
    "weekday", "hour", "iso", "focal_length", "aperture", "shutter", "scene_ev",
    "camera", "lens", "monthly", "yearly",
}
VIDEO_KEYS = {"weekday", "hour", "duration", "camera", "monthly", "yearly"}

_MIME = {
    "jpg": "image/jpeg", "jpeg": "image/jpeg", "heic": "image/heic",
    "png": "image/png", "mp4": "video/mp4", "mov": "video/quicktime",
    "txt": "text/plain",
}
_VIDEO_EXTS = {"mp4", "mov", "mkv"}


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_settings_singleton():
    reset_settings()
    yield
    reset_settings()


@pytest.fixture(autouse=True)
def _ffprobe_available():
    """Default: ffprobe present. Tests that need it missing patch again."""
    with patch("app.services.media_metadata.ffprobe_path", return_value="/usr/bin/ffprobe"):
        yield


@pytest.fixture
async def db_path(tmp_path):
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-media-stats-tests"
    # Clear settings that other test modules may leak through os.environ:
    # the default time zone would change tz_source, and a leaked
    # PHOTOVAULT_MAX_USERS (test_api_admin) would block multi-user tests.
    saved = {
        key: os.environ.pop(key, None)
        for key in ("PHOTOVAULT_DEFAULT_TIMEZONE", "PHOTOVAULT_MAX_USERS")
    }
    reset_settings()

    path = str(tmp_path / "test.db")
    await init_db(path)

    yield path

    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    for key, value in saved.items():
        os.environ.pop(key, None)
        if value is not None:
            os.environ[key] = value
    reset_settings()


def _make_app(db_path: str, on_connect=None) -> FastAPI:
    app = FastAPI()

    async def _override_get_db():
        db = await aiosqlite.connect(db_path)
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        try:
            if on_connect:
                await on_connect(db)
            yield db
        finally:
            await db.close()

    app.dependency_overrides[get_db] = _override_get_db
    app.include_router(stats_router)
    return app


@pytest.fixture
async def client(db_path):
    transport = ASGITransport(app=_make_app(db_path))
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _create_user(db_path: str, username: str, is_admin: bool = False):
    async with aiosqlite.connect(db_path) as db:
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        auth = AuthService(db)
        user = await auth.create_user(username, "password123", is_admin=is_admin)
        tokens = await auth.login(username, "password123")
    return user.id, tokens.access_token


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


_counter = {"n": 0}


async def _insert(
    db_path: str,
    user_id: int,
    username: str,
    rel: str,
    *,
    file_path: str | None = None,
    **cols,
) -> None:
    """Insert one file_records row under ``{media_root}/{username}/{rel}``."""
    _counter["n"] += 1
    name = rel.rsplit("/", 1)[-1]
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    row = {
        "user_id": user_id,
        "file_hash": f"hash-{_counter['n']}",
        "file_path": file_path or f"{get_settings().media_root}/{username}/{rel}",
        "original_path": f"/orig/{name}",
        "device_name": rel.split("/", 1)[0],
        "file_size": 1000,
        "file_name": name,
        "mime_type": _MIME.get(ext),
        "media_type": "video" if ext in _VIDEO_EXTS else "image",
        "meta_version": 1,
        "meta_status": "ok",
    }
    row.update(cols)
    names = ", ".join(row)
    marks = ", ".join("?" for _ in row)
    async with aiosqlite.connect(db_path) as db:
        await db.execute(f"INSERT INTO file_records ({names}) VALUES ({marks})", list(row.values()))
        await db.commit()


async def _get(client, token, **params):
    resp = await client.get(URL, params=params, headers=_auth(token))
    assert resp.status_code == 200, resp.text
    return resp.json()


def _counts(dist: dict) -> dict[str, int]:
    return {b["key"]: b["count"] for b in dist["buckets"]}


def _total(data: dict) -> int:
    return data["overview"]["total"]["count"]


def _all_distributions(data: dict):
    yield data["overview"]["categories"]
    yield data["overview"]["formats"]
    yield from data["photo"].values()
    yield from data["video"].values()


def _assert_tenths(shares) -> None:
    assert sum(round(s * 10) for s in shares) == 1000


# ---------------------------------------------------------------------------
# Scope and permissions (AC1–AC7)
# ---------------------------------------------------------------------------


class TestScope:
    async def test_user_isolation_and_admin(self, client, db_path):
        alice, ta = await _create_user(db_path, "alice")
        bob, tb = await _create_user(db_path, "bob")
        admin, tadm = await _create_user(db_path, "admin", is_admin=True)
        await _insert(db_path, alice, "alice", "DeviceX/a.jpg")
        await _insert(db_path, bob, "bob", "DeviceX/b1.jpg")
        await _insert(db_path, bob, "bob", "DeviceX/b2.jpg")
        await _insert(db_path, admin, "admin", "Other/c.jpg")

        assert _total(await _get(client, ta, path="DeviceX")) == 1
        assert _total(await _get(client, ta)) == 1
        assert _total(await _get(client, tb, path="DeviceX")) == 2
        assert _total(await _get(client, tadm)) == 1
        assert _total(await _get(client, tadm, path="DeviceX")) == 0

    async def test_unauthenticated_401(self, client, db_path):
        resp = await client.get(URL)
        assert resp.status_code == 401
        resp = await client.get(URL, headers={"Authorization": "Bearer not-a-token"})
        assert resp.status_code == 401

    async def test_recursive_and_direct(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "DeviceX/top.jpg")
        await _insert(db_path, uid, "alice", "DeviceX/DCIM/Camera/deep.jpg")
        await _insert(db_path, uid, "alice", "DeviceY/elsewhere.jpg")

        data = await _get(client, t, path="DeviceX")
        assert _total(data) == 2
        assert data["scope"]["recursive"] is True
        data = await _get(client, t, path="DeviceX", recursive="false")
        assert _total(data) == 1
        assert data["scope"]["recursive"] is False
        assert _total(await _get(client, t, path="DeviceX/DCIM")) == 1
        # Leading / trailing slashes are normalised away.
        data = await _get(client, t, path="/DeviceX/")
        assert data["scope"]["path"] == "DeviceX"
        assert _total(data) == 2

    async def test_underscore_is_not_a_wildcard(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "a_b/1.jpg")
        await _insert(db_path, uid, "alice", "axb/2.jpg")
        await _insert(db_path, uid, "alice", "axb/3.jpg")
        await _insert(db_path, uid, "alice", "a_b0/4.jpg")

        assert _total(await _get(client, t, path="a_b")) == 1
        assert _total(await _get(client, t, path="axb")) == 2
        assert _total(await _get(client, t, path="a_b0")) == 1

    async def test_trash_purged_zombie_reference_excluded(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "Dev/ok.jpg")
        await _insert(db_path, uid, "alice", "Dev/deleted.jpg", deleted_at="2024-01-01T00:00:00")
        await _insert(db_path, uid, "alice", "Dev/purged.jpg", purged_at="2024-01-01T00:00:00")
        await _insert(db_path, uid, "alice", ".trash/Dev/zombie.jpg")
        await _insert(db_path, uid, "alice", "Dev/ref.jpg", is_reference=1)

        assert _total(await _get(client, t)) == 1
        assert _total(await _get(client, t, path="Dev")) == 1

    async def test_root_includes_custom_paths(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "Dev/ok.jpg")
        await _insert(db_path, uid, "alice", "x.jpg", file_path="/mnt/custom/Camera/x.jpg")

        data = await _get(client, t, recursive="false")
        assert _total(data) == 2
        assert data["scope"]["includes_custom_paths"] is True
        assert data["scope"]["recursive"] is True  # root ignores recursive
        sub = await _get(client, t, path="Dev")
        assert sub["scope"]["includes_custom_paths"] is False
        assert _total(sub) == 1

    @pytest.mark.parametrize(
        "path, detail",
        [
            ("a/../b", "路径格式无效"),
            ("..", "路径格式无效"),
            ("a//b", "路径格式无效"),
            ("a/./b", "路径格式无效"),
            ("a\x00b", "路径包含非法字符"),
            ("a\\b", "路径包含非法字符"),
            ("a" * 1025, "路径过长"),
        ],
    )
    async def test_invalid_path_400(self, client, db_path, path, detail):
        _, t = await _create_user(db_path, "alice")
        resp = await client.get(URL, params={"path": path}, headers=_auth(t))
        assert resp.status_code == 400
        assert resp.json()["detail"] == detail

    @pytest.mark.parametrize("top_n", [0, 51])
    async def test_invalid_top_n_422(self, client, db_path, top_n):
        _, t = await _create_user(db_path, "alice")
        resp = await client.get(URL, params={"top_n": top_n}, headers=_auth(t))
        assert resp.status_code == 422

    async def test_invalid_tz_422_detail(self, client, db_path):
        _, t = await _create_user(db_path, "alice")
        for tz in ("Not/AZone", "../etc/passwd", "Asia/Shang hai"):
            resp = await client.get(URL, params={"tz": tz}, headers=_auth(t))
            assert resp.status_code == 422
            detail = resp.json()["detail"]
            assert detail["code"] == "invalid_tz"
            assert isinstance(detail["message"], str) and detail["message"]

    async def test_invalid_recursive_422(self, client, db_path):
        _, t = await _create_user(db_path, "alice")
        resp = await client.get(URL, params={"recursive": "abc"}, headers=_auth(t))
        assert resp.status_code == 422

    async def test_nonexistent_path_200_all_zero(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "Dev/ok.jpg")
        data = await _get(client, t, path="NoSuch/Dir")
        assert data["overview"]["total"] == {"count": 0, "bytes": 0}
        for dist in _all_distributions(data):
            assert dist["total"] == 0
            assert all(b["count"] == 0 for b in dist["buckets"])


# ---------------------------------------------------------------------------
# Overview (AC8–AC10)
# ---------------------------------------------------------------------------


class TestOverview:
    async def _seed_ac8(self, db_path):
        uid, t = await _create_user(db_path, "alice")
        u = (uid, "alice")
        await _insert(db_path, *u, "D/1.jpg", file_size=100)
        await _insert(db_path, *u, "D/2.JPG", file_size=200)
        await _insert(db_path, *u, "D/3.jpeg", file_size=300)
        await _insert(db_path, *u, "D/4.heic", file_size=400, is_motion_photo=1)
        await _insert(db_path, *u, "D/5.dng", file_size=5000, mime_type=None)
        await _insert(db_path, *u, "D/6.mp4", file_size=60000)
        await _insert(db_path, *u, "D/7.mp4", file_size=70000)
        await _insert(db_path, *u, "D/notes.txt", file_size=7, media_type="image")
        return t

    async def test_categories_and_formats_ac8(self, client, db_path):
        t = await self._seed_ac8(db_path)
        data = await _get(client, t)
        cats = data["overview"]["categories"]
        assert [b["key"] for b in cats["buckets"]] == ["photo", "video", "other"]
        assert _counts(cats) == {"photo": 5, "video": 2, "other": 1}
        assert [b["label"] for b in cats["buckets"]] == ["照片", "视频", "其他"]
        _assert_tenths([b["share"] for b in cats["buckets"]] + [cats["unknown_share"]])

        formats = data["overview"]["formats"]
        assert _counts(formats) == {"jpeg": 3, "heic": 1, "raw": 1, "mp4": 2, "misc": 1}
        assert [b["key"] for b in formats["buckets"]] == ["jpeg", "heic", "raw", "mp4", "misc"]
        raw = next(b for b in formats["buckets"] if b["key"] == "raw")
        assert raw["children"] == [{
            "key": "dng", "label": "DNG", "count": 1, "share": 12.5,
            "bytes": 5000, "bytes_share": pct(5000, 136007),
        }]
        misc = next(b for b in formats["buckets"] if b["key"] == "misc")
        assert [c["key"] for c in misc["children"]] == ["txt"]
        assert "children" not in next(b for b in formats["buckets"] if b["key"] == "jpeg")
        assert formats["unknown"] == 0
        _assert_tenths([b["share"] for b in formats["buckets"]])

    async def test_bytes_ac9(self, client, db_path):
        t = await self._seed_ac8(db_path)
        data = await _get(client, t)
        total_bytes = 100 + 200 + 300 + 400 + 5000 + 60000 + 70000 + 7
        assert data["overview"]["total"] == {"count": 8, "bytes": total_bytes}
        cats = data["overview"]["categories"]
        assert cats["total_bytes"] == total_bytes
        assert {b["key"]: b["bytes"] for b in cats["buckets"]} == {
            "photo": 6000, "video": 130000, "other": 7,
        }
        _assert_tenths([b["bytes_share"] for b in cats["buckets"]])
        formats = data["overview"]["formats"]
        assert formats["total_bytes"] == total_bytes
        assert {b["key"]: b["bytes"] for b in formats["buckets"]} == {
            "jpeg": 600, "heic": 400, "raw": 5000, "mp4": 130000, "misc": 7,
        }
        _assert_tenths([b["bytes_share"] for b in formats["buckets"]])

    async def test_motion_photo_ac10(self, client, db_path):
        t = await self._seed_ac8(db_path)
        data = await _get(client, t)
        assert data["overview"]["motion_photo_count"] == 1
        assert _counts(data["overview"]["categories"])["video"] == 2

    async def test_zero_bytes(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "D/empty.jpg", file_size=0)
        cats = (await _get(client, t))["overview"]["categories"]
        assert [b["bytes_share"] for b in cats["buckets"]] == [0.0, 0.0, 0.0]
        assert cats["buckets"][0]["share"] == 100.0


def pct(value: int, total: int) -> float:
    return round(value * 100 / total, 1)


# ---------------------------------------------------------------------------
# Capture time (AC11–AC15)
# ---------------------------------------------------------------------------


class TestCaptureTime:
    async def test_local_photo_ac11(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(
            db_path, uid, "alice", "D/p.jpg",
            capture_time="2025-01-15T23:30:00", capture_time_kind="local",
            exif_time="2025-01-15T23:30:00+08:00",
        )
        for tz in ("UTC", "Asia/Shanghai", "America/New_York"):
            data = await _get(client, t, tz=tz)
            assert _counts(data["photo"]["weekday"])["wed"] == 1
            assert _counts(data["photo"]["hour"])["23"] == 1
            assert data["photo"]["weekday"]["meta"] == {"local": 1, "utc_converted": 0}

    async def test_utc_video_ac12(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(
            db_path, uid, "alice", "D/v.mp4",
            capture_time="2025-01-15T15:30:00", capture_time_kind="utc",
        )
        data = await _get(client, t, tz="Asia/Shanghai")
        assert data["scope"]["tz"] == "Asia/Shanghai"
        assert data["scope"]["tz_source"] == "request"
        assert _counts(data["video"]["weekday"])["wed"] == 1
        assert _counts(data["video"]["hour"])["23"] == 1
        assert data["video"]["hour"]["meta"] == {"local": 0, "utc_converted": 1}

        data = await _get(client, t, tz="UTC")
        assert _counts(data["video"]["weekday"])["wed"] == 1
        assert _counts(data["video"]["hour"])["15"] == 1

    async def test_utc_1988_shanghai_dst(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(
            db_path, uid, "alice", "D/old.mp4",
            capture_time="1988-07-01T00:00:00", capture_time_kind="utc",
        )
        data = await _get(client, t, tz="Asia/Shanghai")
        assert _counts(data["video"]["hour"])["09"] == 1

    async def test_config_default_timezone_wins(self, client, db_path):
        os.environ["PHOTOVAULT_DEFAULT_TIMEZONE"] = "UTC"
        reset_settings()
        uid, t = await _create_user(db_path, "alice")
        await _insert(
            db_path, uid, "alice", "D/v.mp4",
            capture_time="2025-01-15T15:30:00", capture_time_kind="utc",
        )
        data = await _get(client, t, tz="Asia/Shanghai")
        assert data["scope"]["tz"] == "UTC"
        assert data["scope"]["tz_source"] == "config"
        assert _counts(data["video"]["hour"])["15"] == 1

    async def test_scope_tz_always_present(self, client, db_path):
        _, t = await _create_user(db_path, "alice")
        data = await _get(client, t)
        assert data["scope"]["tz"]
        assert data["scope"]["tz_source"] == "server"

    async def test_missing_time_is_unknown_ac13(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "D/p1.jpg", created_at="2024-05-05T10:00:00")
        await _insert(
            db_path, uid, "alice", "D/p2.jpg",
            capture_time="2024-05-06T08:00:00", capture_time_kind="local",
        )
        await _insert(db_path, uid, "alice", "D/v1.mp4", created_at="2024-05-05T10:00:00")
        data = await _get(client, t, tz="UTC")
        for cat, total, unknown in (("photo", 2, 1), ("video", 1, 1)):
            for name in ("weekday", "hour"):
                dist = data[cat][name]
                assert dist["total"] == total
                assert dist["unknown"] == unknown
                assert sum(b["count"] for b in dist["buckets"]) + dist["unknown"] == total
            assert data[cat]["monthly"]["unknown"] == unknown

    async def test_out_of_range_years_ac14(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        future = datetime.now(timezone.utc).year + 5
        await _insert(
            db_path, uid, "alice", "D/p1.jpg",
            capture_time="1800-06-01T10:00:00", capture_time_kind="local",
        )
        await _insert(
            db_path, uid, "alice", "D/p2.jpg",
            capture_time=f"{future}-06-01T10:00:00", capture_time_kind="local",
        )
        await _insert(
            db_path, uid, "alice", "D/v1.mp4",
            capture_time="1800-06-01T10:00:00", capture_time_kind="utc",
        )
        await _insert(
            db_path, uid, "alice", "D/p3.jpg",
            capture_time="2025-13-45T10:00:00", capture_time_kind="local",
        )
        data = await _get(client, t, tz="UTC")
        assert data["photo"]["weekday"]["unknown"] == 3
        assert data["photo"]["hour"]["unknown"] == 3
        assert data["photo"]["monthly"]["unknown"] == 3
        assert data["photo"]["monthly"]["buckets"] == []
        assert data["video"]["hour"]["unknown"] == 1
        assert data["photo"]["weekday"]["meta"] == {"local": 0, "utc_converted": 0}

    async def test_fixed_buckets_ac15(self, client, db_path):
        _, t = await _create_user(db_path, "alice")
        data = await _get(client, t)
        for cat in ("photo", "video"):
            wd = data[cat]["weekday"]["buckets"]
            assert [b["key"] for b in wd] == ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
            assert [b["label"] for b in wd] == ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
            hr = data[cat]["hour"]["buckets"]
            assert [b["key"] for b in hr] == [f"{h:02d}" for h in range(24)]
            assert hr[0]["label"] == "0时" and hr[23]["label"] == "23时"
            assert all(b["count"] == 0 for b in wd + hr)


# ---------------------------------------------------------------------------
# Photo metrics (AC16–AC22)
# ---------------------------------------------------------------------------


class TestPhotoMetrics:
    async def test_iso_ac16(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        for i, iso in enumerate([50, 51, 100, 12799, 12800, None, 0]):
            await _insert(db_path, uid, "alice", f"D/{i}.jpg", iso=iso)
        dist = (await _get(client, t))["photo"]["iso"]
        c = _counts(dist)
        assert c["iso_le_50"] == 1
        assert c["iso_51_100"] == 2
        assert c["iso_6401_12799"] == 1
        assert c["iso_ge_12800"] == 1
        assert dist["unknown"] == 2
        assert dist["buckets"][0]["range"] == "(0, 50]"

    async def test_focal_ac17(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        u = (uid, "alice")
        await _insert(db_path, *u, "D/a.jpg", focal_35mm=24, focal_35mm_source="exif", focal_actual=6.9)
        await _insert(db_path, *u, "D/b.jpg", focal_actual=50, focal_length=50)
        await _insert(db_path, *u, "D/c.jpg", focal_35mm=16, focal_35mm_source="exif")
        await _insert(db_path, *u, "D/d.jpg", focal_35mm=16.1, focal_35mm_source="derived")
        await _insert(db_path, *u, "D/e.jpg", focal_35mm=200, focal_35mm_source="exif")
        await _insert(db_path, *u, "D/f.jpg", focal_35mm=200.1, focal_35mm_source="exif")
        dist = (await _get(client, t))["photo"]["focal_length"]
        c = _counts(dist)
        assert c["fl_17_24"] == 2
        assert c["fl_36_50"] == 0
        assert c["fl_le_16"] == 1
        assert c["fl_136_200"] == 1
        assert c["fl_gt_200"] == 1
        assert dist["unknown"] == 1
        assert dist["meta"] == {"by_exif": 4, "by_derived": 1}

    async def test_aperture_ac18(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        values = [1.4, 1.59, 1.6, 1.8, 2.2, 2.8, 16, 22, None, 0, 1.9, 13.5]
        for i, f in enumerate(values):
            await _insert(db_path, uid, "alice", f"D/{i}.jpg", f_number=f)
        dist = (await _get(client, t))["photo"]["aperture"]
        assert _counts(dist) == {
            "ap_le_1_4": 2, "ap_1_8": 2, "ap_2": 2, "ap_2_8": 1, "ap_4": 0,
            "ap_5_6": 0, "ap_8": 0, "ap_11": 0, "ap_16_plus": 3,
        }
        assert dist["unknown"] == 2

    async def test_shutter_ac19(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        values = [1 / 8000, 1 / 4000, 1 / 2000, 1 / 1984, 1 / 30, 0.5, 1, 2, None]
        for i, s in enumerate(values):
            await _insert(db_path, uid, "alice", f"D/{i}.jpg", exposure_time=s)
        dist = (await _get(client, t))["photo"]["shutter"]
        c = _counts(dist)
        assert c["ss_le_1_4000"] == 2
        assert c["ss_1_4000_1_2000"] == 1
        assert c["ss_1_2000_1_1000"] == 1
        assert c["ss_1_60_1_30"] == 1
        assert c["ss_1_30_1"] == 2
        assert c["ss_gt_1"] == 1
        assert dist["unknown"] == 1

    async def test_camera_ranking_ac20(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        n = 0
        # 12 distinct models; model i gets 13 - i photos (13 .. 2).
        for i in range(12):
            for _ in range(13 - i):
                n += 1
                await _insert(db_path, uid, "alice", f"D/{n}.jpg",
                              camera_make="Maker", camera_model=f"Model {i:02d}")
        await _insert(db_path, uid, "alice", "D/r5.jpg", camera_make="Canon", camera_model="Canon EOS R5")
        for make in ("Apple", "apple ", "Apple"):
            n += 1
            await _insert(db_path, uid, "alice", f"D/a{n}.jpg", camera_make=make, camera_model="iPhone 15 Pro")
        await _insert(db_path, uid, "alice", "D/none.jpg")
        await _insert(db_path, uid, "alice", "D/make-only.jpg", camera_make="Sony")

        dist = (await _get(client, t, top_n=10))["photo"]["camera"]
        assert dist["kind"] == "ranking"
        assert len(dist["buckets"]) == 10
        # 14 entries: Maker Model 00..11 (13..2), Apple (3), Canon (1).
        assert [b["label"] for b in dist["buckets"]] == [f"Maker Model {i:02d}" for i in range(10)]
        assert dist["other"] == 3 + 2 + 3 + 1  # Model 10, Model 11, Apple, Canon
        assert dist["unknown"] == 2  # no camera at all + make only
        assert dist["total"] == n + 1 + 2
        _assert_tenths([b["share"] for b in dist["buckets"]] + [dist["other_share"], dist["unknown_share"]])

        full = (await _get(client, t, top_n=50))["photo"]["camera"]
        labels = [b["label"] for b in full["buckets"]]
        assert len(full["buckets"]) == 14
        assert full["other"] == 0
        assert "Canon EOS R5" in labels
        assert "Canon Canon EOS R5" not in labels
        apple = [b for b in full["buckets"] if b["key"] == "apple iphone 15 pro"]
        assert len(apple) == 1
        assert apple[0]["count"] == 3 and apple[0]["label"] == "Apple iPhone 15 Pro"

    async def test_camera_twelve_models_other_is_remaining_two(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        n = 0
        for i in range(12):
            for _ in range(13 - i):
                n += 1
                await _insert(db_path, uid, "alice", f"D/{n}.jpg",
                              camera_make="Maker", camera_model=f"Model {i:02d}")
        await _insert(db_path, uid, "alice", "D/none.jpg")
        dist = (await _get(client, t))["photo"]["camera"]
        assert [b["label"] for b in dist["buckets"]] == [f"Maker Model {i:02d}" for i in range(10)]
        assert dist["other"] == 3 + 2  # Model 10 and Model 11
        assert dist["unknown"] == 1

        one = (await _get(client, t, top_n=1))["photo"]["camera"]
        assert len(one["buckets"]) == 1
        assert one["other"] == n - 13
        assert one["unknown"] == 1

    async def test_lens_ranking_ac21(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        n = 0
        for i in range(12):
            for _ in range(13 - i):
                n += 1
                await _insert(db_path, uid, "alice", f"D/{n}.jpg", lens_model=f"Lens {i:02d}")
        await _insert(db_path, uid, "alice", "D/x1.jpg", lens_model="lens 00")
        await _insert(db_path, uid, "alice", "D/none.jpg")
        dist = (await _get(client, t))["photo"]["lens"]
        assert len(dist["buckets"]) == 10
        assert dist["buckets"][0]["key"] == "lens 00"
        assert dist["buckets"][0]["count"] == 14
        assert dist["buckets"][0]["label"] == "Lens 00"
        assert dist["other"] == 3 + 2
        assert dist["unknown"] == 1

    async def test_trend_ac22(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        u = (uid, "alice")
        await _insert(db_path, *u, "D/a.jpg", capture_time="2023-11-03T10:00:00", capture_time_kind="local")
        await _insert(db_path, *u, "D/b.jpg", capture_time="2023-11-20T10:00:00", capture_time_kind="local")
        await _insert(db_path, *u, "D/c.jpg", capture_time="2024-02-01T10:00:00", capture_time_kind="local")
        await _insert(db_path, *u, "D/d.jpg")
        data = await _get(client, t, tz="UTC")
        monthly = data["photo"]["monthly"]
        assert monthly["kind"] == "series"
        assert [b["key"] for b in monthly["buckets"]] == ["2023-11", "2023-12", "2024-01", "2024-02"]
        assert [b["count"] for b in monthly["buckets"]] == [2, 0, 0, 1]
        assert monthly["buckets"][0]["label"] == "2023年11月"
        assert monthly["unknown"] == 1
        yearly = data["photo"]["yearly"]
        assert [(b["key"], b["count"]) for b in yearly["buckets"]] == [("2023", 2), ("2024", 1)]
        assert yearly["unknown"] == 1


# ---------------------------------------------------------------------------
# Video metrics (AC23–AC24)
# ---------------------------------------------------------------------------


class TestVideoMetrics:
    async def test_duration_ac23(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        for i, d in enumerate([0, 9.9, 10, 59.9, 60, 600, None]):
            await _insert(db_path, uid, "alice", f"D/{i}.mp4", duration_sec=d)
        dist = (await _get(client, t))["video"]["duration"]
        assert _counts(dist) == {
            "d_lt_10": 1, "d_10_30": 1, "d_30_60": 1, "d_60_180": 1,
            "d_180_600": 0, "d_ge_600": 1,
        }
        assert dist["unknown"] == 2
        assert [b["range"] for b in dist["buckets"]] == [
            "(0, 10)", "[10, 30)", "[30, 60)", "[60, 180)", "[180, 600)", "[600, ∞)",
        ]

    async def test_shared_trend_axis_and_camera_ac24(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        u = (uid, "alice")
        await _insert(db_path, *u, "D/p.jpg", capture_time="2023-11-10T10:00:00", capture_time_kind="local")
        # UTC 2024-01-31T20:00 is 2024-02-01 04:00 in Asia/Shanghai.
        await _insert(db_path, *u, "D/v.mp4", capture_time="2024-01-31T20:00:00", capture_time_kind="utc",
                      camera_make="Apple", camera_model="iPhone 15 Pro")
        await _insert(db_path, *u, "D/android.mp4")
        data = await _get(client, t, tz="Asia/Shanghai")

        months = ["2023-11", "2023-12", "2024-01", "2024-02"]
        assert [b["key"] for b in data["photo"]["monthly"]["buckets"]] == months
        assert [b["key"] for b in data["video"]["monthly"]["buckets"]] == months
        assert [b["count"] for b in data["video"]["monthly"]["buckets"]] == [0, 0, 0, 1]
        assert [b["key"] for b in data["video"]["yearly"]["buckets"]] == ["2023", "2024"]
        assert data["video"]["monthly"]["unknown"] == 1
        assert _counts(data["video"]["weekday"])["thu"] == 1  # 2024-02-01 local

        camera = data["video"]["camera"]
        assert [b["label"] for b in camera["buckets"]] == ["Apple iPhone 15 Pro"]
        assert camera["unknown"] == 1
        assert camera["other"] == 0

        data_utc = await _get(client, t, tz="UTC")
        assert [b["count"] for b in data_utc["video"]["monthly"]["buckets"]] == [0, 0, 1]


# ---------------------------------------------------------------------------
# Response structure (§6.2, AC31), notices and pending
# ---------------------------------------------------------------------------


class TestResponseStructure:
    async def test_zero_files_full_shape(self, client, db_path):
        _, t = await _create_user(db_path, "alice")
        data = await _get(client, t)
        assert data["schema_version"] == 1
        assert set(data) == {
            "schema_version", "scope", "generated_at", "pending_metadata_count",
            "notices", "overview", "photo", "video",
        }
        assert set(data["photo"]) == PHOTO_KEYS
        assert set(data["video"]) == VIDEO_KEYS
        sizes = {k: len(v["buckets"]) for k, v in data["photo"].items()}
        assert sizes == {
            "weekday": 7, "hour": 24, "iso": 10, "focal_length": 8, "aperture": 9,
            "shutter": 10, "scene_ev": 27, "camera": 0, "lens": 0, "monthly": 0, "yearly": 0,
        }
        vsizes = {k: len(v["buckets"]) for k, v in data["video"].items()}
        assert vsizes == {
            "weekday": 7, "hour": 24, "duration": 6, "camera": 0, "monthly": 0, "yearly": 0,
        }
        assert len(data["overview"]["categories"]["buckets"]) == 3
        assert data["overview"]["formats"]["buckets"] == []
        datetime.fromisoformat(data["generated_at"])

    async def test_zero_videos_with_photos(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "D/p.jpg", capture_time="2024-03-01T10:00:00",
                      capture_time_kind="local")
        data = await _get(client, t)
        assert set(data["video"]) == VIDEO_KEYS
        assert data["video"]["duration"]["total"] == 0
        assert len(data["video"]["monthly"]["buckets"]) == len(data["photo"]["monthly"]["buckets"]) == 1
        assert len(data["video"]["yearly"]["buckets"]) == len(data["photo"]["yearly"]["buckets"]) == 1

    async def test_zero_photos_with_videos(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "D/v.mp4", duration_sec=5)
        data = await _get(client, t)
        assert set(data["photo"]) == PHOTO_KEYS
        assert data["photo"]["iso"]["total"] == 0
        assert len(data["photo"]["iso"]["buckets"]) == 10

    async def test_kinds_and_optional_fields_ac31(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "D/p.jpg", camera_make="Apple", camera_model="iPhone")
        await _insert(db_path, uid, "alice", "D/v.mp4")
        data = await _get(client, t)
        for dist in _all_distributions(data):
            assert {"kind", "buckets", "unknown", "unknown_share", "total"} <= set(dist)
            other = dist.get("other") or 0
            assert sum(b["count"] for b in dist["buckets"]) + other + dist["unknown"] == dist["total"]
        kinds = {f"photo.{k}": v["kind"] for k, v in data["photo"].items()}
        kinds.update({f"video.{k}": v["kind"] for k, v in data["video"].items()})
        for key, kind in kinds.items():
            name = key.split(".", 1)[1]
            if name in ("camera", "lens"):
                assert kind == "ranking", key
                assert "other" in data[key.split(".")[0]][name]
                assert "other_share" in data[key.split(".")[0]][name]
            elif name in ("monthly", "yearly"):
                assert kind == "series", key
            else:
                assert kind == "distribution", key
        # Optional fields are omitted where they do not apply.
        assert "range" not in data["photo"]["weekday"]["buckets"][0]
        assert "bytes" not in data["photo"]["iso"]["buckets"][0]
        assert "other" not in data["photo"]["iso"]
        assert "total_bytes" not in data["photo"]["iso"]
        assert "meta" not in data["photo"]["iso"]
        assert "meta" in data["photo"]["weekday"]
        assert "range" in data["photo"]["iso"]["buckets"][0]
        # Only photo.scene_ev carries a summary.
        assert all("summary" not in d for k, d in data["photo"].items() if k != "scene_ev")
        assert all("summary" not in d for d in data["video"].values())
        assert "summary" not in data["overview"]["categories"]
        assert "summary" in data["photo"]["scene_ev"]

    async def test_ffprobe_missing_notice(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "Photos/p.jpg")
        await _insert(db_path, uid, "alice", "Videos/v.mp4")
        with patch("app.services.media_metadata.ffprobe_path", return_value=None):
            with_video = await _get(client, t)
            no_video = await _get(client, t, path="Photos")
        assert with_video["notices"] == [{
            "code": "ffprobe_missing",
            "message": "服务器未安装 ffprobe，部分视频的时长和拍摄时间暂不可用",
        }]
        assert no_video["notices"] == []
        available = await _get(client, t)
        assert available["notices"] == []

    async def test_pending_counts_no_ffprobe_only_when_available(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "D/done.jpg")
        await _insert(db_path, uid, "alice", "D/new.jpg", meta_version=None, meta_status=None)
        await _insert(db_path, uid, "alice", "D/v.mp4", meta_status="no_ffprobe")
        await _insert(db_path, uid, "alice", "D/t.mp4", meta_version=None, meta_status="timeout")
        assert (await _get(client, t))["pending_metadata_count"] == 3
        with patch("app.services.media_metadata.ffprobe_path", return_value=None):
            assert (await _get(client, t))["pending_metadata_count"] == 2

    async def test_sql_error_returns_500_and_logs(self, client, db_path, caplog):
        _, t = await _create_user(db_path, "alice")

        async def _boom(*args, **kwargs):
            raise sqlite3.OperationalError("database is locked")

        with patch.object(media_stats_service.MediaStatsService, "compute", _boom):
            with caplog.at_level(logging.ERROR, logger="photovault.api.stats"):
                resp = await client.get(URL, params={"path": "Dev"}, headers=_auth(t))
        assert resp.status_code == 500
        assert resp.json()["detail"] == "统计查询失败，请稍后重试"
        records = [r for r in caplog.records if r.name == "photovault.api.stats"]
        assert records and records[0].levelno == logging.ERROR
        assert "path='Dev'" in records[0].getMessage()
        assert records[0].exc_info is not None

    async def test_temp_tables_dropped(self, db_path):
        uid, _ = await _create_user(db_path, "alice")
        await _insert(db_path, uid, "alice", "D/p.jpg")
        async with aiosqlite.connect(db_path) as db:
            service = media_stats_service.MediaStatsService(db, get_settings().media_root)
            await service.compute(uid, "alice", "", True, [("0000-01-01T00:00:00", "+0 seconds")],
                                  True, 10, 2100)
            cursor = await db.execute("SELECT name FROM sqlite_temp_master WHERE type = 'table'")
            assert await cursor.fetchall() == []


# ---------------------------------------------------------------------------
# Scene EV (scene-ev design §4.3, §7; AC4–AC12)
# ---------------------------------------------------------------------------

# (iso, f_number, exposure_time) -> scene EV 13, 4, 12, 17, −11, 22 (requirements AC4).
_AC4_PHOTOS = [
    (100, 8, 1 / 128),
    (400, 4, 1 / 4),
    (200, 8, 1 / 128),
    (50, 16, 1 / 256),
    (6400, 1, 32),
    (25, 32, 1 / 1024),
]
_AC4_COUNTS = {"ev_13": 1, "ev_4": 1, "ev_12": 1, "ev_17": 1, "ev_le_m6": 1, "ev_ge_20": 1}

# Missing or illegal inputs (requirements AC5).
_AC5_BAD = [
    (None, 8, 1 / 128),
    (100, None, 1 / 128),
    (100, 8, None),
    (0, 8, 1 / 128),
    (100, 0, 1 / 128),
    (100, 8, 0),
    (100, 8, -1),
]


async def _insert_exposures(db_path, uid, username, triples, prefix="D/ev", ext="jpg"):
    for i, (iso, f, t) in enumerate(triples):
        await _insert(db_path, uid, username, f"{prefix}{i}.{ext}",
                      iso=iso, f_number=f, exposure_time=t)


def _expected_key(x: float) -> str:
    return next(b.key for b in SCENE_EV_SPEC.buckets if b.upper is None or x < b.upper)


class TestSceneEv:
    def _assert_ac4(self, dist, *, unknown=0):
        assert dist["kind"] == "distribution"
        counts = _counts(dist)
        assert len(counts) == 27
        assert {k: v for k, v in counts.items() if v} == _AC4_COUNTS
        assert dist["unknown"] == unknown
        assert dist["total"] == 6 + unknown
        summary = dist["summary"]
        assert set(summary) == {"samples", "median", "mean"}
        assert summary["samples"] == 6
        assert summary["median"] == pytest.approx(12.5, abs=1e-9)
        assert summary["mean"] == pytest.approx(9.5, abs=1e-9)
        assert summary["samples"] + dist["unknown"] == dist["total"]
        assert summary["samples"] == sum(counts.values())

    async def test_buckets_and_summary_ac4(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert_exposures(db_path, uid, "alice", _AC4_PHOTOS)
        dist = (await _get(client, t))["photo"]["scene_ev"]
        self._assert_ac4(dist)
        assert all("range" in b for b in dist["buckets"])
        assert dist["buckets"][0]["label"] == "≤−6"
        assert dist["buckets"][-1]["label"] == "≥20"

    async def test_missing_or_illegal_is_unknown_ac5(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert_exposures(db_path, uid, "alice", _AC4_PHOTOS)
        await _insert_exposures(db_path, uid, "alice", _AC5_BAD, prefix="D/bad")
        dist = (await _get(client, t))["photo"]["scene_ev"]
        self._assert_ac4(dist, unknown=7)
        assert dist["total"] == 13

    async def test_video_and_other_excluded_ac6(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert_exposures(db_path, uid, "alice", _AC4_PHOTOS)
        await _insert(db_path, uid, "alice", "D/v.mp4", iso=100, f_number=8, exposure_time=1 / 128)
        await _insert(db_path, uid, "alice", "D/n.txt", iso=100, f_number=8, exposure_time=1 / 128)
        data = await _get(client, t)
        assert _counts(data["overview"]["categories"]) == {"photo": 6, "video": 1, "other": 1}
        self._assert_ac4(data["photo"]["scene_ev"])
        assert "scene_ev" not in data["video"]

    async def test_no_valid_samples_ac8(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert_exposures(db_path, uid, "alice", _AC5_BAD[:3], prefix="Bad/x")
        dist = (await _get(client, t, path="Bad"))["photo"]["scene_ev"]
        assert dist["total"] == 3
        assert dist["unknown"] == 3
        summary = dist["summary"]
        assert "median" in summary and "mean" in summary
        assert summary["samples"] == 0
        assert summary["median"] is None
        assert summary["mean"] is None

        empty = (await _get(client, t, path="NoSuch"))["photo"]["scene_ev"]
        assert empty["total"] == 0 and empty["unknown"] == 0
        assert empty["summary"] == {"samples": 0, "median": None, "mean": None}

    async def test_scope_and_isolation_ac9(self, client, db_path):
        alice, ta = await _create_user(db_path, "alice")
        bob, _ = await _create_user(db_path, "bob")
        await _insert_exposures(db_path, alice, "alice", _AC4_PHOTOS, prefix="Cam/ev")
        good = (100, 8, 1 / 128)
        await _insert_exposures(db_path, bob, "bob", [good], prefix="Cam/bob")
        await _insert(db_path, alice, "alice", "Cam/deleted.jpg", iso=100, f_number=8,
                      exposure_time=1 / 128, deleted_at="2024-01-01T00:00:00")
        await _insert_exposures(db_path, alice, "alice", [good], prefix="Other/out")
        await _insert_exposures(db_path, alice, "alice", [good], prefix="Cam/Sub/deep")

        direct = (await _get(client, ta, path="Cam", recursive="false"))["photo"]["scene_ev"]
        self._assert_ac4(direct)

        recursive = (await _get(client, ta, path="Cam"))["photo"]["scene_ev"]
        assert recursive["summary"]["samples"] == 7
        assert _counts(recursive)["ev_13"] == 2

    async def test_sql_matches_pure_function_ac10(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        rng = random.Random(20261012)
        triples = [
            (rng.choice([50, 100, 200, 400, 800, 1600, 3200, 6400, 12800]),
             round(rng.uniform(1.0, 22.0), 2),
             2 ** rng.uniform(-13, 5))
            for _ in range(30)
        ]
        await _insert_exposures(db_path, uid, "alice", triples)
        dist = (await _get(client, t))["photo"]["scene_ev"]

        values = [ev100_value(*x) for x in triples]
        assert all(v is not None for v in values)
        expected_counts: dict[str, int] = {}
        for v in values:
            key = _expected_key(v)
            expected_counts[key] = expected_counts.get(key, 0) + 1
        assert len(expected_counts) >= 5
        assert {k: c for k, c in _counts(dist).items() if c} == expected_counts
        assert dist["unknown"] == 0
        assert dist["summary"]["samples"] == len(values)
        assert dist["summary"]["median"] == pytest.approx(statistics.median(values), abs=1e-9)
        assert dist["summary"]["mean"] == pytest.approx(statistics.fmean(values), abs=1e-9)

    async def test_odd_sample_count_median(self, client, db_path):
        uid, t = await _create_user(db_path, "alice")
        await _insert_exposures(db_path, uid, "alice", _AC4_PHOTOS[:3])  # 13, 4, 12
        summary = (await _get(client, t))["photo"]["scene_ev"]["summary"]
        assert summary["samples"] == 3
        assert summary["median"] == pytest.approx(12, abs=1e-9)
        assert summary["mean"] == pytest.approx(29 / 3, abs=1e-9)

    async def test_scene_ev_without_sqlite_math_functions(self, db_path):
        """AC12: SQLite math functions are never used (they raise here)."""
        def _no_math(*_):
            raise RuntimeError("math functions unavailable")

        async def _disable_math(db):
            for name, n in (("log2", 1), ("log", 1), ("log", 2), ("ln", 1), ("log10", 1),
                            ("pow", 2), ("power", 2), ("exp", 1)):
                await db.create_function(name, n, _no_math)

        uid, t = await _create_user(db_path, "alice")
        await _insert_exposures(db_path, uid, "alice", _AC4_PHOTOS)
        transport = ASGITransport(app=_make_app(db_path, on_connect=_disable_math))
        async with AsyncClient(transport=transport, base_url="http://testserver") as c:
            resp = await c.get(URL, headers=_auth(t))
        assert resp.status_code == 200, resp.text
        self._assert_ac4(resp.json()["photo"]["scene_ev"])

    def test_scene_ev_sql_has_no_math_functions(self):
        pattern = re.compile(r"\b(log2|log10|log|ln|pow|power|exp)\s*\(", re.I)
        sqls = [media_stats_service._CREATE_SCOPE_SQL, bucket_case_sql(SCENE_EV_SPEC)[0]]
        sqls += [
            v for k, v in vars(media_stats_service).items()
            if k.startswith("_Q_") and isinstance(v, str)
        ]
        assert len(sqls) >= 4
        for sql in sqls:
            assert not pattern.search(sql), sql
        assert SQL_EV100_FUNCTION in media_stats_service._CREATE_SCOPE_SQL
