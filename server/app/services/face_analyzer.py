"""Face analysis: incremental clustering decision (pure logic).

This module hosts the *pure* clustering-decision logic used by the face
analysis pipeline. Keeping the decision separate from ONNX model inference and
SQL persistence makes it trivially unit- and property-testable (see task 6.2).

The heavy ``FaceAnalyzer`` class (ONNX detection + embedding, incremental
persistence to ``faces`` / ``face_clusters``) is added later in task 6.3 and
will call :func:`assign_cluster` to decide whether a freshly extracted face
joins an existing cluster or seeds a new one.

Cosine similarity is implemented with plain Python math so this module has *no*
third-party dependencies (numpy ships with onnxruntime, which is an optional
resource that may be absent in a lightweight deployment).
"""

from __future__ import annotations

import array
import json
import logging
import math
from pathlib import Path
from typing import Any, NamedTuple, Optional, Sequence, Tuple

logger = logging.getLogger("photovault.face_analyzer")

# A single existing cluster as consumed by the decision function: its integer
# id paired with its centroid vector. task 6.3 (FaceAnalyzer) and task 6.2
# (property test) both rely on this exact shape.
Vector = Sequence[float]
Cluster = Tuple[int, Vector]


def cosine_similarity(a: Vector, b: Vector) -> float:
    """Return the cosine similarity between two vectors.

    Cosine similarity is ``dot(a, b) / (||a|| * ||b||)`` and lies in
    ``[-1.0, 1.0]`` for non-degenerate inputs.

    Degenerate cases are handled safely rather than raising:
    - If either vector has zero magnitude (all-zero vector) the similarity is
      defined as ``0.0`` (the angle is undefined; ``0.0`` keeps the result
      finite and deterministic).

    Args:
        a: First vector (sequence of floats).
        b: Second vector (sequence of floats), same dimension as ``a``.

    Returns:
        The cosine similarity as a float.

    Raises:
        ValueError: If the two vectors have different dimensions.
    """
    if len(a) != len(b):
        raise ValueError(
            f"vectors must share dimension: {len(a)} != {len(b)}"
        )

    dot = 0.0
    norm_a = 0.0
    norm_b = 0.0
    for x, y in zip(a, b):
        dot += x * y
        norm_a += x * x
        norm_b += y * y

    if norm_a <= 0.0 or norm_b <= 0.0:
        return 0.0

    return dot / (math.sqrt(norm_a) * math.sqrt(norm_b))


def assign_cluster(
    embedding: Vector,
    clusters: Sequence[Cluster],
    threshold: float,
) -> Optional[int]:
    """Decide which existing cluster a face embedding joins, if any.

    Computes the cosine similarity between ``embedding`` and each cluster
    centroid, then applies a nearest-above-threshold rule:

    - Returns the ``cluster_id`` of the cluster whose centroid has the highest
      cosine similarity to ``embedding`` **iff** that highest similarity is
      ``>= threshold``.
    - Otherwise returns ``None``, signalling the caller should create a new
      cluster.

    The returned ``cluster_id`` is always a member of the input ``clusters``.
    When several clusters tie for the highest similarity, the first one in
    iteration order wins (deterministic).

    Args:
        embedding: The L2-normalised face feature vector to place.
        clusters: Existing clusters as ``(cluster_id, centroid)`` tuples. An
            empty collection always yields ``None``.
        threshold: Minimum cosine similarity required to join an existing
            cluster (``settings.face_cluster_similarity``).

    Returns:
        The best-matching ``cluster_id`` when its similarity meets
        ``threshold``, else ``None``.
    """
    best_id: Optional[int] = None
    best_sim: float = -math.inf

    for cluster_id, centroid in clusters:
        sim = cosine_similarity(embedding, centroid)
        if sim > best_sim:
            best_sim = sim
            best_id = cluster_id

    if best_id is not None and best_sim >= threshold:
        return best_id
    return None


def l2_normalize(vec: Sequence[float]) -> list[float]:
    """Return ``vec`` scaled to unit L2 norm.

    A zero (or empty) vector is returned unchanged (as a list) since it has no
    direction to normalise; this keeps the function total and side-effect free.
    """
    norm = math.sqrt(sum(float(x) * float(x) for x in vec))
    if norm <= 0.0:
        return [float(x) for x in vec]
    return [float(x) / norm for x in vec]


def _mean_vectors(vectors: Sequence[Sequence[float]]) -> Optional[list[float]]:
    """Return the element-wise mean of equal-length vectors, or ``None`` if empty."""
    if not vectors:
        return None
    dim = len(vectors[0])
    totals = [0.0] * dim
    for vec in vectors:
        for i in range(dim):
            totals[i] += float(vec[i])
    count = len(vectors)
    return [t / count for t in totals]


# ---------------------------------------------------------------------------
# float32 vector <-> BLOB helpers
# ---------------------------------------------------------------------------
#
# Embeddings and cluster centroids are stored as raw little-endian float32
# bytes. ``array('f', ...)`` produces the exact same byte layout as
# ``numpy.asarray(vec, dtype=numpy.float32).tobytes()``, so a database written
# by the real (numpy-backed) inference path and one written by a test using
# these helpers are byte-compatible. Using ``array`` keeps this module free of
# any hard numpy dependency for the clustering/persistence path.


def _encode_vector(vec: Sequence[float]) -> bytes:
    """Serialize a float vector to little-endian float32 bytes for a BLOB."""
    import sys

    arr = array.array("f", [float(x) for x in vec])
    if sys.byteorder != "little":  # pragma: no cover - big-endian hosts
        arr.byteswap()
    return arr.tobytes()


def _decode_vector(blob: Optional[bytes]) -> Optional[list[float]]:
    """Deserialize float32 BLOB bytes back into a list of floats."""
    if not blob:
        return None
    arr = array.array("f")
    arr.frombytes(blob)
    import sys

    if sys.byteorder != "little":  # pragma: no cover - big-endian hosts
        arr.byteswap()
    return list(arr)


class DetectedFace(NamedTuple):
    """A single detected+embedded face returned by the inference boundary."""

    bbox: list[float]
    det_score: float
    embedding: list[float]


class FaceAnalyzer:
    """Face detection + feature extraction + incremental clustering analyzer.

    For each analyzed photo the analyzer:

    1. decodes the image and runs the detection model, keeping only faces whose
       detection score meets ``settings.face_det_min_score`` (requirement 4.1);
    2. aligns/crops each kept face and runs the feature model, producing an
       L2-normalised embedding;
    3. assigns each embedding to the most similar existing cluster (cosine
       similarity ``>= settings.face_cluster_similarity``) via
       :func:`assign_cluster`, or seeds a new cluster otherwise (requirement
       4.2), incrementally updating the cluster centroid, ``face_count`` and
       ``cover_face_id``;
    4. writes one ``faces`` row per kept face, all tagged with ``user_id`` for
       isolation (requirement 4.7).

    **Optional resource.** The ONNX runtime (``onnxruntime``) and the detection
    (``faces/det_*.onnx``) and feature (``faces/w600k_*.onnx``) models are
    optional resources not bundled with the server. When any is missing the
    analyzer reports ``available = False`` and the pipeline skips the face
    dimension entirely, leaving the lightweight deployment unaffected
    (requirements 4.6, 7.4, 9.2).

    **Idempotent re-analysis (requirement 7.5).** Before inserting a file's new
    faces the analyzer deletes the file's prior ``faces`` rows and *recomputes*
    every cluster those rows belonged to (centroid / ``face_count`` /
    ``cover_face_id``) from the faces that remain, deleting any cluster left
    empty. This prevents centroid/count drift so that re-analyzing a file yields
    a state equivalent to analyzing it once.

    The ONNX inference boundary is split into small overridable methods
    (:meth:`_decode_image`, :meth:`_detect`, :meth:`_embed`) so the
    clustering/persistence logic can be exercised in tests without real models.
    """

    def __init__(self, settings: Any = None) -> None:
        """Create the analyzer.

        Args:
            settings: Optional settings object exposing ``models_root``,
                ``database_url``, ``face_det_min_score`` and
                ``face_cluster_similarity``. Defaults to the application
                settings singleton. Injectable for testing.
        """
        if settings is None:
            from app.core.config import get_settings

            settings = get_settings()
        self._settings = settings

        # Cached ONNX sessions keyed by (path, mtime) so an uploaded/updated
        # model file is transparently reloaded on next use (requirement 6.3).
        self._det_session: Any = None
        self._det_session_key: Optional[tuple[str, float]] = None
        self._feat_session: Any = None
        self._feat_session_key: Optional[tuple[str, float]] = None

        # Landmarks from the most recent detection, keyed by bbox, so _embed can
        # align each face precisely (populated by _detect).
        self._kps_cache: dict[tuple, Any] = {}

    # -- resource discovery -------------------------------------------------

    @property
    def _faces_dir(self) -> Path:
        """Directory that holds the face model files."""
        return Path(self._settings.models_root) / "faces"

    def _det_model_path(self) -> Optional[Path]:
        """Locate the detection model (``faces/det_*.onnx``), if present."""
        return self._glob_one("det_*.onnx")

    def _feat_model_path(self) -> Optional[Path]:
        """Locate the feature model (``faces/w600k_*.onnx``), if present."""
        return self._glob_one("w600k_*.onnx")

    def _glob_one(self, pattern: str) -> Optional[Path]:
        """Return the first file in ``faces/`` matching ``pattern`` (sorted)."""
        try:
            matches = sorted(self._faces_dir.glob(pattern))
        except OSError:  # pragma: no cover - defensive
            return None
        for candidate in matches:
            if candidate.is_file():
                return candidate
        return None

    def _runtime_available(self) -> bool:
        """Whether ``onnxruntime`` can be imported (optional dependency)."""
        try:
            import onnxruntime  # noqa: F401
        except ImportError:
            return False
        return True

    @property
    def available(self) -> bool:
        """Whether the ONNX runtime and both face models are installed.

        ``True`` iff ``onnxruntime`` imports *and* a detection model
        (``faces/det_*.onnx``) *and* a feature model (``faces/w600k_*.onnx``)
        exist under ``models_root``. Any missing piece degrades gracefully to
        ``False`` (requirements 4.6, 9.2).
        """
        return (
            self._runtime_available()
            and self._det_model_path() is not None
            and self._feat_model_path() is not None
        )

    # -- ONNX session loading (lazy, mtime-cached) --------------------------

    def _load_session(
        self,
        model_path: Path,
        cache_attr: str,
        key_attr: str,
    ) -> Any:
        """Lazily create and cache an ONNX InferenceSession for ``model_path``.

        The session is reloaded when the model file's mtime changes so a model
        uploaded at runtime is picked up without a restart (requirement 6.3).
        """
        mtime = model_path.stat().st_mtime
        key = (str(model_path), mtime)
        if getattr(self, key_attr) != key or getattr(self, cache_attr) is None:
            import onnxruntime

            session = onnxruntime.InferenceSession(
                str(model_path),
                providers=["CPUExecutionProvider"],
            )
            setattr(self, cache_attr, session)
            setattr(self, key_attr, key)
        return getattr(self, cache_attr)

    # -- ONNX inference boundary (overridable for tests) --------------------

    def _decode_image(self, path: str) -> Any:
        """Decode an image file to an RGB array for the detection model.

        Returns ``None`` when the file cannot be opened. Kept small and
        overridable so tests can bypass real image decoding.
        """
        try:
            import numpy as np

            from app.services.image_utils import open_image

            with open_image(path) as img:
                return np.asarray(img.convert("RGB"))
        except Exception as exc:  # noqa: BLE001 - decode is best-effort
            logger.debug("Could not decode image %s: %s", path, exc)
            return None

    @staticmethod
    def _bbox_key(bbox: Sequence[float]) -> tuple:
        """Stable, rounded key for correlating a bbox with its cached landmarks."""
        return tuple(round(float(v), 3) for v in bbox)

    def _detect(self, image: Any) -> list[tuple[list[float], float]]:
        """Run the detection model, returning ``(bbox, det_score)`` per face.

        ``bbox`` is ``[x1, y1, x2, y2]``. Detected 5-point landmarks are cached
        (keyed by bbox) so :meth:`_embed` can align faces precisely. This is the
        SCRFD detection boundary; tests override it to inject detections.
        """
        det_path = self._det_model_path()
        if det_path is None:  # pragma: no cover - guarded by ``available``
            return []
        session = self._load_session(det_path, "_det_session", "_det_session_key")
        detections = self._run_detection(session, image)

        self._kps_cache = {}
        out: list[tuple[list[float], float]] = []
        for bbox, score, kps in detections:
            if kps is not None:
                self._kps_cache[self._bbox_key(bbox)] = kps
            out.append((bbox, score))
        return out

    def _embed(self, image: Any, bbox: Sequence[float]) -> list[float]:
        """Align ``bbox`` (by cached landmarks when available) and embed it.

        This is the ArcFace feature boundary; tests override it to inject
        deterministic embeddings.
        """
        feat_path = self._feat_model_path()
        if feat_path is None:  # pragma: no cover - guarded by ``available``
            return []
        session = self._load_session(feat_path, "_feat_session", "_feat_session_key")
        kps = self._kps_cache.get(self._bbox_key(bbox))
        embedding = self._run_feature(session, image, bbox, kps)
        return l2_normalize(embedding)

    def _run_detection(
        self, session: Any, image: Any
    ) -> list[tuple[list[float], float, Optional[list]]]:
        """Run the SCRFD detector, returning ``(bbox, score, kps)`` per face.

        Delegates the model-specific pre/post-processing to
        :mod:`app.services.face_inference`. ``kps`` is a list of five
        ``(x, y)`` landmarks, or ``None`` for models without keypoints.
        """
        from app.services import face_inference

        min_score = float(getattr(self._settings, "face_det_min_score", 0.5))
        return face_inference.detect_faces(
            session, image, score_thresh=min_score
        )

    def _run_feature(
        self,
        session: Any,
        image: Any,
        bbox: Sequence[float],
        kps: Optional[Sequence] = None,
    ) -> list[float]:
        """Align a face (5-point landmarks preferred, else bbox crop) and embed it."""
        from app.services import face_inference

        return face_inference.get_embedding(session, image, kps=kps, bbox=bbox)

    # -- analysis entry point ----------------------------------------------

    async def analyze(self, user_id: int, file_id: int, path: str) -> None:
        """Detect, embed, cluster and persist the faces in a single photo.

        Safe no-op when the runtime/models are unavailable (requirement 9.2) or
        when the image cannot be decoded. Existing faces for ``file_id`` are
        cleared (and affected clusters recomputed) before insert so re-analysis
        is idempotent (requirement 7.5).

        Args:
            user_id: Owner of the file (persisted for user isolation).
            file_id: ``file_records.id`` of the photo being analyzed.
            path: Absolute filesystem path to the image.
        """
        if not self.available:
            return

        image = self._decode_image(path)
        if image is None:
            return

        min_score = float(getattr(self._settings, "face_det_min_score", 0.5))

        kept: list[DetectedFace] = []
        for bbox, det_score in self._detect(image):
            # Requirement 4.1: only faces at/above the detection threshold.
            if float(det_score) < min_score:
                continue
            embedding = self._embed(image, bbox)
            if not embedding:
                continue
            kept.append(
                DetectedFace(
                    bbox=[float(v) for v in bbox],
                    det_score=float(det_score),
                    embedding=[float(v) for v in embedding],
                )
            )

        await self._persist(user_id, file_id, kept)

    # -- persistence + incremental clustering -------------------------------

    async def _persist(
        self, user_id: int, file_id: int, faces: list[DetectedFace]
    ) -> None:
        """Persist detected faces, clearing prior results and updating clusters.

        Opens its own aiosqlite connection (worker-isolated). The write path is:

        1. delete the file's existing ``faces`` rows and recompute every cluster
           they touched so no centroid/count drift remains (requirement 7.5);
        2. for each new face, assign it to an existing cluster or seed a new one
           (:func:`assign_cluster`), insert the ``faces`` row, and incrementally
           update the cluster centroid / ``face_count`` / ``cover_face_id``.
        """
        from app.core.database import _create_connection

        threshold = float(getattr(self._settings, "face_cluster_similarity", 0.5))

        db = await _create_connection(self._settings.database_url)
        try:
            await self._clear_file_faces(db, user_id, file_id)

            # In-memory view of this user's clusters, kept in sync as we insert
            # so successive faces of the same photo see updated centroids.
            state = await self._load_cluster_state(db, user_id)

            for face in faces:
                candidates = [
                    (cid, s["centroid"])
                    for cid, s in state.items()
                    if s["centroid"] is not None
                ]
                cluster_id = assign_cluster(face.embedding, candidates, threshold)

                if cluster_id is None:
                    # Requirement 4.2: no match -> seed a new cluster.
                    cursor = await db.execute(
                        """INSERT INTO face_clusters (user_id, centroid, face_count)
                           VALUES (?, NULL, 0)""",
                        (user_id,),
                    )
                    cluster_id = cursor.lastrowid
                    state[cluster_id] = {
                        "centroid": None,
                        "count": 0,
                        "cover": None,
                    }

                cursor = await db.execute(
                    """INSERT INTO faces
                       (file_id, user_id, cluster_id, bbox, det_score, embedding)
                       VALUES (?, ?, ?, ?, ?, ?)""",
                    (
                        file_id,
                        user_id,
                        cluster_id,
                        json.dumps(face.bbox),
                        face.det_score,
                        _encode_vector(face.embedding),
                    ),
                )
                face_id = cursor.lastrowid

                await self._apply_incremental_update(
                    db, state, cluster_id, face.embedding, face_id
                )

            await db.commit()
        finally:
            await db.close()

    async def clear_file_faces(
        self, db: Any, user_id: int, file_id: int
    ) -> None:
        """Remove a file's faces and keep affected cluster metadata consistent.

        This is used when a file record is permanently removed outside the
        analysis pipeline, such as when an administrator clears purged records.
        """
        await self._clear_file_faces(db, user_id, file_id)

    async def _clear_file_faces(
        self, db: Any, user_id: int, file_id: int
    ) -> None:
        """Delete a file's faces then recompute the clusters they belonged to.

        Empties any cluster left with no faces. This keeps re-analysis idempotent
        by removing the file's prior contribution to cluster centroids/counts
        before the new faces are inserted (requirement 7.5).
        """
        cursor = await db.execute(
            "SELECT DISTINCT cluster_id FROM faces WHERE file_id = ? AND user_id = ?",
            (file_id, user_id),
        )
        affected = [
            row["cluster_id"]
            for row in await cursor.fetchall()
            if row["cluster_id"] is not None
        ]

        await db.execute(
            "DELETE FROM faces WHERE file_id = ? AND user_id = ?",
            (file_id, user_id),
        )

        for cluster_id in affected:
            await self._recompute_cluster(db, user_id, cluster_id)

    async def _recompute_cluster(
        self, db: Any, user_id: int, cluster_id: int
    ) -> None:
        """Recompute a cluster's centroid/count/cover from its remaining faces.

        Deletes the cluster when no faces remain.
        """
        cursor = await db.execute(
            "SELECT id, embedding FROM faces WHERE cluster_id = ? AND user_id = ?",
            (cluster_id, user_id),
        )
        rows = await cursor.fetchall()

        if not rows:
            await db.execute(
                "DELETE FROM face_clusters WHERE id = ? AND user_id = ?",
                (cluster_id, user_id),
            )
            return

        face_ids = [row["id"] for row in rows]
        embeddings = [
            _decode_vector(row["embedding"]) for row in rows
        ]
        embeddings = [e for e in embeddings if e]
        centroid = _mean_vectors(embeddings)

        # Preserve the current cover face if it still exists, else pick one.
        cover_cursor = await db.execute(
            "SELECT cover_face_id FROM face_clusters WHERE id = ?",
            (cluster_id,),
        )
        cover_row = await cover_cursor.fetchone()
        current_cover = cover_row["cover_face_id"] if cover_row else None
        cover_face_id = (
            current_cover if current_cover in face_ids else face_ids[0]
        )

        await db.execute(
            """UPDATE face_clusters
               SET centroid = ?, face_count = ?, cover_face_id = ?
               WHERE id = ? AND user_id = ?""",
            (
                _encode_vector(centroid) if centroid is not None else None,
                len(face_ids),
                cover_face_id,
                cluster_id,
                user_id,
            ),
        )

    async def _load_cluster_state(self, db: Any, user_id: int) -> dict[int, dict]:
        """Load this user's clusters into an in-memory working set."""
        cursor = await db.execute(
            """SELECT id, centroid, face_count, cover_face_id
               FROM face_clusters WHERE user_id = ?""",
            (user_id,),
        )
        state: dict[int, dict] = {}
        for row in await cursor.fetchall():
            state[row["id"]] = {
                "centroid": _decode_vector(row["centroid"]),
                "count": row["face_count"] or 0,
                "cover": row["cover_face_id"],
            }
        return state

    async def _apply_incremental_update(
        self,
        db: Any,
        state: dict[int, dict],
        cluster_id: int,
        embedding: Sequence[float],
        face_id: int,
    ) -> None:
        """Fold ``embedding`` into a cluster via incremental mean and persist.

        Updates ``centroid`` (running mean), increments ``face_count`` and sets
        ``cover_face_id`` when it is not yet assigned. The in-memory ``state`` is
        updated in lock-step so later faces in the same batch compare against the
        refreshed centroid.
        """
        s = state[cluster_id]
        count = s["count"] or 0
        prev_centroid = s["centroid"]

        if count == 0 or prev_centroid is None:
            new_centroid = [float(x) for x in embedding]
            new_count = 1
        else:
            new_count = count + 1
            new_centroid = [
                (c * count + float(e)) / new_count
                for c, e in zip(prev_centroid, embedding)
            ]

        new_cover = s["cover"] if s["cover"] is not None else face_id

        await db.execute(
            """UPDATE face_clusters
               SET centroid = ?, face_count = ?, cover_face_id = ?
               WHERE id = ?""",
            (_encode_vector(new_centroid), new_count, new_cover, cluster_id),
        )

        s["centroid"] = new_centroid
        s["count"] = new_count
        s["cover"] = new_cover
