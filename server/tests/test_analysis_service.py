"""Unit tests for AnalysisService orchestration (task 3.1).

Covers:
- physical path resolution from file_records, including reference following and
  missing rows (Requirement 7.1);
- FeatureFlag + analyzer.available gating decides which dimensions run
  (Requirement 7.2 / 9.2);
- per-dimension exception isolation: one dimension failing is logged and does
  not prevent the others from running (Requirement 7.4).

The three analyzers are substituted with lightweight fakes so the test targets
the orchestration logic itself, not the analyzers' internals.
"""

import types

import aiosqlite
import pytest

from app.core.database import init_db
from app.services.analysis_service import AnalysisService


def _make_settings(tmp_path, *, enable_place=True, enable_scene=True, enable_face=True):
    db_path = tmp_path / "photovault.db"
    return types.SimpleNamespace(
        database_url=str(db_path),
        models_root=str(tmp_path / "models"),
        enable_place=enable_place,
        enable_scene=enable_scene,
        enable_face=enable_face,
    )


async def _setup_db(db_url: str) -> None:
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
        # Regular file for user 1.
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name)
               VALUES (10, 1, 'h1', '/data/alice/dev/a.jpg', '/o', 'dev', 100, 'a.jpg')"""
        )
        # Reference record for user 1 pointing at the real file id 10.
        await db.execute(
            """INSERT INTO file_records
               (id, user_id, file_hash, file_path, original_path,
                device_name, file_size, file_name, is_reference, reference_to)
               VALUES (11, 1, 'h1', '/ignored/ref.jpg', '/o', 'dev', 100, 'a.jpg', 1, 10)"""
        )
        await db.commit()
    finally:
        await db.close()


class FakeAnalyzer:
    """Records analyze() calls; optionally raises to simulate failure."""

    def __init__(self, *, available=True, raises=False):
        self._available = available
        self._raises = raises
        self.calls: list[tuple[int, int, str]] = []

    @property
    def available(self) -> bool:
        return self._available

    async def analyze(self, user_id: int, file_id: int, path: str) -> None:
        self.calls.append((user_id, file_id, path))
        if self._raises:
            raise RuntimeError("boom")


def _service(settings, place, scene, face):
    return AnalysisService(
        settings,
        place_analyzer=place,
        scene_analyzer=scene,
        face_analyzer=face,
    )


async def test_resolves_regular_path_and_dispatches(tmp_path):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    place, scene, face = FakeAnalyzer(), FakeAnalyzer(), FakeAnalyzer()

    await _service(settings, place, scene, face).analyze_file(user_id=1, file_id=10)

    assert place.calls == [(1, 10, "/data/alice/dev/a.jpg")]
    assert scene.calls == [(1, 10, "/data/alice/dev/a.jpg")]
    assert face.calls == [(1, 10, "/data/alice/dev/a.jpg")]


async def test_follows_reference_to_actual_file_path(tmp_path):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    place, scene, face = FakeAnalyzer(), FakeAnalyzer(), FakeAnalyzer()

    # file_id 11 is a reference to file 10; analyzers must get file 10's path.
    await _service(settings, place, scene, face).analyze_file(user_id=1, file_id=11)

    assert place.calls == [(1, 11, "/data/alice/dev/a.jpg")]


async def test_missing_file_skips_all_dimensions(tmp_path):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    place, scene, face = FakeAnalyzer(), FakeAnalyzer(), FakeAnalyzer()

    await _service(settings, place, scene, face).analyze_file(user_id=1, file_id=999)

    assert place.calls == []
    assert scene.calls == []
    assert face.calls == []


async def test_user_isolation_on_path_resolution(tmp_path):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    place, scene, face = FakeAnalyzer(), FakeAnalyzer(), FakeAnalyzer()

    # File 10 belongs to user 1; user 2 must not resolve it.
    await _service(settings, place, scene, face).analyze_file(user_id=2, file_id=10)

    assert place.calls == []


async def test_feature_flag_disables_dimension(tmp_path):
    settings = _make_settings(tmp_path, enable_scene=False)
    await _setup_db(settings.database_url)
    place, scene, face = FakeAnalyzer(), FakeAnalyzer(), FakeAnalyzer()

    await _service(settings, place, scene, face).analyze_file(user_id=1, file_id=10)

    assert place.calls  # ran
    assert scene.calls == []  # gated off by flag
    assert face.calls  # ran


async def test_unavailable_analyzer_is_skipped(tmp_path):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    place = FakeAnalyzer(available=False)
    scene, face = FakeAnalyzer(), FakeAnalyzer()

    await _service(settings, place, scene, face).analyze_file(user_id=1, file_id=10)

    assert place.calls == []  # gated off by availability
    assert scene.calls
    assert face.calls


async def test_dimension_failure_is_isolated(tmp_path):
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)
    place = FakeAnalyzer(raises=True)
    scene, face = FakeAnalyzer(), FakeAnalyzer()

    # Must not raise despite place blowing up.
    await _service(settings, place, scene, face).analyze_file(user_id=1, file_id=10)

    assert place.calls  # attempted
    assert scene.calls  # still ran
    assert face.calls  # still ran


async def test_default_analyzers_are_instantiated(tmp_path):
    """Without injection the service wires real analyzers; scene/face stubs are
    unavailable so only place is considered (and place is unavailable without a
    cities.db, so nothing runs but no error is raised)."""
    settings = _make_settings(tmp_path)
    await _setup_db(settings.database_url)

    service = AnalysisService(settings)
    # Stubs report available=False.
    assert service._scene.available is False
    assert service._face.available is False

    # Should complete without error.
    await service.analyze_file(user_id=1, file_id=10)
