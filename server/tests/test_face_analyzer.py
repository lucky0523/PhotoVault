"""Unit tests for FaceAnalyzer (availability, clustering, persistence).

These tests cover the stateful analyzer behaviour of task 6.3 without requiring
``onnxruntime`` or real model files:

- ``available`` is False when the runtime or model files are missing, and True
  only when the runtime imports AND both models are present (Requirements 4.6,
  9.2);
- ``analyze`` is a safe no-op when the analyzer is unavailable (Requirement 9.2);
- similar embeddings join one cluster while a dissimilar embedding seeds a new
  cluster (Requirement 4.2);
- detections below ``face_det_min_score`` are dropped (Requirement 4.1);
- persisted rows carry the file owner's ``user_id`` (Requirement 4.7);
- re-analysis replaces a file's faces and recomputes clusters without drift so
  the result is equivalent to a single run (Requirement 7.5).

The ONNX inference boundary (``_decode_image`` / ``_detect`` / ``_embed``) is
substituted so the real clustering + SQLite persistence path runs
deterministically against injected detections/embeddings.
"""

import json
import types

import aiosqlite
import pytest

from app.core.database import init_db
from app.services.face_analyzer import (
    FaceAnalyzer,
    _decode_vector,
    _encode_vector,
)


# ---------------------------------------------------------------------------
# Fixtures / helpers
# ---------------------------------------------------------------------------

def _make_settings(tmp_path):
    """Build a minimal settings-like object for the analyzer."""
    return types.SimpleNamespace(
        models_root=str(tmp_path / "models"),
        database_url=str(tmp_path / "photovault.db"),
        face_det_min_score=0.5,
        face_cluster_similarity=0.5,
    )


async def _setup_db(db_url: str) -> None:
    """Init schema and insert two users with a few file_records each."""
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
        for fid, uid in ((10, 1), (11, 1), (12, 1), (20, 2)):
            await db.execute(
                """INSERT INTO file_records
                   (id, user_id, file_hash, file_path, original_path,
                    device_name, file_size, file_name)
                   VALUES (?, ?, ?, ?, ?, 'dev', 100, 'a.jpg')""",
                (fid, uid, f"hash{fid}", f"/f{fid}", f"/o{fid}"),
            )
        await db.commit()
    finally:
        await db.close()


def _install_models(models_root) -> None:
    """Create dummy detection + feature model files so paths resolve."""
    from pathlib import Path

    faces_dir = Path(models_root) / "faces"
    faces_dir.mkdir(parents=True, exist_ok=True)
    (faces_dir / "det_10g.onnx").write_bytes(b"stub")
    (faces_dir / "w600k_r50.onnx").write_bytes(b"stub")


def _prepare(analyzer, monkeypatch, faces):
    """Make ``analyzer`` available and inject ``faces`` as detection results.

    ``faces`` is a list of ``(bbox, det_score, embedding)`` tuples. The image
    decode is stubbed to a sentinel, detection returns the bboxes+scores, and
    embedding looks up the injected vector by bbox.
    """
    _install_models(analyzer._settings.models_root)
    monkeypatch.setattr(analyzer, "_runtime_available", lambda: True)
    monkeypatch.setattr(analyzer, "_decode_image", lambda path: "IMG")
    monkeypatch.setattr(
        analyzer, "_detect", lambda image: [(bbox, score) for bbox, score, _ in faces]
    )
    embed_map = {tuple(bbox): emb for bbox, _, emb in faces}
    monkeypatch.setattr(
        analyzer, "_embed", lambda image, bbox: embed_map[tuple(bbox)]
    )


async def _fetch_faces(db_url, user_id=None):
    db = await aiosqlite.connect(db_url)
    db.row_factory = aiosqlite.Row
    try:
        if user_id is None:
            cur = await db.execute("SELECT * FROM faces ORDER BY id")
        else:
            cur = await db.execute(
                "SELECT * FROM faces WHERE user_id = ? ORDER BY id", (user_id,)
            )
        return await cur.fetchall()
    finally:
        await db.close()


async def _fetch_clusters(db_url, user_id=None):
    db = await aiosqlite.connect(db_url)
    db.row_factory = aiosqlite.Row
    try:
        if user_id is None:
            cur = await db.execute("SELECT * FROM face_clusters ORDER BY id")
        else:
            cur = await db.execute(
                "SELECT * FROM face_clusters WHERE user_id = ? ORDER BY id",
                (user_id,),
            )
        return await cur.fetchall()
    finally:
        await db.close()


# ---------------------------------------------------------------------------
# float32 BLOB round-trip
# ---------------------------------------------------------------------------

def test_vector_blob_roundtrip():
    vec = [1.0, -0.5, 0.25, 0.0]
    decoded = _decode_vector(_encode_vector(vec))
    assert decoded is not None
    assert len(decoded) == len(vec)
    for a, b in zip(decoded, vec):
        assert abs(a - b) < 1e-6
    assert _decode_vector(None) is None
    assert _decode_vector(b"") is None


# ---------------------------------------------------------------------------
# Availability (Requirements 4.6, 9.2)
# ---------------------------------------------------------------------------

def test_available_false_without_runtime(tmp_path, monkeypatch):
    settings = _make_settings(tmp_path)
    analyzer = FaceAnalyzer(settings=settings)
    _install_models(settings.models_root)
    # Models present but runtime missing -> unavailable.
    monkeypatch.setattr(analyzer, "_runtime_available", lambda: False)
    assert analyzer.available is False


def test_available_false_without_models(tmp_path, monkeypatch):
    settings = _make_settings(tmp_path)
    analyzer = FaceAnalyzer(settings=settings)
    # Runtime present but no model files -> unavailable.
    monkeypatch.setattr(analyzer, "_runtime_available", lambda: True)
    assert analyzer.available is False

    # Only the detection model present is still not enough.
    from pathlib import Path

    faces_dir = Path(settings.models_root) / "faces"
    faces_dir.mkdir(parents=True, exist_ok=True)
    (faces_dir / "det_10g.onnx").write_bytes(b"stub")
    assert analyzer.available is False


def test_available_true_with_runtime_and_models(tmp_path, monkeypatch):
    settings = _make_settings(tmp_path)
    analyzer = FaceAnalyzer(settings=settings)
    _install_models(settings.models_root)
    monkeypatch.setattr(analyzer, "_runtime_available", lambda: True)
    assert analyzer.available is True


# ---------------------------------------------------------------------------
# analyze() no-op when unavailable (Requirement 9.2)
# ---------------------------------------------------------------------------

async def test_analyze_noop_when_unavailable(tmp_path, monkeypatch):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    analyzer = FaceAnalyzer(settings=settings)
    # No models, no runtime -> unavailable. _detect must never be called.
    monkeypatch.setattr(
        analyzer,
        "_detect",
        lambda image: (_ for _ in ()).throw(AssertionError("should not run")),
    )

    await analyzer.analyze(user_id=1, file_id=10, path="/nonexistent.jpg")

    assert await _fetch_faces(settings.database_url) == []
    assert await _fetch_clusters(settings.database_url) == []


# ---------------------------------------------------------------------------
# Clustering + persistence (Requirements 4.1, 4.2, 4.7)
# ---------------------------------------------------------------------------

async def test_similar_faces_join_dissimilar_creates_new_cluster(
    tmp_path, monkeypatch
):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    analyzer = FaceAnalyzer(settings=settings)

    # File 10: one face pointing +x.
    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0])])
    await analyzer.analyze(user_id=1, file_id=10, path="/a.jpg")

    # File 11: a very similar face (cosine ~0.99) -> should JOIN cluster 1.
    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [0.99, 0.14, 0.0])])
    await analyzer.analyze(user_id=1, file_id=11, path="/b.jpg")

    # File 12: an orthogonal face (cosine 0.0 < 0.5) -> NEW cluster.
    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [0.0, 0.0, 1.0])])
    await analyzer.analyze(user_id=1, file_id=12, path="/c.jpg")

    faces = await _fetch_faces(settings.database_url, user_id=1)
    clusters = await _fetch_clusters(settings.database_url, user_id=1)

    assert len(faces) == 3
    assert len(clusters) == 2  # {face10, face11} together, face12 alone

    # The two similar faces share a cluster; the dissimilar one differs.
    by_file = {f["file_id"]: f["cluster_id"] for f in faces}
    assert by_file[10] == by_file[11]
    assert by_file[12] != by_file[10]

    # face_count reflects membership.
    counts = {c["id"]: c["face_count"] for c in clusters}
    assert counts[by_file[10]] == 2
    assert counts[by_file[12]] == 1

    # cover_face_id is set for every cluster.
    assert all(c["cover_face_id"] is not None for c in clusters)

    # user_id isolation: all rows belong to user 1.
    assert all(f["user_id"] == 1 for f in faces)
    assert all(c["user_id"] == 1 for c in clusters)

    # bbox and embedding round-trip.
    f10 = next(f for f in faces if f["file_id"] == 10)
    assert json.loads(f10["bbox"]) == [0.0, 0.0, 10.0, 10.0]
    assert _decode_vector(f10["embedding"])[0] == pytest.approx(1.0, abs=1e-6)


async def test_detection_threshold_filters_low_score_faces(tmp_path, monkeypatch):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    analyzer = FaceAnalyzer(settings=settings)

    # Two detections: one above threshold (0.9), one below (0.1).
    _prepare(
        analyzer,
        monkeypatch,
        [
            ([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0]),
            ([20, 20, 30, 30], 0.1, [0.0, 1.0, 0.0]),
        ],
    )
    await analyzer.analyze(user_id=1, file_id=10, path="/a.jpg")

    faces = await _fetch_faces(settings.database_url, user_id=1)
    assert len(faces) == 1  # low-score face dropped
    assert faces[0]["det_score"] == pytest.approx(0.9)


async def test_multiple_faces_same_photo(tmp_path, monkeypatch):
    """Two dissimilar faces in one photo yield two clusters."""
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    analyzer = FaceAnalyzer(settings=settings)

    _prepare(
        analyzer,
        monkeypatch,
        [
            ([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0]),
            ([20, 20, 30, 30], 0.8, [0.0, 1.0, 0.0]),
        ],
    )
    await analyzer.analyze(user_id=1, file_id=10, path="/a.jpg")

    faces = await _fetch_faces(settings.database_url, user_id=1)
    clusters = await _fetch_clusters(settings.database_url, user_id=1)
    assert len(faces) == 2
    assert len(clusters) == 2


# ---------------------------------------------------------------------------
# User isolation (Requirement 4.7)
# ---------------------------------------------------------------------------

async def test_user_isolation(tmp_path, monkeypatch):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    analyzer = FaceAnalyzer(settings=settings)

    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0])])
    await analyzer.analyze(user_id=1, file_id=10, path="/a.jpg")

    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0])])
    await analyzer.analyze(user_id=2, file_id=20, path="/z.jpg")

    u1_faces = await _fetch_faces(settings.database_url, user_id=1)
    u2_faces = await _fetch_faces(settings.database_url, user_id=2)
    u1_clusters = await _fetch_clusters(settings.database_url, user_id=1)
    u2_clusters = await _fetch_clusters(settings.database_url, user_id=2)

    # Identical embeddings but different users -> separate clusters, no bleed.
    assert len(u1_faces) == 1 and u1_faces[0]["user_id"] == 1
    assert len(u2_faces) == 1 and u2_faces[0]["user_id"] == 2
    assert len(u1_clusters) == 1 and len(u2_clusters) == 1
    assert u1_clusters[0]["id"] != u2_clusters[0]["id"]


# ---------------------------------------------------------------------------
# Idempotent re-analysis (Requirement 7.5)
# ---------------------------------------------------------------------------

async def test_reanalysis_is_idempotent(tmp_path, monkeypatch):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    analyzer = FaceAnalyzer(settings=settings)

    faces_spec = [([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0])]

    _prepare(analyzer, monkeypatch, faces_spec)
    await analyzer.analyze(user_id=1, file_id=10, path="/a.jpg")

    _prepare(analyzer, monkeypatch, faces_spec)
    await analyzer.analyze(user_id=1, file_id=10, path="/a.jpg")

    faces = await _fetch_faces(settings.database_url, user_id=1)
    clusters = await _fetch_clusters(settings.database_url, user_id=1)

    # Re-analysis replaced rather than accumulated.
    assert len(faces) == 1
    assert len(clusters) == 1
    assert clusters[0]["face_count"] == 1
    # Cover points at the (single) surviving face.
    assert clusters[0]["cover_face_id"] == faces[0]["id"]


async def test_reanalysis_recomputes_shared_cluster_without_drift(
    tmp_path, monkeypatch
):
    """Re-analyzing one file recomputes a cluster it shared with another file."""
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    analyzer = FaceAnalyzer(settings=settings)

    # File 10 and 11 both contain the same person -> one shared cluster.
    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0])])
    await analyzer.analyze(user_id=1, file_id=10, path="/a.jpg")
    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0])])
    await analyzer.analyze(user_id=1, file_id=11, path="/b.jpg")

    clusters = await _fetch_clusters(settings.database_url, user_id=1)
    assert len(clusters) == 1
    assert clusters[0]["face_count"] == 2

    # Re-analyze file 11 with the same face: count must stay 2, not grow.
    _prepare(analyzer, monkeypatch, [([0, 0, 10, 10], 0.9, [1.0, 0.0, 0.0])])
    await analyzer.analyze(user_id=1, file_id=11, path="/b.jpg")

    faces = await _fetch_faces(settings.database_url, user_id=1)
    clusters = await _fetch_clusters(settings.database_url, user_id=1)
    assert len(faces) == 2
    assert len(clusters) == 1
    assert clusters[0]["face_count"] == 2
