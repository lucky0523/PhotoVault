"""Scene classification analyzer.

Runs an optional ONNX scene-classification model over a photo and stores the
resulting scene labels (with confidence scores) in ``photo_scenes``. The model
runtime (``onnxruntime``) and the model files themselves are optional resources
that are not bundled with the server; when they are missing the analyzer simply
reports ``available = False`` and the pipeline skips the scene dimension
(requirements 3.6, 9.2).

The confidence-threshold filtering logic is factored out into the pure function
``filter_scenes`` so it can be exhaustively property-tested without loading any
model. Model loading, image preprocessing and inference (the side-effecting
parts) live in the ``SceneAnalyzer`` class.

Resource layout under ``{models_root}/scenes/``:

- ``scene.onnx``    -- the classification model (e.g. Places365 / MobileNet).
- ``labels_zh.json`` -- maps each model output class to a Chinese display name.

``labels_zh.json`` shapes accepted (see :func:`load_scene_labels`):

- A JSON list indexed by class id::

      ["海滩", "公园", ...]                     # plain zh strings
      [{"label": "beach", "name_zh": "海滩"}, ...]  # objects with en + zh

- A JSON object mapping::

      {"beach": "海滩", "park": "公园", ...}     # english label -> zh name
      {"0": "海滩", "1": "公园", ...}            # class-id string -> zh name

The parsed result is an ordered ``list[(label_key, name_zh)]`` indexed by class
id, where ``label_key`` is a stable identifier persisted in
``photo_scenes.scene_label`` and ``name_zh`` is the Chinese display name.

Storage decision (aligns task 4.1 / ExploreAPI):
    ``photo_scenes.scene_label`` stores the **label key** (the stable
    english/identifier string when the labels file provides one, otherwise the
    Chinese string itself when only zh names are available). The scenes
    aggregation API returns ``{label, name_zh}`` where ``name_zh`` is resolved
    from ``labels_zh.json``. The ExploreAPI can reuse :func:`load_scene_labels`
    (or :attr:`SceneAnalyzer.label_display_map`) to build the ``label -> name_zh``
    mapping so the two layers stay consistent.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Optional, Sequence

logger = logging.getLogger("photovault.scene_analyzer")

# Default preprocessing / inference parameters (ImageNet-style).
_INPUT_SIZE = 224
_IMAGENET_MEAN = (0.485, 0.456, 0.406)
_IMAGENET_STD = (0.229, 0.224, 0.225)
# How many top predictions to consider as candidates before threshold filtering.
_TOP_K = 5


def filter_scenes(
    scores: Sequence[float],
    labels: Sequence[str],
    min_conf: float,
) -> list[tuple[str, float]]:
    """Filter and rank scene predictions by confidence.

    Given parallel sequences of model ``scores`` (confidences) and ``labels``,
    return the labels whose confidence meets ``min_conf``, ordered from most to
    least confident. When the same label appears more than once, only the
    highest-confidence occurrence is kept.

    Args:
        scores: Confidence value for each prediction.
        labels: Label string for each prediction, parallel to ``scores``.
        min_conf: Minimum confidence (inclusive) required to keep a label.

    Returns:
        A list of ``(label, confidence)`` tuples such that:

        - every ``confidence`` is ``>= min_conf``;
        - every ``label`` comes from the input ``labels``;
        - the list is sorted by ``confidence`` descending;
        - no label appears more than once (highest confidence kept);
        - the list is empty when no prediction meets ``min_conf``.

    Only the overlapping prefix of ``scores`` and ``labels`` is considered, so
    mismatched lengths are handled gracefully rather than raising.
    """
    # Keep the highest confidence seen for each distinct label.
    best_by_label: dict[str, float] = {}
    for label, score in zip(labels, scores):
        conf = float(score)
        if conf < min_conf:
            continue
        current = best_by_label.get(label)
        if current is None or conf > current:
            best_by_label[label] = conf

    # Sort by confidence descending; ties broken by label for deterministic output.
    ranked = sorted(best_by_label.items(), key=lambda item: (-item[1], item[0]))
    return ranked


def load_scene_labels(labels_path: Path) -> list[tuple[str, str]]:
    """Parse ``labels_zh.json`` into an ordered ``[(label_key, name_zh)]`` list.

    The result is indexed by model output class id (position ``i`` corresponds
    to logit ``i``). ``label_key`` is a stable identifier stored in
    ``photo_scenes.scene_label``; ``name_zh`` is the Chinese display name shown
    in the Web UI. See the module docstring for the accepted JSON shapes.

    Any parse failure yields an empty list (logged), so a malformed labels file
    degrades gracefully rather than raising.
    """
    try:
        with open(labels_path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as exc:
        logger.warning("Could not read scene labels %s: %s", labels_path, exc)
        return []

    labels: list[tuple[str, str]] = []

    if isinstance(data, list):
        for entry in data:
            if isinstance(entry, str):
                labels.append((entry, entry))
            elif isinstance(entry, dict):
                key = (
                    entry.get("label")
                    or entry.get("en")
                    or entry.get("name")
                    or entry.get("name_zh")
                    or entry.get("zh")
                )
                zh = (
                    entry.get("name_zh")
                    or entry.get("zh")
                    or entry.get("label")
                    or key
                )
                if key is None:
                    continue
                labels.append((str(key), str(zh)))
            else:
                # Unknown element type: skip defensively.
                continue
        return labels

    if isinstance(data, dict):
        # If every key is integer-like, treat keys as class ids and order by
        # them; the value is the zh name and doubles as the label key.
        keys = list(data.keys())
        if keys and all(str(k).lstrip("-").isdigit() for k in keys):
            for k in sorted(keys, key=lambda x: int(x)):
                zh = str(data[k])
                labels.append((zh, zh))
            return labels
        # Otherwise treat as {english_label: zh_name}, insertion-ordered.
        for key, zh in data.items():
            labels.append((str(key), str(zh)))
        return labels

    logger.warning("Unrecognised scene labels format in %s", labels_path)
    return []


def _softmax(values: Any) -> Any:
    """Numerically stable softmax over the last axis of a numpy array."""
    import numpy as np

    arr = np.asarray(values, dtype=np.float32)
    arr = arr - np.max(arr)
    exp = np.exp(arr)
    total = np.sum(exp)
    if total == 0:
        return exp
    return exp / total


class SceneAnalyzer:
    """Scene-classification analyzer backed by an optional ONNX model.

    The analyzer lazily imports ``onnxruntime`` (and ``numpy``) and lazily loads
    the model / labels from ``{models_root}/scenes/``. When the runtime is not
    installed, or the model or labels file is missing, :attr:`available` is
    ``False`` and :meth:`analyze` is a safe no-op so the lightweight deployment
    and the place link are unaffected (requirements 3.6, 7.4, 9.2).

    The ONNX session and labels are cached and transparently reloaded when the
    model file's mtime changes, so a model uploaded at runtime via the manage
    UI is picked up on the next analysis without a restart (requirement 6.3).
    """

    def __init__(self, settings: Any = None) -> None:
        """Create the analyzer.

        Args:
            settings: Optional settings object exposing ``models_root``,
                ``database_url`` and ``scene_min_confidence``. Defaults to the
                application settings singleton. Injectable for testing.
        """
        if settings is None:
            from app.core.config import get_settings

            settings = get_settings()
        self._settings = settings

        # Whether the ONNX runtime import succeeded. ``None`` = not yet probed.
        self._runtime_ok: Optional[bool] = None

        # Cached session + labels, keyed by the model file's mtime so a
        # runtime-uploaded model is reloaded on next use.
        self._session: Any = None
        self._input_name: Optional[str] = None
        self._labels: list[tuple[str, str]] = []
        self._model_mtime: Optional[float] = None

    # -- resource paths ---------------------------------------------------

    @property
    def model_path(self) -> Path:
        """Filesystem path to the scene classification ONNX model."""
        return Path(self._settings.models_root) / "scenes" / "scene.onnx"

    @property
    def labels_path(self) -> Path:
        """Filesystem path to the Chinese label mapping file."""
        return Path(self._settings.models_root) / "scenes" / "labels_zh.json"

    # -- availability -----------------------------------------------------

    def _runtime_available(self) -> bool:
        """Probe (once) whether ``onnxruntime`` can be imported."""
        if self._runtime_ok is None:
            try:
                import onnxruntime  # noqa: F401
                import numpy  # noqa: F401

                self._runtime_ok = True
            except ImportError:
                # Requirement 9.2: optional dependency missing -> degrade.
                logger.info(
                    "onnxruntime/numpy not installed; scene analysis disabled"
                )
                self._runtime_ok = False
        return self._runtime_ok

    @property
    def available(self) -> bool:
        """Whether the scene model + runtime + labels are installed and loadable."""
        return (
            self._runtime_available()
            and self.model_path.is_file()
            and self.labels_path.is_file()
        )

    # -- model loading ----------------------------------------------------

    def _ensure_loaded(self) -> bool:
        """Load (or reload) the ONNX session + labels if needed.

        Returns ``True`` when a usable session and non-empty labels are ready,
        ``False`` otherwise. Reloads transparently when the model file's mtime
        changes so runtime-uploaded models are picked up (requirement 6.3).
        """
        if not self.available:
            return False

        try:
            mtime = self.model_path.stat().st_mtime
        except OSError:
            return False

        if self._session is None or self._model_mtime != mtime:
            try:
                import onnxruntime as ort

                session = ort.InferenceSession(
                    str(self.model_path),
                    providers=["CPUExecutionProvider"],
                )
            except Exception as exc:  # pragma: no cover - defensive
                logger.warning(
                    "Failed to load scene model %s: %s", self.model_path, exc
                )
                self._session = None
                return False

            labels = load_scene_labels(self.labels_path)
            if not labels:
                logger.warning(
                    "Scene labels %s empty/unreadable; scene analysis skipped",
                    self.labels_path,
                )
                self._session = None
                return False

            self._session = session
            self._input_name = session.get_inputs()[0].name
            self._labels = labels
            self._model_mtime = mtime

        return self._session is not None

    @property
    def label_display_map(self) -> dict[str, str]:
        """Mapping of persisted ``scene_label`` -> Chinese display name.

        Exposed so the ExploreAPI (task 4.1) can resolve ``name_zh`` for the
        scenes aggregation without re-implementing the labels parsing.
        """
        if not self._labels:
            self._labels = load_scene_labels(self.labels_path)
        return {key: zh for key, zh in self._labels}

    # -- inference --------------------------------------------------------

    def _preprocess(self, path: str) -> Any:
        """Decode and preprocess an image into a model-ready NCHW batch.

        Resizes to ``_INPUT_SIZE`` square, scales to ``[0, 1]`` and applies the
        ImageNet mean/std normalisation, returning a ``float32`` array shaped
        ``(1, 3, H, W)``.
        """
        import numpy as np

        from app.services.image_utils import open_image

        with open_image(path) as img:
            img = img.convert("RGB").resize((_INPUT_SIZE, _INPUT_SIZE))
            arr = np.asarray(img, dtype=np.float32) / 255.0

        mean = np.array(_IMAGENET_MEAN, dtype=np.float32)
        std = np.array(_IMAGENET_STD, dtype=np.float32)
        arr = (arr - mean) / std
        # HWC -> CHW, add batch dim.
        arr = np.transpose(arr, (2, 0, 1))[np.newaxis, ...]
        return np.ascontiguousarray(arr, dtype=np.float32)

    def _run_inference(self, path: str) -> list[tuple[str, float]]:
        """Run the model on ``path`` and return top-k ``(label_key, confidence)``.

        Returns candidate predictions (before threshold filtering), softmaxed to
        probabilities and mapped to label keys. Returns an empty list when the
        model is unavailable or inference fails. This is the single side-effect
        boundary for inference and is the intended monkeypatch point in tests.
        """
        if not self._ensure_loaded():
            return []

        import numpy as np

        try:
            batch = self._preprocess(path)
            outputs = self._session.run(None, {self._input_name: batch})
        except Exception as exc:
            logger.warning("Scene inference failed for %s: %s", path, exc)
            return []

        logits = np.asarray(outputs[0]).reshape(-1)
        probs = _softmax(logits)

        num = min(len(probs), len(self._labels))
        if num == 0:
            return []

        order = np.argsort(probs[:num])[::-1][:_TOP_K]
        results: list[tuple[str, float]] = []
        for idx in order:
            label_key, _name_zh = self._labels[int(idx)]
            results.append((label_key, float(probs[int(idx)])))
        return results

    # -- orchestration ----------------------------------------------------

    async def analyze(self, user_id: int, file_id: int, path: str) -> None:
        """Classify a photo's scene(s) and persist them to ``photo_scenes``.

        Args:
            user_id: Owner of the file (persisted for user isolation, req 3.7).
            file_id: ``file_records.id`` of the photo being analyzed.
            path: Absolute filesystem path to the decoded image.

        Predictions below ``settings.scene_min_confidence`` are dropped
        (requirement 3.2). Existing rows for ``file_id`` are removed before
        insert so re-analysis stays idempotent (requirement 7.5). A safe no-op
        when the analyzer is unavailable (requirements 3.6, 9.2).
        """
        if not self.available:
            return

        predictions = self._run_inference(path)
        if not predictions:
            # No usable predictions: still clear stale rows so a re-analysis of a
            # now-empty result does not leave orphaned scenes.
            await self._write_scenes(user_id=user_id, file_id=file_id, kept=[])
            return

        scores = [conf for _label, conf in predictions]
        labels = [label for label, _conf in predictions]
        min_conf = float(getattr(self._settings, "scene_min_confidence", 0.0))
        kept = filter_scenes(scores, labels, min_conf)

        await self._write_scenes(user_id=user_id, file_id=file_id, kept=kept)

    async def _write_scenes(
        self,
        *,
        user_id: int,
        file_id: int,
        kept: list[tuple[str, float]],
    ) -> None:
        """Persist scene rows for a file, replacing any existing rows.

        Opens its own aiosqlite connection (worker-isolated) so the analyzer can
        be driven from a background task without sharing a request connection.
        """
        from app.core.database import _create_connection

        db = await _create_connection(self._settings.database_url)
        try:
            # Requirement 7.5: clear prior results so re-analysis is idempotent.
            await db.execute(
                "DELETE FROM photo_scenes WHERE file_id = ?", (file_id,)
            )
            if kept:
                await db.executemany(
                    """
                    INSERT INTO photo_scenes (file_id, user_id, scene_label, confidence)
                    VALUES (?, ?, ?, ?)
                    """,
                    [
                        (file_id, user_id, label, confidence)
                        for label, confidence in kept
                    ],
                )
            await db.commit()
        finally:
            await db.close()
