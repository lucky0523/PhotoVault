"""End-to-end integration test for the explore analysis pipeline (task 7).

Exercises the full data path that the concurrently-built modules must agree on,
without requiring any ML runtime or model files:

    upload/seed file_records
        -> enqueue_analysis (analysis_queue)
        -> drain queue
        -> AnalysisService.analyze_file (orchestration + path resolution + gating)
        -> injected analyzer writes analysis rows (photo_gps / faces+clusters)
        -> ExploreAPI aggregation endpoints return the analyzed data

The three analyzers are *stubbed / injected* (per the design's testing
strategy: "对分析器打桩，避免依赖真实模型"). The place stub simulates GPS
extraction + reverse geocoding by writing a ``photo_gps`` row; the face stub
simulates detection + clustering by writing ``face_clusters`` + ``faces`` rows.
This keeps the test runnable in a no-ONNX / Pillow-only environment while still
validating that the queue, orchestrator, DB schema and ExploreAPI line up.

App/auth/DB wiring mirrors ``test_api_explore.py`` (httpx AsyncClient +
ASGITransport + ``dependency_overrides[get_db]`` + real JWT tokens).
"""

from __future__ import annotations

import os
import types

import aiosqlite
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.explore import router as explore_router
from app.core.config import reset_settings
from app.core.database import get_db, init_db
from app.services.analysis_queue import (
    enqueue_analysis,
    get_analysis_queue,
    reset_analysis_queue,
)
from app.services.analysis_service import AnalysisService
from app.services.auth_service import AuthService


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


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
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-integration"
    reset_settings()

    path = str(tmp_path / "test.db")
    await init_db(path)

    yield path

    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    reset_settings()


def _make_app(db_path: str) -> FastAPI:
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
    app = _make_app(db_path)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c


def _settings_for(db_path: str):
    """Minimal settings namespace enabling every dimension for the service."""
    return types.SimpleNamespace(
        database_url=db_path,
        enable_place=True,
        enable_scene=True,
        enable_face=True,
    )


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


async def _create_user(db_path: str, username: str, password: str, is_admin=False):
    async with aiosqlite.connect(db_path) as db:
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        auth = AuthService(db)
        user = await auth.create_user(username, password, is_admin=is_admin)
        tokens = await auth.login(username, password)
    return user.id, tokens.access_token


async def _seed_file(db_path: str, *, file_id: int, user_id: int, device="Pixel"):
    async with aiosqlite.connect(db_path) as db:
        await db.execute("PRAGMA foreign_keys=ON;")
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name, mime_type, media_type)
               VALUES (?, ?, ?, ?, '/orig', ?, 1000, ?, 'image/jpeg', 'image')""",
            (file_id, user_id, f"hash{file_id}", f"/p/{file_id}.jpg", device, f"{file_id}.jpg"),
        )
        await db.commit()


# ---------------------------------------------------------------------------
# Injected analyzer stubs (simulate the real analyzers' DB writes)
# ---------------------------------------------------------------------------


class _UnavailableAnalyzer:
    """Stands in for scene/face when their models are absent."""

    available = False

    async def analyze(self, user_id, file_id, path):  # pragma: no cover
        raise AssertionError("unavailable analyzer must never be called")


class _StubPlaceAnalyzer:
    """Simulates GPS extraction + reverse geocoding by writing photo_gps.

    Mirrors PlaceAnalyzer's contract: idempotent per file (clears prior rows
    for the file before inserting), writes ``user_id`` for isolation.
    """

    available = True

    def __init__(self, db_path: str, injected):
        self._db_path = db_path
        # injected: file_id -> (lat, lon, city, province, country)
        self._injected = injected

    async def analyze(self, user_id, file_id, path):
        gps = self._injected.get(file_id)
        if gps is None:
            return
        lat, lon, city, province, country = gps
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute("PRAGMA foreign_keys=ON;")
            await db.execute("DELETE FROM photo_gps WHERE file_id = ?", (file_id,))
            await db.execute(
                """INSERT INTO photo_gps
                   (file_id, user_id, latitude, longitude, city, province, country)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (file_id, user_id, lat, lon, city, province, country),
            )
            await db.commit()


class _StubFaceAnalyzer:
    """Simulates detection + clustering by writing face_clusters + faces.

    Deliberately leaves the stored ``face_clusters.face_count`` at 0 to prove
    the people aggregation counts visible faces via ``COUNT(fa.id)`` (the
    HAVING/ORDER-BY must reference the aggregate, not the stored column).
    """

    available = True

    def __init__(self, db_path: str, cluster_by_file):
        self._db_path = db_path
        # cluster_by_file: file_id -> cluster_key (str) grouping faces together
        self._cluster_by_file = cluster_by_file

    async def analyze(self, user_id, file_id, path):
        key = self._cluster_by_file.get(file_id)
        if key is None:
            return
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute("PRAGMA foreign_keys=ON;")
            db.row_factory = aiosqlite.Row
            # Idempotency: clear this file's faces before re-inserting.
            await db.execute("DELETE FROM faces WHERE file_id = ?", (file_id,))
            # Find or create the cluster for this key (stored via display_name marker).
            marker = f"__key_{key}__"
            cur = await db.execute(
                "SELECT id FROM face_clusters WHERE user_id = ? AND display_name = ?",
                (user_id, marker),
            )
            row = await cur.fetchone()
            if row is None:
                cur = await db.execute(
                    """INSERT INTO face_clusters (user_id, display_name, face_count)
                       VALUES (?, ?, 0)""",
                    (user_id, marker),
                )
                cluster_id = cur.lastrowid
            else:
                cluster_id = row["id"]
            await db.execute(
                """INSERT INTO faces
                   (file_id, user_id, cluster_id, bbox, det_score, embedding)
                   VALUES (?, ?, ?, '[0,0,10,10]', 0.9, ?)""",
                (file_id, user_id, cluster_id, b"\x00\x00\x00\x00"),
            )
            # Note: intentionally do NOT maintain the stored face_count.
            await db.commit()


async def _drain_queue_with_service(service: AnalysisService) -> int:
    """Drain the process queue, dispatching each item through the service.

    Emulates ``analysis_worker_task`` but with an injected service so the run
    needs no ML models. Returns the number of items processed.
    """
    queue = get_analysis_queue()
    processed = 0
    while not queue.empty():
        user_id, file_id = queue.get_nowait()
        await service.analyze_file(user_id, file_id)
        queue.task_done()
        processed += 1
    return processed


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestPlacePipelineEndToEnd:
    async def test_enqueue_analyze_and_aggregate_places(self, client, db_path):
        user_id, token = await _create_user(db_path, "alice", "password123")
        await _seed_file(db_path, file_id=1, user_id=user_id)
        await _seed_file(db_path, file_id=2, user_id=user_id)
        await _seed_file(db_path, file_id=3, user_id=user_id)

        place = _StubPlaceAnalyzer(
            db_path,
            {
                1: (39.9, 116.4, "北京", "北京市", "中国"),
                2: (39.9, 116.4, "北京", "北京市", "中国"),
                3: (31.2, 121.5, "上海", "上海市", "中国"),
            },
        )
        service = AnalysisService(
            _settings_for(db_path),
            place_analyzer=place,
            scene_analyzer=_UnavailableAnalyzer(),
            face_analyzer=_UnavailableAnalyzer(),
        )

        # Upload -> enqueue (non-blocking, best-effort).
        for fid in (1, 2, 3):
            assert enqueue_analysis(fid, user_id) is True

        processed = await _drain_queue_with_service(service)
        assert processed == 3

        # ExploreAPI aggregation returns the analyzed cities.
        resp = await client.get("/explore/places", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert data[0]["city"] == "北京"
        assert data[0]["count"] == 2
        assert data[1]["city"] == "上海"
        assert data[1]["count"] == 1

        # Detail endpoint returns the two Beijing photos.
        detail = await client.get("/explore/places/北京", headers=_auth(token))
        assert detail.status_code == 200
        body = detail.json()
        assert body["total"] == 2
        assert {f["id"] for f in body["files"]} == {1, 2}

    async def test_reanalysis_is_idempotent(self, client, db_path):
        """Running the pipeline twice must not duplicate place rows (Req 7.5)."""
        user_id, token = await _create_user(db_path, "alice", "password123")
        await _seed_file(db_path, file_id=1, user_id=user_id)

        place = _StubPlaceAnalyzer(db_path, {1: (39.9, 116.4, "北京", "北京市", "中国")})
        service = AnalysisService(
            _settings_for(db_path),
            place_analyzer=place,
            scene_analyzer=_UnavailableAnalyzer(),
            face_analyzer=_UnavailableAnalyzer(),
        )

        enqueue_analysis(1, user_id)
        await _drain_queue_with_service(service)
        # Re-analyze the same file.
        enqueue_analysis(1, user_id)
        await _drain_queue_with_service(service)

        resp = await client.get("/explore/places/北京", headers=_auth(token))
        assert resp.status_code == 200
        assert resp.json()["total"] == 1  # not duplicated


class TestPeoplePipelineEndToEnd:
    async def test_people_aggregation_counts_visible_faces(self, client, db_path):
        """The people query must count visible faces via the aggregate even when
        the stored face_clusters.face_count is stale (0). Guards the HAVING /
        ORDER BY fix that references COUNT(fa.id) rather than the stored column.
        """
        user_id, token = await _create_user(db_path, "alice", "password123")
        await _seed_file(db_path, file_id=1, user_id=user_id)
        await _seed_file(db_path, file_id=2, user_id=user_id)

        face = _StubFaceAnalyzer(db_path, {1: "A", 2: "A"})
        service = AnalysisService(
            _settings_for(db_path),
            place_analyzer=_UnavailableAnalyzer(),
            scene_analyzer=_UnavailableAnalyzer(),
            face_analyzer=face,
        )

        for fid in (1, 2):
            enqueue_analysis(fid, user_id)
        await _drain_queue_with_service(service)

        resp = await client.get("/explore/people", headers=_auth(token))
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 1
        # Stored face_count was left at 0 by the stub; the API must still report
        # the two visible faces via COUNT(fa.id).
        assert data[0]["face_count"] == 2
        assert data[0]["cover_file_id"] in (1, 2)


class TestUserIsolationEndToEnd:
    async def test_users_do_not_see_each_others_places(self, client, db_path):
        user_a, token_a = await _create_user(db_path, "alice", "password123")
        user_b, token_b = await _create_user(db_path, "bob", "password123")
        await _seed_file(db_path, file_id=1, user_id=user_a)
        await _seed_file(db_path, file_id=2, user_id=user_b)

        place = _StubPlaceAnalyzer(
            db_path,
            {
                1: (39.9, 116.4, "北京", "北京市", "中国"),
                2: (31.2, 121.5, "上海", "上海市", "中国"),
            },
        )
        service = AnalysisService(
            _settings_for(db_path),
            place_analyzer=place,
            scene_analyzer=_UnavailableAnalyzer(),
            face_analyzer=_UnavailableAnalyzer(),
        )

        enqueue_analysis(1, user_a)
        enqueue_analysis(2, user_b)
        await _drain_queue_with_service(service)

        places_a = (await client.get("/explore/places", headers=_auth(token_a))).json()
        places_b = (await client.get("/explore/places", headers=_auth(token_b))).json()
        assert [p["city"] for p in places_a] == ["北京"]
        assert [p["city"] for p in places_b] == ["上海"]
