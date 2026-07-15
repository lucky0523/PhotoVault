"""Unit tests for SceneAnalyzer (availability, labels parsing, persistence).

These tests cover the stateful analyzer behaviour of task 5.3 without requiring
a real ONNX model or the onnxruntime dependency:

- ``available`` is False when the model / labels files are missing (req 9.2);
- ``analyze`` is a safe no-op when the analyzer is unavailable (req 3.6, 9.2);
- when inference results are injected, kept labels are persisted with the file
  owner's ``user_id`` (req 3.7) after threshold filtering (req 3.2);
- re-analysis replaces prior rows rather than accumulating (req 7.5);
- ``load_scene_labels`` parses the supported labels_zh.json shapes.

The inference boundary (``_run_inference``) is substituted so the real
threshold-filter + SQLite write path is exercised deterministically, and the
tests do not depend on onnxruntime being installed.
"""

import json
import types

import aiosqlite
import pytest

from app.core.database import init_db
from app.services.scene_analyzer import (
    SceneAnalyzer,
    filter_scenes,
    load_scene_labels,
)


def _make_settings(tmp_path, min_conf=0.3):
    """Build a minimal settings-like object for the analyzer."""
    models_root = tmp_path / "models"
    db_path = tmp_path / "photovault.db"
    return types.SimpleNamespace(
        models_root=str(models_root),
        database_url=str(db_path),
        scene_min_confidence=min_conf,
    )


async def _setup_db(db_url: str) -> int:
    """Init schema and insert a user + file_record; return the file_id."""
    await init_db(db_url)
    db = await aiosqlite.connect(db_url)
    try:
        await db.execute("PRAGMA foreign_keys=ON;")
        await db.execute(
            "INSERT INTO users (id, username, password_hash) VALUES (1, 'alice', 'h')"
        )
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name)
               VALUES (10, 1, 'hash', '/f', '/o', 'dev', 100, 'a.jpg')"""
        )
        await db.commit()
    finally:
        await db.close()
    return 10


async def _fetch_scenes(db_url: str, file_id: int):
    db = await aiosqlite.connect(db_url)
    db.row_factory = aiosqlite.Row
    try:
        cursor = await db.execute(
            "SELECT * FROM photo_scenes WHERE file_id = ? ORDER BY confidence DESC",
            (file_id,),
        )
        return await cursor.fetchall()
    finally:
        await db.close()


def _install_model_files(analyzer, labels):
    """Create placeholder model + labels files so path checks pass."""
    analyzer.model_path.parent.mkdir(parents=True, exist_ok=True)
    analyzer.model_path.write_bytes(b"not-a-real-model")
    with open(analyzer.labels_path, "w", encoding="utf-8") as f:
        json.dump(labels, f, ensure_ascii=False)


# ---------------------------------------------------------------------------
# Availability / graceful degradation
# ---------------------------------------------------------------------------


def test_available_false_when_files_missing(tmp_path):
    """With no model/labels installed, the analyzer reports unavailable."""
    settings = _make_settings(tmp_path)
    analyzer = SceneAnalyzer(settings=settings)
    assert analyzer.available is False


def test_available_false_when_runtime_missing(tmp_path):
    """If the ONNX runtime import fails, available is False even with files."""
    settings = _make_settings(tmp_path)
    analyzer = SceneAnalyzer(settings=settings)
    _install_model_files(analyzer, ["海滩", "公园"])
    # Force the runtime probe to report the optional dependency as missing.
    analyzer._runtime_ok = False
    assert analyzer.available is False


def test_available_true_when_runtime_and_files_present(tmp_path):
    """available is True only when runtime + model + labels are all present."""
    settings = _make_settings(tmp_path)
    analyzer = SceneAnalyzer(settings=settings)
    _install_model_files(analyzer, ["海滩", "公园"])
    analyzer._runtime_ok = True  # simulate onnxruntime importable
    assert analyzer.available is True


async def test_analyze_is_noop_when_unavailable(tmp_path):
    """analyze writes nothing when the analyzer is unavailable."""
    settings = _make_settings(tmp_path)
    file_id = await _setup_db(settings.database_url)
    analyzer = SceneAnalyzer(settings=settings)
    assert analyzer.available is False

    await analyzer.analyze(user_id=1, file_id=file_id, path="/nonexistent.jpg")

    rows = await _fetch_scenes(settings.database_url, file_id)
    assert rows == []


# ---------------------------------------------------------------------------
# Write / threshold-filter / idempotency path (inference injected)
# ---------------------------------------------------------------------------


async def test_analyze_persists_filtered_scenes(tmp_path, monkeypatch):
    """Kept predictions (>= threshold) are persisted with the file owner id."""
    settings = _make_settings(tmp_path, min_conf=0.3)
    file_id = await _setup_db(settings.database_url)
    analyzer = SceneAnalyzer(settings=settings)
    _install_model_files(analyzer, ["beach", "park", "screenshot"])
    analyzer._runtime_ok = True

    # Inject inference results: one below threshold should be dropped.
    monkeypatch.setattr(
        analyzer,
        "_run_inference",
        lambda path: [("beach", 0.8), ("park", 0.5), ("screenshot", 0.1)],
    )

    await analyzer.analyze(user_id=1, file_id=file_id, path="/img.jpg")

    rows = await _fetch_scenes(settings.database_url, file_id)
    labels = [r["scene_label"] for r in rows]
    assert labels == ["beach", "park"]  # sorted by confidence desc, 0.1 dropped
    assert all(r["user_id"] == 1 for r in rows)
    assert all(r["confidence"] >= 0.3 for r in rows)


async def test_analyze_all_below_threshold_writes_nothing(tmp_path, monkeypatch):
    """When every prediction is below threshold, no rows are written."""
    settings = _make_settings(tmp_path, min_conf=0.9)
    file_id = await _setup_db(settings.database_url)
    analyzer = SceneAnalyzer(settings=settings)
    _install_model_files(analyzer, ["beach", "park"])
    analyzer._runtime_ok = True

    monkeypatch.setattr(
        analyzer, "_run_inference", lambda path: [("beach", 0.4), ("park", 0.2)]
    )

    await analyzer.analyze(user_id=1, file_id=file_id, path="/img.jpg")

    rows = await _fetch_scenes(settings.database_url, file_id)
    assert rows == []


async def test_reanalysis_is_idempotent(tmp_path, monkeypatch):
    """Re-analyzing replaces prior rows instead of accumulating them."""
    settings = _make_settings(tmp_path, min_conf=0.3)
    file_id = await _setup_db(settings.database_url)
    analyzer = SceneAnalyzer(settings=settings)
    _install_model_files(analyzer, ["beach", "park"])
    analyzer._runtime_ok = True

    monkeypatch.setattr(
        analyzer, "_run_inference", lambda path: [("beach", 0.8), ("park", 0.6)]
    )
    await analyzer.analyze(user_id=1, file_id=file_id, path="/img.jpg")
    await analyzer.analyze(user_id=1, file_id=file_id, path="/img.jpg")

    rows = await _fetch_scenes(settings.database_url, file_id)
    assert len(rows) == 2  # not 4


async def test_reanalysis_to_empty_clears_prior_rows(tmp_path, monkeypatch):
    """A re-analysis that yields no predictions clears stale rows."""
    settings = _make_settings(tmp_path, min_conf=0.3)
    file_id = await _setup_db(settings.database_url)
    analyzer = SceneAnalyzer(settings=settings)
    _install_model_files(analyzer, ["beach", "park"])
    analyzer._runtime_ok = True

    monkeypatch.setattr(analyzer, "_run_inference", lambda path: [("beach", 0.8)])
    await analyzer.analyze(user_id=1, file_id=file_id, path="/img.jpg")
    assert len(await _fetch_scenes(settings.database_url, file_id)) == 1

    monkeypatch.setattr(analyzer, "_run_inference", lambda path: [])
    await analyzer.analyze(user_id=1, file_id=file_id, path="/img.jpg")
    assert await _fetch_scenes(settings.database_url, file_id) == []


# ---------------------------------------------------------------------------
# Labels parsing
# ---------------------------------------------------------------------------


def test_load_scene_labels_plain_list(tmp_path):
    """A list of zh strings maps each to itself as both key and display name."""
    p = tmp_path / "labels.json"
    p.write_text(json.dumps(["海滩", "公园"], ensure_ascii=False), encoding="utf-8")
    assert load_scene_labels(p) == [("海滩", "海滩"), ("公园", "公园")]


def test_load_scene_labels_object_list(tmp_path):
    """A list of {label, name_zh} objects preserves english key + zh name."""
    p = tmp_path / "labels.json"
    p.write_text(
        json.dumps(
            [
                {"label": "beach", "name_zh": "海滩"},
                {"label": "park", "name_zh": "公园"},
            ],
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    assert load_scene_labels(p) == [("beach", "海滩"), ("park", "公园")]


def test_load_scene_labels_english_dict(tmp_path):
    """A {english: zh} dict uses the english label as the stable key."""
    p = tmp_path / "labels.json"
    p.write_text(
        json.dumps({"beach": "海滩", "park": "公园"}, ensure_ascii=False),
        encoding="utf-8",
    )
    assert load_scene_labels(p) == [("beach", "海滩"), ("park", "公园")]


def test_load_scene_labels_indexed_dict(tmp_path):
    """A {index: zh} dict orders by integer index and uses zh as key."""
    p = tmp_path / "labels.json"
    p.write_text(
        json.dumps({"1": "公园", "0": "海滩"}, ensure_ascii=False),
        encoding="utf-8",
    )
    assert load_scene_labels(p) == [("海滩", "海滩"), ("公园", "公园")]


def test_load_scene_labels_missing_file(tmp_path):
    """A missing labels file degrades to an empty list, not an error."""
    assert load_scene_labels(tmp_path / "nope.json") == []


def test_label_display_map_exposed_for_api(tmp_path):
    """label_display_map resolves stored label keys to zh display names."""
    settings = _make_settings(tmp_path)
    analyzer = SceneAnalyzer(settings=settings)
    _install_model_files(analyzer, [{"label": "beach", "name_zh": "海滩"}])
    assert analyzer.label_display_map == {"beach": "海滩"}


# ---------------------------------------------------------------------------
# filter_scenes sanity (pure function already property-tested elsewhere)
# ---------------------------------------------------------------------------


def test_filter_scenes_dedups_keeping_highest():
    """Duplicate labels collapse to the highest-confidence occurrence."""
    result = filter_scenes([0.4, 0.9, 0.5], ["a", "a", "b"], 0.3)
    assert result == [("a", 0.9), ("b", 0.5)]
