"""Analysis orchestration.

:class:`AnalysisService` is the single entry point the background worker (and
the backfill script) calls to analyze one photo across all enabled dimensions:
place, scene and face.

For each dimension it applies two gates before running the analyzer:

1. the :class:`~app.core.config.Settings` FeatureFlag
   (``enable_place`` / ``enable_scene`` / ``enable_face``), and
2. the analyzer's own ``available`` flag (its resource files / runtime are
   installed and loadable).

Each dimension is wrapped in its own ``try``/``except`` so a failure in one
dimension is logged and isolated — it never propagates to the caller nor
prevents the remaining dimensions from running (requirement 7.4). Missing
optional dependencies or resource files simply leave the analyzer
``available = False`` and the dimension is skipped (requirement 9.2).

The physical file path handed to each analyzer is resolved from
``file_records`` exactly like the download path resolution
(``FileBrowseService._resolve_file_path``): the ``file_path`` column already
holds the absolute on-disk path, and reference records are followed to their
target file (requirement 7.1).
"""

from __future__ import annotations

import logging
from typing import Any, Optional

logger = logging.getLogger("photovault.analysis_service")


class AnalysisService:
    """Orchestrate per-photo analysis across place / scene / face dimensions."""

    def __init__(
        self,
        settings: Any = None,
        *,
        place_analyzer: Any = None,
        scene_analyzer: Any = None,
        face_analyzer: Any = None,
    ) -> None:
        """Create the orchestrator.

        Args:
            settings: Optional settings object exposing the ``enable_*`` flags
                and ``database_url``. Defaults to the application settings
                singleton. Injectable for testing.
            place_analyzer: Optional :class:`~app.services.place_analyzer.PlaceAnalyzer`
                override (injectable for testing).
            scene_analyzer: Optional ``SceneAnalyzer`` override.
            face_analyzer: Optional ``FaceAnalyzer`` override.
        """
        if settings is None:
            from app.core.config import get_settings

            settings = get_settings()
        self._settings = settings

        if place_analyzer is None:
            from app.services.place_analyzer import PlaceAnalyzer

            place_analyzer = PlaceAnalyzer(settings)
        if scene_analyzer is None:
            from app.services.scene_analyzer import SceneAnalyzer

            scene_analyzer = SceneAnalyzer(settings)
        if face_analyzer is None:
            from app.services.face_analyzer import FaceAnalyzer

            face_analyzer = FaceAnalyzer(settings)

        self._place = place_analyzer
        self._scene = scene_analyzer
        self._face = face_analyzer

    async def analyze_file(self, user_id: int, file_id: int) -> None:
        """Analyze a single photo across all enabled, available dimensions.

        Resolves the photo's physical path, then dispatches to each analyzer
        gated by its FeatureFlag and ``available`` flag. Every dimension runs in
        isolation: a failure in one is logged and swallowed so the others still
        run (requirement 7.4).

        Args:
            user_id: Owner of the file (for user isolation).
            file_id: ``file_records.id`` of the photo to analyze.
        """
        path = await self._resolve_physical_path(user_id, file_id)
        if path is None:
            logger.warning(
                "Skipping analysis: could not resolve path for file_id=%s user_id=%s",
                file_id,
                user_id,
            )
            return

        settings = self._settings

        if settings.enable_place and self._place.available:
            await self._run_dimension("place", self._place, user_id, file_id, path)
        if settings.enable_scene and self._scene.available:
            await self._run_dimension("scene", self._scene, user_id, file_id, path)
        if settings.enable_face and self._face.available:
            await self._run_dimension("face", self._face, user_id, file_id, path)

    async def _run_dimension(
        self,
        name: str,
        analyzer: Any,
        user_id: int,
        file_id: int,
        path: str,
    ) -> None:
        """Run one analyzer with per-dimension exception isolation.

        Any exception raised by the analyzer is logged and suppressed so it
        cannot propagate to the caller or block the remaining dimensions
        (requirement 7.4).
        """
        try:
            await analyzer.analyze(user_id, file_id, path)
        except Exception:  # noqa: BLE001 - deliberate per-dimension isolation
            logger.exception(
                "%s analysis failed for file_id=%s user_id=%s; skipping dimension",
                name,
                file_id,
                user_id,
            )

    async def _resolve_physical_path(
        self, user_id: int, file_id: int
    ) -> Optional[str]:
        """Resolve the absolute on-disk path for a file, honoring references.

        Mirrors ``FileBrowseService._resolve_file_path``: the ``file_path``
        column stores the absolute physical path, and reference records
        (``is_reference`` + ``reference_to``) point at the actual stored file.
        The lookup is scoped to ``user_id`` for isolation.

        Returns:
            The absolute filesystem path, or ``None`` when the file (or the
            reference target) does not exist for this user.
        """
        from app.core.database import _create_connection

        db = await _create_connection(self._settings.database_url)
        try:
            cursor = await db.execute(
                """SELECT file_path, is_reference, reference_to
                   FROM file_records
                   WHERE id = ? AND user_id = ?""",
                (file_id, user_id),
            )
            row = await cursor.fetchone()
            if row is None:
                return None

            if row["is_reference"] and row["reference_to"] is not None:
                ref_cursor = await db.execute(
                    "SELECT file_path FROM file_records WHERE id = ?",
                    (row["reference_to"],),
                )
                ref_row = await ref_cursor.fetchone()
                if ref_row is None:
                    return None
                return ref_row["file_path"]

            return row["file_path"]
        finally:
            await db.close()
