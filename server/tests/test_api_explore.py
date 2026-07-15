"""Unit tests for the ExploreAPI endpoints (task 4.3).

Covers, against a temp SQLite DB seeded with analysis rows:

- Aggregation: people / places / scenes grouping, counts, default 人物N name,
  cover file resolution, Chinese scene name fallback.
- Detail: paginated photo lists (total/page/page_size) and thumbnail_url shape.
- Rename: PUT updates display_name; 404 when the cluster is not owned; user
  isolation (user A cannot rename or see user B's data).
- Degradation (Req 8.4): empty tables -> aggregation returns [] (200) and
  detail returns an empty paginated shape, never an error.
- Permissions (Req 6.6): GET /explore/resources works for a regular user;
  upload + reanalyze return 403 for non-admin, succeed for admin.
- Upload validation (Req 6.7): illegal extension -> 400 and an existing
  installed resource is left byte-for-byte intact; a valid upload installs.
- Reanalyze: enqueues the admin's live files.

The test app mirrors the pattern in ``test_security.py`` (httpx AsyncClient +
ASGITransport + ``dependency_overrides[get_db]`` + real JWT tokens minted via
AuthService). Resource/reanalyze endpoints read the settings singleton and open
their own DB connection, so the ``db_path`` fixture also wires the env vars
(``PHOTOVAULT_DATABASE_URL`` / ``PHOTOVAULT_STORAGE_ROOT``) and resets the
settings + analysis-queue singletons for isolation.
"""

from __future__ import annotations

import os
from pathlib import Path

import aiosqlite
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.explore import router as explore_router
from app.core.config import get_settings, reset_settings
from app.core.database import get_db, init_db
from app.services.analysis_queue import get_analysis_queue, reset_analysis_queue
from app.services.auth_service import AuthService


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_singletons():
    """Reset the settings and analysis-queue singletons around each test."""
    reset_settings()
    reset_analysis_queue()
    yield
    reset_settings()
    reset_analysis_queue()


@pytest.fixture
async def db_path(tmp_path):
    """Set up env + initialize a temp database, shared by app and helpers."""
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-explore-tests"

    reset_settings()

    path = str(tmp_path / "test.db")
    await init_db(path)

    yield path

    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    reset_settings()


def _make_app(db_path: str) -> FastAPI:
    """Build a FastAPI app exposing the explore router with get_db overridden."""
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
    app.include_router(explore_router)
    return app


@pytest.fixture
async def client(db_path):
    """Async HTTP client bound to the explore test app."""
    app = _make_app(db_path)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c


# ---------------------------------------------------------------------------
# Seeding helpers
# ---------------------------------------------------------------------------


async def _create_user(db_path: str, username: str, password: str, is_admin: bool):
    """Create a user and return (user_id, access_token)."""
    async with aiosqlite.connect(db_path) as db:
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        auth = AuthService(db)
        user = await auth.create_user(username, password, is_admin=is_admin)
        tokens = await auth.login(username, password)
    return user.id, tokens.access_token


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


async def _insert_file(
    db: aiosqlite.Connection,
    *,
    file_id: int,
    user_id: int,
    device_name: str = "Pixel",
    deleted: bool = False,
) -> None:
    await db.execute(
        """INSERT INTO file_records
           (id, user_id, file_hash, file_path, original_path,
            device_name, file_size, file_name, mime_type, media_type, deleted_at)
           VALUES (?, ?, ?, ?, '/orig', ?, 1000, ?, 'image/jpeg', 'image', ?)""",
        (
            file_id,
            user_id,
            f"hash{file_id}",
            f"/p/{file_id}.jpg",
            device_name,
            f"{file_id}.jpg",
            "2024-01-01T00:00:00" if deleted else None,
        ),
    )


async def _insert_gps(db, *, file_id, user_id, city, province="省", country="中国"):
    await db.execute(
        """INSERT INTO photo_gps
           (file_id, user_id, latitude, longitude, city, province, country)
           VALUES (?, ?, 1.0, 2.0, ?, ?, ?)""",
        (file_id, user_id, city, province, country),
    )


async def _insert_scene(db, *, file_id, user_id, label, confidence=0.9):
    await db.execute(
        """INSERT INTO photo_scenes (file_id, user_id, scene_label, confidence)
           VALUES (?, ?, ?, ?)""",
        (file_id, user_id, label, confidence),
    )


async def _insert_cluster(db, *, user_id, display_name=None, cover_face_id=None) -> int:
    cursor = await db.execute(
        """INSERT INTO face_clusters (user_id, display_name, cover_face_id, face_count)
           VALUES (?, ?, ?, 0)""",
        (user_id, display_name, cover_face_id),
    )
    return cursor.lastrowid


async def _insert_face(db, *, file_id, user_id, cluster_id, det_score=0.9) -> int:
    cursor = await db.execute(
        """INSERT INTO faces
           (file_id, user_id, cluster_id, bbox, det_score, embedding)
           VALUES (?, ?, ?, '[0,0,10,10]', ?, ?)""",
        (file_id, user_id, cluster_id, det_score, b"\x00\x00\x00\x00"),
    )
    # Keep the cluster's stored face_count in sync, exactly as FaceAnalyzer
    # does when it assigns a face to a cluster. The people aggregation query
    # gates clusters on ``face_clusters.face_count > 0`` (a stored column).
    await db.execute(
        "UPDATE face_clusters SET face_count = face_count + 1 WHERE id = ?",
        (cluster_id,),
    )
    return cursor.lastrowid


# ---------------------------------------------------------------------------
# Aggregation
# ---------------------------------------------------------------------------


class TestPeopleAggregation:
    async def test_people_grouping_default_name_and_cover(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            await _insert_file(db, file_id=1, user_id=user_id)
            await _insert_file(db, file_id=2, user_id=user_id)
            # Cluster 1: no custom name, two faces.
            c1 = await _insert_cluster(db, user_id=user_id)
            await _insert_face(db, file_id=1, user_id=user_id, cluster_id=c1)
            await _insert_face(db, file_id=2, user_id=user_id, cluster_id=c1)
            # Cluster 2: named, one face.
            c2 = await _insert_cluster(db, user_id=user_id, display_name="Bob")
            await _insert_face(db, file_id=1, user_id=user_id, cluster_id=c2)
            await db.commit()

        resp = await client.get("/explore/people", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        # Ordered by face_count DESC: c1 (2) first, c2 (1) second.
        assert data[0]["cluster_id"] == c1
        assert data[0]["face_count"] == 2
        assert data[0]["name"] == "人物1"  # default name at index 0
        assert data[0]["cover_file_id"] in (1, 2)
        assert data[1]["cluster_id"] == c2
        assert data[1]["face_count"] == 1
        assert data[1]["name"] == "Bob"

    async def test_people_excludes_deleted_photos(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            await _insert_file(db, file_id=1, user_id=user_id, deleted=True)
            c1 = await _insert_cluster(db, user_id=user_id)
            await _insert_face(db, file_id=1, user_id=user_id, cluster_id=c1)
            await db.commit()

        resp = await client.get("/explore/people", headers=_auth(token))
        assert resp.status_code == 200
        # Cluster only has faces on a deleted photo -> no visible faces.
        assert resp.json() == []


class TestPlacesAggregation:
    async def test_places_grouping_counts_and_cover(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            for fid in (1, 2, 3):
                await _insert_file(db, file_id=fid, user_id=user_id)
            await _insert_gps(db, file_id=1, user_id=user_id, city="北京")
            await _insert_gps(db, file_id=2, user_id=user_id, city="北京")
            await _insert_gps(db, file_id=3, user_id=user_id, city="上海")
            await db.commit()

        resp = await client.get("/explore/places", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert data[0]["city"] == "北京"
        assert data[0]["count"] == 2
        assert data[0]["province"] == "省"
        assert data[0]["country"] == "中国"
        assert data[0]["cover_file_id"] in (1, 2)
        assert data[1]["city"] == "上海"
        assert data[1]["count"] == 1


class TestScenesAggregation:
    async def test_scenes_grouping_and_name_fallback(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            for fid in (1, 2, 3):
                await _insert_file(db, file_id=fid, user_id=user_id)
            await _insert_scene(db, file_id=1, user_id=user_id, label="beach")
            await _insert_scene(db, file_id=2, user_id=user_id, label="beach")
            await _insert_scene(db, file_id=3, user_id=user_id, label="park")
            await db.commit()

        resp = await client.get("/explore/scenes", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert data[0]["label"] == "beach"
        assert data[0]["count"] == 2
        # No scene model installed -> name_zh falls back to the raw label.
        assert data[0]["name_zh"] == "beach"
        assert data[1]["label"] == "park"
        assert data[1]["count"] == 1


# ---------------------------------------------------------------------------
# Detail (pagination + thumbnail_url)
# ---------------------------------------------------------------------------


class TestDetailEndpoints:
    async def test_person_photos_pagination(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            await _insert_file(db, file_id=1, user_id=user_id)
            await _insert_file(db, file_id=2, user_id=user_id)
            c1 = await _insert_cluster(db, user_id=user_id)
            await _insert_face(db, file_id=1, user_id=user_id, cluster_id=c1)
            await _insert_face(db, file_id=2, user_id=user_id, cluster_id=c1)
            await db.commit()

        # First page with page_size=1 -> 1 item, total reflects all matches.
        resp = await client.get(
            f"/explore/people/{c1}",
            params={"page": 1, "page_size": 1},
            headers=_auth(token),
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["total"] == 2
        assert data["page"] == 1
        assert data["page_size"] == 1
        assert len(data["files"]) == 1
        fid = data["files"][0]["id"]
        assert data["files"][0]["thumbnail_url"] == f"/api/v1/files/thumbnail/{fid}"

        # Second page returns the remaining item.
        resp2 = await client.get(
            f"/explore/people/{c1}",
            params={"page": 2, "page_size": 1},
            headers=_auth(token),
        )
        assert resp2.status_code == 200
        assert len(resp2.json()["files"]) == 1

    async def test_place_photos(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            for fid in (1, 2):
                await _insert_file(db, file_id=fid, user_id=user_id)
                await _insert_gps(db, file_id=fid, user_id=user_id, city="北京")
            await db.commit()

        resp = await client.get("/explore/places/北京", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["total"] == 2
        assert len(data["files"]) == 2

    async def test_scene_photos(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            for fid in (1, 2):
                await _insert_file(db, file_id=fid, user_id=user_id)
                await _insert_scene(db, file_id=fid, user_id=user_id, label="beach")
            await db.commit()

        resp = await client.get("/explore/scenes/beach", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert data["total"] == 2
        assert len(data["files"]) == 2


# ---------------------------------------------------------------------------
# Rename + user isolation
# ---------------------------------------------------------------------------


class TestRenameAndIsolation:
    async def test_rename_updates_display_name(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            await _insert_file(db, file_id=1, user_id=user_id)
            c1 = await _insert_cluster(db, user_id=user_id)
            await _insert_face(db, file_id=1, user_id=user_id, cluster_id=c1)
            await db.commit()

        resp = await client.put(
            f"/explore/people/{c1}", json={"name": "小明"}, headers=_auth(token)
        )
        assert resp.status_code == 200
        assert resp.json() == {"success": True, "name": "小明"}

        # The new name is reflected in the aggregation.
        people = await client.get("/explore/people", headers=_auth(token))
        assert people.json()[0]["name"] == "小明"

    async def test_rename_missing_cluster_returns_404(self, client, db_path):
        _, token = await _create_user(db_path, "alice", "password123", False)
        resp = await client.put(
            "/explore/people/999", json={"name": "x"}, headers=_auth(token)
        )
        assert resp.status_code == 404

    async def test_user_cannot_rename_or_see_other_users_cluster(self, client, db_path):
        user_a, token_a = await _create_user(db_path, "alice", "password123", False)
        user_b, token_b = await _create_user(db_path, "bob", "password123", False)
        async with aiosqlite.connect(db_path) as db:
            await _insert_file(db, file_id=1, user_id=user_a)
            c_a = await _insert_cluster(db, user_id=user_a)
            await _insert_face(db, file_id=1, user_id=user_a, cluster_id=c_a)
            await db.commit()

        # User B cannot rename user A's cluster.
        resp = await client.put(
            f"/explore/people/{c_a}", json={"name": "hijack"}, headers=_auth(token_b)
        )
        assert resp.status_code == 404

        # User B cannot see user A's people.
        people_b = await client.get("/explore/people", headers=_auth(token_b))
        assert people_b.status_code == 200
        assert people_b.json() == []

        # User A's cluster name is untouched.
        people_a = await client.get("/explore/people", headers=_auth(token_a))
        assert people_a.json()[0]["cluster_id"] == c_a
        assert people_a.json()[0]["name"] == "人物1"


# ---------------------------------------------------------------------------
# Degradation (Req 8.4)
# ---------------------------------------------------------------------------


class TestDegradationEmptyCollections:
    async def test_aggregation_endpoints_return_empty_list(self, client, db_path):
        _, token = await _create_user(db_path, "alice", "password123", False)
        for path in ("/explore/people", "/explore/places", "/explore/scenes"):
            resp = await client.get(path, headers=_auth(token))
            assert resp.status_code == 200, path
            assert resp.json() == [], path

    async def test_detail_endpoints_return_empty_paginated_shape(self, client, db_path):
        _, token = await _create_user(db_path, "alice", "password123", False)
        cases = ["/explore/people/1", "/explore/places/北京", "/explore/scenes/beach"]
        for path in cases:
            resp = await client.get(path, headers=_auth(token))
            assert resp.status_code == 200, path
            data = resp.json()
            assert data["files"] == [], path
            assert data["total"] == 0, path
            assert data["page"] == 1, path
            assert data["page_size"] == 50, path


# ---------------------------------------------------------------------------
# Permissions (Req 6.6)
# ---------------------------------------------------------------------------


class TestResourcePermissions:
    async def test_get_resources_allowed_for_regular_user(self, client, db_path):
        _, token = await _create_user(db_path, "alice", "password123", False)
        resp = await client.get("/explore/resources", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert set(data.keys()) == {"face", "scene", "geocoding"}
        # Nothing installed in a fresh temp models_root.
        assert data["geocoding"]["installed"] is False

    async def test_upload_forbidden_for_regular_user(self, client, db_path):
        _, token = await _create_user(db_path, "alice", "password123", False)
        resp = await client.post(
            "/explore/resources/geocoding",
            files={"file": ("cities.db", b"data", "application/octet-stream")},
            headers=_auth(token),
        )
        assert resp.status_code == 403

    async def test_reanalyze_forbidden_for_regular_user(self, client, db_path):
        _, token = await _create_user(db_path, "alice", "password123", False)
        resp = await client.post(
            "/explore/reanalyze", json={}, headers=_auth(token)
        )
        assert resp.status_code == 403


# ---------------------------------------------------------------------------
# Upload validation (Req 6.7)
# ---------------------------------------------------------------------------


class TestUploadValidation:
    async def test_invalid_extension_returns_400(self, client, db_path):
        _, token = await _create_user(db_path, "admin", "adminpass", True)
        resp = await client.post(
            "/explore/resources/geocoding",
            files={"file": ("cities.txt", b"not a db", "text/plain")},
            headers=_auth(token),
        )
        assert resp.status_code == 400

    async def test_invalid_upload_does_not_overwrite_existing(self, client, db_path):
        _, token = await _create_user(db_path, "admin", "adminpass", True)

        # Pre-install a valid geocoding resource with known bytes.
        models_root = Path(get_settings().models_root)
        geo_dir = models_root / "geocoding"
        geo_dir.mkdir(parents=True, exist_ok=True)
        existing = geo_dir / "cities.db"
        original_bytes = b"ORIGINAL-VALID-CITIES-DB"
        existing.write_bytes(original_bytes)

        # Attempt an invalid upload (bad extension).
        resp = await client.post(
            "/explore/resources/geocoding",
            files={"file": ("evil.txt", b"REPLACED", "text/plain")},
            headers=_auth(token),
        )
        assert resp.status_code == 400
        # Existing installed resource is byte-for-byte intact.
        assert existing.read_bytes() == original_bytes

    async def test_valid_upload_installs_resource(self, client, db_path):
        _, token = await _create_user(db_path, "admin", "adminpass", True)

        resp = await client.post(
            "/explore/resources/geocoding",
            files={"file": ("cities.db", b"VALID-DB-CONTENT", "application/octet-stream")},
            headers=_auth(token),
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        assert body["status"]["installed"] is True

        # Reflected in the read-only status endpoint.
        status = await client.get("/explore/resources", headers=_auth(token))
        assert status.json()["geocoding"]["installed"] is True


# ---------------------------------------------------------------------------
# Reanalyze (Req 6.4 / admin)
# ---------------------------------------------------------------------------


class TestReanalyze:
    async def test_reanalyze_enqueues_admin_live_files(self, client, db_path):
        admin_id, token = await _create_user(db_path, "admin", "adminpass", True)
        async with aiosqlite.connect(db_path) as db:
            await _insert_file(db, file_id=1, user_id=admin_id)
            await _insert_file(db, file_id=2, user_id=admin_id)
            await db.commit()

        resp = await client.post("/explore/reanalyze", json={}, headers=_auth(token))
        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        assert body["queued"] >= 2

        # The files actually landed on the shared queue.
        assert get_analysis_queue().qsize() >= 2
