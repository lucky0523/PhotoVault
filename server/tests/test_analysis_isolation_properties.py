"""Property-based tests for user isolation and re-analysis idempotency (task 6.4).

Covers **Property 4** from the design of the ``web-explore-people-places-scenes``
feature:

    对于任意分析结果写入，其记录的 ``user_id`` 恒等于被分析文件的归属用户；
    且对同一文件同一维度重复执行分析后，该维度结果集合与单次执行等价
    （不产生重复累积）。

These tests exercise the *real* analyzers' persistence paths against a fresh
temporary SQLite database, injecting only the ONNX/Pillow inference boundary so
no real models are required:

- **Place** — ``place_analyzer._extract_gps_coords`` is substituted so the real
  offline reverse-geocoding + ``photo_gps`` write path runs.
- **Scene** — ``SceneAnalyzer._run_inference`` is substituted (with a dummy
  model + labels installed and ``_runtime_ok=True``) so the real threshold
  filter + ``photo_scenes`` write path runs.
- **Face** — ``FaceAnalyzer._runtime_available`` / ``_decode_image`` /
  ``_detect`` / ``_embed`` are substituted (mirroring the ``_prepare`` helper in
  ``test_face_analyzer.py``) so the real clustering + ``faces`` /
  ``face_clusters`` write path runs.

For each generated case every file is analyzed twice; we assert (a) the
``user_id`` on every written row matches the analyzed file's owner and (b) the
dimension's result set after two runs equals the result set after one run.

Because Hypothesis drives a synchronous test body while the analyzers are async,
each example runs its scenario via ``asyncio.run`` inside the ``@given`` test and
builds a brand-new temp DB so examples are independent and never share state.
"""

from __future__ import annotations

import asyncio
import os
import shutil
import sqlite3
import tempfile
import types
from typing import Dict, List, Tuple

import aiosqlite
import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.core.database import _create_connection, init_db
from app.services import place_analyzer as pa
from app.services.place_analyzer import PlaceAnalyzer
from app.services.scene_analyzer import SceneAnalyzer
from app.services.face_analyzer import FaceAnalyzer, _decode_vector


# ---------------------------------------------------------------------------
# Shared fixtures / helpers
# ---------------------------------------------------------------------------

# A small pool of pre-seeded user ids. Files are owned by one of these users so
# the generated scenarios exercise multi-user isolation.
_USER_POOL = [1, 2, 3]

# Fixed thresholds mirroring the analyzer defaults used elsewhere in the suite.
_SCENE_MIN_CONF = 0.3
_FACE_DET_MIN_SCORE = 0.5
_FACE_CLUSTER_SIM = 0.5

# A tiny offline city dataset for deterministic reverse geocoding.
_CITY_ROWS = [
    ("Paris", "IDF", "FR", 48.8566, 2.3522),
    ("Tokyo", "Tokyo", "JP", 35.6762, 139.6503),
    ("New York", "NY", "US", 40.7128, -74.0060),
    ("Sydney", "NSW", "AU", -33.8688, 151.2093),
]


def _path(file_id: int) -> str:
    """A stable synthetic image path used as the inference lookup key."""
    return f"/img/{file_id}.jpg"


def _settings(models_root: str, db_url: str) -> types.SimpleNamespace:
    """Build a settings-like object covering all three analyzers' fields."""
    return types.SimpleNamespace(
        models_root=models_root,
        database_url=db_url,
        scene_min_confidence=_SCENE_MIN_CONF,
        face_det_min_score=_FACE_DET_MIN_SCORE,
        face_cluster_similarity=_FACE_CLUSTER_SIM,
    )


async def _seed(db_url: str, files: List[Tuple[int, int]]) -> None:
    """Create the schema and insert the distinct users + given file_records."""
    await init_db(db_url)
    db = await _create_connection(db_url)
    try:
        for uid in sorted({uid for _, uid in files}):
            await db.execute(
                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                (uid, f"user{uid}", "h"),
            )
        for fid, uid in files:
            await db.execute(
                """INSERT INTO file_records
                   (id, user_id, file_hash, file_path, original_path,
                    device_name, file_size, file_name)
                   VALUES (?, ?, ?, ?, ?, 'dev', 100, ?)""",
                (fid, uid, f"hash{fid}", f"/f{fid}", f"/o{fid}", f"{fid}.jpg"),
            )
        await db.commit()
    finally:
        await db.close()


def _make_cities_db(path, rows) -> None:
    """Create a cities.db with the standard schema and given rows."""
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    try:
        conn.execute(
            "CREATE TABLE cities (name TEXT, province TEXT, country TEXT, "
            "latitude REAL, longitude REAL)"
        )
        conn.executemany("INSERT INTO cities VALUES (?, ?, ?, ?, ?)", rows)
        conn.commit()
    finally:
        conn.close()


class _TempWorkspace:
    """Context manager yielding a throwaway (db_url, models_root) pair."""

    def __enter__(self) -> Tuple[str, str]:
        self._dir = tempfile.mkdtemp(prefix="pv_prop_")
        db_url = os.path.join(self._dir, "photovault.db")
        models_root = os.path.join(self._dir, "models")
        return db_url, models_root

    def __exit__(self, *exc) -> None:
        shutil.rmtree(self._dir, ignore_errors=True)


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

_lat = st.floats(min_value=-90.0, max_value=90.0, allow_nan=False, allow_infinity=False)
_lon = st.floats(
    min_value=-180.0, max_value=180.0, allow_nan=False, allow_infinity=False
)
_score = st.floats(min_value=0.0, max_value=1.0, allow_nan=False, allow_infinity=False)
_scene_label = st.sampled_from(
    ["beach", "park", "city", "forest", "screenshot", "mountain"]
)
_emb_component = st.floats(
    min_value=-10.0, max_value=10.0, allow_nan=False, allow_infinity=False
)


@st.composite
def _files(draw, max_files: int = 5):
    """Draw a list of ``(file_id, owner_user_id)`` with distinct file ids."""
    n = draw(st.integers(min_value=1, max_value=max_files))
    owners = draw(
        st.lists(st.sampled_from(_USER_POOL), min_size=n, max_size=n)
    )
    return [(1000 + i, owners[i]) for i in range(n)]


@st.composite
def _place_scenario(draw):
    """(files, coords_by_path): a coordinate for every file."""
    files = draw(_files())
    coords = {_path(fid): (draw(_lat), draw(_lon)) for fid, _ in files}
    return files, coords


@st.composite
def _scene_scenario(draw):
    """(files, predictions_by_path): raw (label, score) predictions per file."""
    files = draw(_files())
    preds: Dict[str, List[Tuple[str, float]]] = {}
    for fid, _ in files:
        k = draw(st.integers(min_value=0, max_value=5))
        labels = draw(st.lists(_scene_label, min_size=k, max_size=k))
        preds[_path(fid)] = [(lab, draw(_score)) for lab in labels]
    return files, preds


@st.composite
def _face_scenario(draw):
    """(files, detections_by_path): (bbox, det_score, embedding) list per file."""
    files = draw(_files(max_files=4))
    dim = draw(st.integers(min_value=3, max_value=6))
    detections: Dict[str, List[Tuple[List[float], float, List[float]]]] = {}
    for fid, _ in files:
        nfaces = draw(st.integers(min_value=0, max_value=3))
        faces = []
        for j in range(nfaces):
            # Distinct bbox per face within a file so the embed lookup is exact.
            bbox = [j * 10.0, j * 10.0, j * 10.0 + 5.0, j * 10.0 + 5.0]
            score = draw(_score)
            emb = draw(st.lists(_emb_component, min_size=dim, max_size=dim))
            faces.append((bbox, score, emb))
        detections[_path(fid)] = faces
    return files, detections


# ---------------------------------------------------------------------------
# Snapshot helpers (normalised so run-1 vs run-2 comparison ignores volatile
# columns such as autoincrement ids and CURRENT_TIMESTAMP values).
# ---------------------------------------------------------------------------


async def _snapshot_place(db_url: str):
    db = await _create_connection(db_url)
    try:
        cur = await db.execute(
            "SELECT file_id, user_id, latitude, longitude, city, province, country "
            "FROM photo_gps"
        )
        rows = [
            (r["file_id"], r["user_id"], r["latitude"], r["longitude"],
             r["city"], r["province"], r["country"])
            for r in await cur.fetchall()
        ]
        return sorted(rows, key=lambda t: t[0])
    finally:
        await db.close()


async def _snapshot_scene(db_url: str):
    db = await _create_connection(db_url)
    try:
        cur = await db.execute(
            "SELECT file_id, user_id, scene_label, confidence FROM photo_scenes"
        )
        rows = [
            (r["file_id"], r["user_id"], r["scene_label"], round(r["confidence"], 6))
            for r in await cur.fetchall()
        ]
        return sorted(rows)
    finally:
        await db.close()


async def _snapshot_face(db_url: str):
    """Return (faces_multiset, cluster_count, sorted_face_counts).

    Excludes autoincrement ids, cluster ids, ``cover_face_id`` and
    ``created_at`` so the comparison reflects logical equivalence rather than
    incidental row identity.
    """
    db = await _create_connection(db_url)
    try:
        cur = await db.execute(
            "SELECT file_id, user_id, bbox, det_score, embedding FROM faces"
        )
        faces = []
        for r in await cur.fetchall():
            emb = _decode_vector(r["embedding"]) or []
            faces.append(
                (
                    r["file_id"],
                    r["user_id"],
                    r["bbox"],
                    round(r["det_score"], 6),
                    tuple(round(x, 5) for x in emb),
                )
            )
        cur = await db.execute("SELECT user_id, face_count FROM face_clusters")
        clusters = [(r["user_id"], r["face_count"]) for r in await cur.fetchall()]
        return sorted(faces), len(clusters), sorted(clusters)
    finally:
        await db.close()


# ---------------------------------------------------------------------------
# Per-dimension case runners
# ---------------------------------------------------------------------------


async def _run_place_case(files, coords, db_url, models_root):
    await _seed(db_url, files)
    analyzer = PlaceAnalyzer(settings=_settings(models_root, db_url))
    _make_cities_db(analyzer.cities_db_path, _CITY_ROWS)
    owner = {fid: uid for fid, uid in files}

    original = pa._extract_gps_coords
    pa._extract_gps_coords = lambda path: coords.get(path)
    try:
        for fid, uid in files:
            await analyzer.analyze(user_id=uid, file_id=fid, path=_path(fid))
        snap1 = await _snapshot_place(db_url)

        # (a) user isolation: every row's user_id is the file owner.
        for file_id, user_id, *_ in snap1:
            assert user_id == owner[file_id]
        # A coordinate always yields exactly one row per file.
        assert len(snap1) == len(files)

        for fid, uid in files:
            await analyzer.analyze(user_id=uid, file_id=fid, path=_path(fid))
        snap2 = await _snapshot_place(db_url)
    finally:
        pa._extract_gps_coords = original

    # (b) re-analysis is equivalent to a single run (no accumulation).
    assert snap2 == snap1
    assert len(snap2) == len(files)


async def _run_scene_case(files, preds, db_url, models_root):
    await _seed(db_url, files)
    analyzer = SceneAnalyzer(settings=_settings(models_root, db_url))
    # Install dummy model + labels so ``available`` passes; runtime forced on.
    analyzer.model_path.parent.mkdir(parents=True, exist_ok=True)
    analyzer.model_path.write_bytes(b"stub")
    analyzer.labels_path.write_text("[]", encoding="utf-8")
    analyzer._runtime_ok = True
    analyzer._run_inference = lambda path: list(preds.get(path, []))
    owner = {fid: uid for fid, uid in files}

    for fid, uid in files:
        await analyzer.analyze(user_id=uid, file_id=fid, path=_path(fid))
    snap1 = await _snapshot_scene(db_url)

    # (a) user isolation.
    for file_id, user_id, _label, _conf in snap1:
        assert user_id == owner[file_id]
    # Kept rows must respect the confidence threshold.
    for _fid, _uid, _label, conf in snap1:
        assert conf >= _SCENE_MIN_CONF

    for fid, uid in files:
        await analyzer.analyze(user_id=uid, file_id=fid, path=_path(fid))
    snap2 = await _snapshot_scene(db_url)

    # (b) re-analysis does not accumulate.
    assert snap2 == snap1


async def _run_face_case(files, detections, db_url, models_root):
    await _seed(db_url, files)
    analyzer = FaceAnalyzer(settings=_settings(models_root, db_url))

    # Install dummy detection + feature models so paths resolve.
    faces_dir = analyzer._faces_dir
    faces_dir.mkdir(parents=True, exist_ok=True)
    (faces_dir / "det_10g.onnx").write_bytes(b"stub")
    (faces_dir / "w600k_r50.onnx").write_bytes(b"stub")

    # Inject the inference boundary (mirrors test_face_analyzer._prepare):
    # decode returns the path, detect/embed look results up by path/bbox.
    embed_by_path = {
        path: {tuple(bbox): emb for bbox, _s, emb in faces}
        for path, faces in detections.items()
    }
    analyzer._runtime_available = lambda: True
    analyzer._decode_image = lambda path: path
    analyzer._detect = lambda image: [
        (bbox, score) for bbox, score, _ in detections.get(image, [])
    ]
    analyzer._embed = lambda image, bbox: embed_by_path[image][tuple(bbox)]

    owner = {fid: uid for fid, uid in files}

    for fid, uid in files:
        await analyzer.analyze(user_id=uid, file_id=fid, path=_path(fid))
    snap1 = await _snapshot_face(db_url)

    # (a) user isolation: faces + the clusters that own them belong to the file
    # owner, with no cross-user bleed.
    db = await _create_connection(db_url)
    try:
        cur = await db.execute(
            "SELECT f.user_id AS f_uid, f.file_id AS fid, c.user_id AS c_uid "
            "FROM faces f JOIN face_clusters c ON f.cluster_id = c.id"
        )
        for r in await cur.fetchall():
            assert r["f_uid"] == owner[r["fid"]]
            assert r["c_uid"] == owner[r["fid"]]
    finally:
        await db.close()

    for fid, uid in files:
        await analyzer.analyze(user_id=uid, file_id=fid, path=_path(fid))
    snap2 = await _snapshot_face(db_url)

    # (b) re-analysis is idempotent: identical faces, cluster count and sizes.
    assert snap2 == snap1


# ---------------------------------------------------------------------------
# Property 4 — three dimensions
# ---------------------------------------------------------------------------

_SETTINGS = settings(
    max_examples=100,
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow],
)


# Feature: web-explore-people-places-scenes, Property 4: 用户隔离与重复分析幂等
@_SETTINGS
@given(scenario=_place_scenario())
def test_place_user_isolation_and_idempotent(scenario):
    """Feature: web-explore-people-places-scenes, Property 4: 用户隔离与重复分析幂等

    **Validates: Requirements 2.6, 3.7, 4.7, 7.5, 8.1**

    Place dimension: each ``photo_gps`` row carries the analyzed file's owner,
    and re-analysis keeps exactly one row per file (no accumulation).
    """
    files, coords = scenario
    with _TempWorkspace() as (db_url, models_root):
        asyncio.run(_run_place_case(files, coords, db_url, models_root))


# Feature: web-explore-people-places-scenes, Property 4: 用户隔离与重复分析幂等
@_SETTINGS
@given(scenario=_scene_scenario())
def test_scene_user_isolation_and_idempotent(scenario):
    """Feature: web-explore-people-places-scenes, Property 4: 用户隔离与重复分析幂等

    **Validates: Requirements 2.6, 3.7, 4.7, 7.5, 8.1**

    Scene dimension: each ``photo_scenes`` row carries the analyzed file's
    owner, and re-analysis yields the same row set as a single run.
    """
    files, preds = scenario
    with _TempWorkspace() as (db_url, models_root):
        asyncio.run(_run_scene_case(files, preds, db_url, models_root))


# Feature: web-explore-people-places-scenes, Property 4: 用户隔离与重复分析幂等
@_SETTINGS
@given(scenario=_face_scenario())
def test_face_user_isolation_and_idempotent(scenario):
    """Feature: web-explore-people-places-scenes, Property 4: 用户隔离与重复分析幂等

    **Validates: Requirements 2.6, 3.7, 4.7, 7.5, 8.1**

    Face dimension: ``faces`` / ``face_clusters`` rows carry the analyzed
    file's owner (no cross-user cluster bleed), and re-analysis is idempotent
    (face count and cluster structure stable).
    """
    files, detections = scenario
    with _TempWorkspace() as (db_url, models_root):
        asyncio.run(_run_face_case(files, detections, db_url, models_root))
