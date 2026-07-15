"""Explore API endpoints (people / places / scenes).

Aggregation and detail endpoints backing the Web「探索」page. Photos are
grouped along three dimensions produced by the optional analysis pipeline:

- **people**  — face clusters (``face_clusters`` / ``faces``)
- **places**  — cities from reverse-geocoded GPS (``photo_gps``)
- **scenes**  — scene labels from the scene model (``photo_scenes``)

All endpoints require authentication and are strictly scoped to
``current_user.id`` for user isolation. Aggregation endpoints return an empty
list (never an error) when the corresponding table has no rows, so the Web UI
can degrade gracefully (requirement 8.4).

The optional ``library`` query parameter maps to ``file_records.device_name``:
when empty/absent it means「全部图库」(no filter); when set, only photos from
that device/library are considered — applied consistently to both the
aggregation and detail queries.

All photo/detail queries exclude trashed and purged files
(``deleted_at IS NULL AND purged_at IS NULL``) to match the browse behavior.

Endpoints implemented here (task 4.1):
    GET  /explore/people            aggregation
    GET  /explore/places            aggregation
    GET  /explore/scenes            aggregation
    GET  /explore/people/{cluster_id}   detail (paginated)
    GET  /explore/places/{city}         detail (paginated)
    GET  /explore/scenes/{label}        detail (paginated)
    PUT  /explore/people/{cluster_id}   rename cluster

Resource status / upload / reanalyze endpoints are added by task 4.2.
"""

from __future__ import annotations

import logging
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

import aiosqlite
from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from pydantic import BaseModel

from app.api.files import FileInfoResponse
from app.core.config import get_settings
from app.core.database import get_db
from app.core.security import get_current_user, require_admin
from app.models.auth import UserInfo
from app.services.analysis_queue import enqueue_reanalysis_all

logger = logging.getLogger("photovault.api.explore")

router = APIRouter()


# ---------------------------------------------------------------------------
# Response models
# ---------------------------------------------------------------------------


class PersonClusterResponse(BaseModel):
    """A person (face cluster) entry in the people aggregation."""

    cluster_id: int
    name: str
    face_count: int
    cover_file_id: Optional[int] = None
    cover_face_bbox: Optional[str] = None


class PlaceGroupResponse(BaseModel):
    """A city grouping in the places aggregation."""

    city: str
    province: Optional[str] = None
    country: Optional[str] = None
    count: int
    cover_file_id: Optional[int] = None


class SceneGroupResponse(BaseModel):
    """A scene-label grouping in the scenes aggregation."""

    label: str
    name_zh: str
    count: int
    cover_file_id: Optional[int] = None


class ExplorePhotosResponse(BaseModel):
    """Paginated list of photos under a person / place / scene."""

    files: List[FileInfoResponse] = []
    total: int = 0
    page: int = 1
    page_size: int = 50


class RenamePersonRequest(BaseModel):
    """Body for renaming a person cluster."""

    name: str


class RenamePersonResponse(BaseModel):
    """Result of a person rename."""

    success: bool
    name: str


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Columns selected for photo detail rows, matching FileInfoResponse fields.
_PHOTO_COLUMNS = (
    "fr.id, fr.file_name, fr.file_size, fr.mime_type, fr.exif_time, "
    "fr.media_type, fr.is_motion_photo, fr.is_ultra_hdr, fr.created_at, "
    "fr.device_name, fr.focal_length"
)


def _row_to_file_info(row: aiosqlite.Row) -> FileInfoResponse:
    """Convert a photo detail row into a FileInfoResponse."""
    return FileInfoResponse(
        id=row["id"],
        file_name=row["file_name"],
        file_size=row["file_size"],
        mime_type=row["mime_type"],
        exif_time=row["exif_time"],
        media_type=row["media_type"] or "image",
        is_motion_photo=bool(row["is_motion_photo"]),
        is_ultra_hdr=bool(row["is_ultra_hdr"]),
        created_at=row["created_at"],
        thumbnail_url=f"/api/v1/files/thumbnail/{row['id']}",
        device_name=row["device_name"],
        focal_length=row["focal_length"],
    )


def _library_clause(library: Optional[str], params: list) -> str:
    """Append an optional device_name filter and return the SQL fragment.

    When ``library`` is falsy (empty/None) no filter is applied ("全部图库").
    Otherwise a ``AND fr.device_name = ?`` fragment is returned and the value
    appended to ``params``.
    """
    if library:
        params.append(library)
        return " AND fr.device_name = ?"
    return ""


async def _paginate_photos(
    db: aiosqlite.Connection,
    *,
    join_sql: str,
    where_sql: str,
    params: list,
    page: int,
    page_size: int,
    distinct: bool = False,
) -> ExplorePhotosResponse:
    """Run a paginated photo query and build an ExplorePhotosResponse.

    Args:
        join_sql: JOIN clause(s) linking ``file_records fr`` to the dimension
            table (e.g. ``JOIN faces fa ON fa.file_id = fr.id``).
        where_sql: WHERE conditions (without the leading ``WHERE``).
        params: Bound parameters for the WHERE/JOIN clauses.
        distinct: Whether a file may match multiple dimension rows and thus
            needs de-duplication.
    """
    offset = (page - 1) * page_size

    count_expr = "COUNT(DISTINCT fr.id)" if distinct else "COUNT(*)"
    count_sql = (
        f"SELECT {count_expr} AS cnt FROM file_records fr {join_sql} WHERE {where_sql}"
    )
    cursor = await db.execute(count_sql, params)
    count_row = await cursor.fetchone()
    total = count_row["cnt"] if count_row else 0

    select_kw = "SELECT DISTINCT" if distinct else "SELECT"
    list_sql = (
        f"{select_kw} {_PHOTO_COLUMNS} "
        f"FROM file_records fr {join_sql} WHERE {where_sql} "
        f"ORDER BY COALESCE(fr.exif_time, fr.created_at) DESC, fr.id DESC "
        f"LIMIT ? OFFSET ?"
    )
    cursor = await db.execute(list_sql, [*params, page_size, offset])
    rows = await cursor.fetchall()

    return ExplorePhotosResponse(
        files=[_row_to_file_info(r) for r in rows],
        total=total,
        page=page,
        page_size=page_size,
    )


# ---------------------------------------------------------------------------
# People
# ---------------------------------------------------------------------------


@router.get("/explore/people", response_model=List[PersonClusterResponse])
async def list_people(
    library: Optional[str] = Query(default=None, description="Filter by device/library"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> List[PersonClusterResponse]:
    """List person clusters for the current user, ordered by face count.

    ``face_count`` reflects only visible (non-trashed) photos within the
    selected library. Clusters with no visible faces are omitted. The display
    name falls back to ``人物{n}`` when the cluster has no custom name. Returns
    an empty list when there are no clusters/faces (requirement 8.4).
    """
    params: list = [current_user.id]
    lib = _library_clause(library, params)

    cursor = await db.execute(
        f"""
        SELECT fc.id AS cluster_id, fc.display_name, fc.cover_face_id,
               COUNT(fa.id) AS face_count
        FROM face_clusters fc
        JOIN faces fa ON fa.cluster_id = fc.id
        JOIN file_records fr ON fr.id = fa.file_id
        WHERE fc.user_id = ?
          AND fr.deleted_at IS NULL AND fr.purged_at IS NULL{lib}
        GROUP BY fc.id
        HAVING COUNT(fa.id) > 0
        ORDER BY COUNT(fa.id) DESC, fc.id ASC
        """,
        params,
    )
    rows = await cursor.fetchall()

    results: List[PersonClusterResponse] = []
    for idx, row in enumerate(rows):
        cluster_id = row["cluster_id"]
        cover_file_id, cover_bbox = await _resolve_person_cover(
            db, cluster_id=cluster_id, cover_face_id=row["cover_face_id"], library=library
        )
        name = row["display_name"] or f"人物{idx + 1}"
        results.append(
            PersonClusterResponse(
                cluster_id=cluster_id,
                name=name,
                face_count=row["face_count"],
                cover_file_id=cover_file_id,
                cover_face_bbox=cover_bbox,
            )
        )
    return results


async def _resolve_person_cover(
    db: aiosqlite.Connection,
    *,
    cluster_id: int,
    cover_face_id: Optional[int],
    library: Optional[str],
) -> tuple[Optional[int], Optional[str]]:
    """Resolve a visible cover (file_id, bbox) for a cluster.

    Prefers the designated ``cover_face_id`` when its file is visible under the
    current library filter, otherwise falls back to the highest detection-score
    visible face in the cluster.
    """
    params: list = [cluster_id]
    lib = _library_clause(library, params)
    # Prioritise the designated cover face (if visible), else best det_score.
    params.append(cover_face_id if cover_face_id is not None else -1)
    cursor = await db.execute(
        f"""
        SELECT fa.file_id, fa.bbox
        FROM faces fa
        JOIN file_records fr ON fr.id = fa.file_id
        WHERE fa.cluster_id = ?
          AND fr.deleted_at IS NULL AND fr.purged_at IS NULL{lib}
        ORDER BY (fa.id = ?) DESC, fa.det_score DESC, fa.id ASC
        LIMIT 1
        """,
        params,
    )
    row = await cursor.fetchone()
    if row is None:
        return None, None
    return row["file_id"], row["bbox"]


@router.get("/explore/people/{cluster_id}", response_model=ExplorePhotosResponse)
async def list_person_photos(
    cluster_id: int,
    library: Optional[str] = Query(default=None, description="Filter by device/library"),
    page: int = Query(default=1, ge=1, description="Page number"),
    page_size: int = Query(default=50, ge=1, le=200, description="Items per page"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> ExplorePhotosResponse:
    """List photos that contain a face in the given cluster (paginated)."""
    params: list = [cluster_id, current_user.id]
    lib = _library_clause(library, params)
    where_sql = (
        "fa.cluster_id = ? AND fr.user_id = ? "
        "AND fr.deleted_at IS NULL AND fr.purged_at IS NULL" + lib
    )
    return await _paginate_photos(
        db,
        join_sql="JOIN faces fa ON fa.file_id = fr.id",
        where_sql=where_sql,
        params=params,
        page=page,
        page_size=page_size,
        distinct=True,
    )


@router.put("/explore/people/{cluster_id}", response_model=RenamePersonResponse)
async def rename_person(
    cluster_id: int,
    body: RenamePersonRequest,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> RenamePersonResponse:
    """Set/modify the display name of a person cluster.

    Returns 404 when the cluster does not exist or is not owned by the user.
    """
    name = body.name.strip()

    cursor = await db.execute(
        "SELECT id FROM face_clusters WHERE id = ? AND user_id = ?",
        (cluster_id, current_user.id),
    )
    if await cursor.fetchone() is None:
        raise HTTPException(status_code=404, detail="Person cluster not found")

    await db.execute(
        "UPDATE face_clusters SET display_name = ? WHERE id = ? AND user_id = ?",
        (name, cluster_id, current_user.id),
    )
    await db.commit()

    return RenamePersonResponse(success=True, name=name)


# ---------------------------------------------------------------------------
# Places
# ---------------------------------------------------------------------------


@router.get("/explore/places", response_model=List[PlaceGroupResponse])
async def list_places(
    library: Optional[str] = Query(default=None, description="Filter by device/library"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> List[PlaceGroupResponse]:
    """List cities aggregated from photo GPS, ordered by photo count.

    Photos without a resolved city are skipped. Returns an empty list when
    there is no GPS data (requirement 8.4).
    """
    params: list = [current_user.id]
    lib = _library_clause(library, params)

    cursor = await db.execute(
        f"""
        SELECT pg.city AS city,
               MAX(pg.province) AS province,
               MAX(pg.country) AS country,
               COUNT(*) AS count,
               MIN(pg.file_id) AS cover_file_id
        FROM photo_gps pg
        JOIN file_records fr ON fr.id = pg.file_id
        WHERE pg.user_id = ?
          AND pg.city IS NOT NULL AND pg.city != ''
          AND fr.deleted_at IS NULL AND fr.purged_at IS NULL{lib}
        GROUP BY pg.city
        ORDER BY count DESC, pg.city ASC
        """,
        params,
    )
    rows = await cursor.fetchall()

    return [
        PlaceGroupResponse(
            city=row["city"],
            province=row["province"],
            country=row["country"],
            count=row["count"],
            cover_file_id=row["cover_file_id"],
        )
        for row in rows
    ]


@router.get("/explore/places/{city}", response_model=ExplorePhotosResponse)
async def list_place_photos(
    city: str,
    library: Optional[str] = Query(default=None, description="Filter by device/library"),
    page: int = Query(default=1, ge=1, description="Page number"),
    page_size: int = Query(default=50, ge=1, le=200, description="Items per page"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> ExplorePhotosResponse:
    """List photos taken in the given city (paginated)."""
    params: list = [city, current_user.id]
    lib = _library_clause(library, params)
    where_sql = (
        "pg.city = ? AND fr.user_id = ? "
        "AND fr.deleted_at IS NULL AND fr.purged_at IS NULL" + lib
    )
    return await _paginate_photos(
        db,
        join_sql="JOIN photo_gps pg ON pg.file_id = fr.id",
        where_sql=where_sql,
        params=params,
        page=page,
        page_size=page_size,
        distinct=False,
    )


# ---------------------------------------------------------------------------
# Scenes
# ---------------------------------------------------------------------------


@router.get("/explore/scenes", response_model=List[SceneGroupResponse])
async def list_scenes(
    library: Optional[str] = Query(default=None, description="Filter by device/library"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> List[SceneGroupResponse]:
    """List scene labels aggregated from photo_scenes, ordered by photo count.

    The Chinese display name ``name_zh`` is resolved from the scene model's
    label map, falling back to the raw label when unavailable. Returns an empty
    list when there is no scene data (requirement 8.4).
    """
    params: list = [current_user.id]
    lib = _library_clause(library, params)

    cursor = await db.execute(
        f"""
        SELECT ps.scene_label AS label,
               COUNT(DISTINCT ps.file_id) AS count,
               MIN(ps.file_id) AS cover_file_id
        FROM photo_scenes ps
        JOIN file_records fr ON fr.id = ps.file_id
        WHERE ps.user_id = ?
          AND fr.deleted_at IS NULL AND fr.purged_at IS NULL{lib}
        GROUP BY ps.scene_label
        ORDER BY count DESC, ps.scene_label ASC
        """,
        params,
    )
    rows = await cursor.fetchall()

    display_map = _scene_display_map()

    return [
        SceneGroupResponse(
            label=row["label"],
            name_zh=display_map.get(row["label"], row["label"]),
            count=row["count"],
            cover_file_id=row["cover_file_id"],
        )
        for row in rows
    ]


def _scene_display_map() -> dict[str, str]:
    """Build the scene label -> Chinese display-name map.

    Uses the SceneAnalyzer label map when available; degrades to an empty map
    (labels used as-is) when the scene model/labels are not installed.
    """
    try:
        from app.services.scene_analyzer import SceneAnalyzer

        return SceneAnalyzer(get_settings()).label_display_map
    except Exception:  # pragma: no cover - defensive: never fail aggregation
        logger.debug("Scene label map unavailable; using raw labels", exc_info=True)
        return {}


@router.get("/explore/scenes/{label}", response_model=ExplorePhotosResponse)
async def list_scene_photos(
    label: str,
    library: Optional[str] = Query(default=None, description="Filter by device/library"),
    page: int = Query(default=1, ge=1, description="Page number"),
    page_size: int = Query(default=50, ge=1, le=200, description="Items per page"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> ExplorePhotosResponse:
    """List photos classified with the given scene label (paginated)."""
    params: list = [label, current_user.id]
    lib = _library_clause(library, params)
    where_sql = (
        "ps.scene_label = ? AND fr.user_id = ? "
        "AND fr.deleted_at IS NULL AND fr.purged_at IS NULL" + lib
    )
    return await _paginate_photos(
        db,
        join_sql="JOIN photo_scenes ps ON ps.file_id = fr.id",
        where_sql=where_sql,
        params=params,
        page=page,
        page_size=page_size,
        distinct=True,
    )


# ---------------------------------------------------------------------------
# Resource management (status / upload / reanalyze) — task 4.2
# ---------------------------------------------------------------------------
#
# Three optional AnalysisResource types back the people/scenes/places
# dimensions. None of them ship with the server (requirement 9.2); an admin
# uploads them via the manage UI and they become usable on the next analysis
# without a restart (analyzers reload by file mtime — requirement 6.3).
#
# Resource layout under ``{models_root}/``:
#     faces/      det_*.onnx (detection) + w600k_*.onnx (feature)   -> "face"
#     scenes/     scene.onnx + labels_zh.json                       -> "scene"
#     geocoding/  cities.db                                         -> "geocoding"

# Map the public resource ``type`` to its sub-directory under models_root.
_RESOURCE_DIRS: dict[str, str] = {
    "face": "faces",
    "scene": "scenes",
    "geocoding": "geocoding",
}

# Number of distinct files that must be present for a type to count as
# "installed" (face needs both detection + feature; scene needs model + labels).
_RESOURCE_REQUIRED: dict[str, int] = {"face": 2, "scene": 2, "geocoding": 1}

# Accepted upload extensions per resource type (requirement 6.7). Scene accepts
# both the ONNX model and the JSON label map; geocoding is a SQLite ``.db``.
_ALLOWED_EXTENSIONS: dict[str, set[str]] = {
    "face": {".onnx"},
    "scene": {".onnx", ".json"},
    "geocoding": {".db"},
}


class ResourceStatus(BaseModel):
    """Installation state of a single AnalysisResource type (requirement 6.1)."""

    type: str
    installed: bool
    name: Optional[str] = None
    version: Optional[str] = None
    size: int = 0
    updated_at: Optional[str] = None
    enabled: bool = False


class ResourcesResponse(BaseModel):
    """Status of the three AnalysisResource types."""

    face: ResourceStatus
    scene: ResourceStatus
    geocoding: ResourceStatus


class ResourceUploadResponse(BaseModel):
    """Result of an AnalysisResource upload."""

    success: bool
    message: str
    status: ResourceStatus


class DownloadResourceRequest(BaseModel):
    """Body for the one-click resource download/update.

    ``url`` points at an open-source model / archive / GeoNames dump. The server
    fetches and installs it (see :mod:`app.services.resource_downloader` for the
    supported shapes: direct file, ``.zip`` archive, GeoNames text dump).
    """

    url: str


class ReanalyzeRequest(BaseModel):
    """Body for the reanalyze trigger.

    ``dimensions`` is advisory/reserved for future use: analysis is gated by the
    per-dimension FeatureFlags at analysis time, so the trigger simply enqueues
    all of the user's files. It is accepted here so the Web UI can pass it.
    """

    dimensions: Optional[List[str]] = None


class ReanalyzeResponse(BaseModel):
    """Result of triggering a re-analysis of existing photos."""

    success: bool
    queued: int


class AnalysisFlags(BaseModel):
    """The three analysis feature flags (people / places / scenes)."""

    enable_place: bool
    enable_scene: bool
    enable_face: bool


class DimensionStatus(BaseModel):
    """Per-dimension analysis status counts for the current user."""

    enabled: bool
    installed: bool
    photos: int = 0       # photos that produced a result in this dimension
    groups: int = 0       # clusters (face) / cities (place) / labels (scene)


class AnalysisStatusResponse(BaseModel):
    """Overall analysis status for the current user's library."""

    total_images: int
    queue_pending: int
    runtime_ok: bool  # onnxruntime importable (required for face/scene)
    people: DimensionStatus
    places: DimensionStatus
    scenes: DimensionStatus


def _resource_files(settings: object, rtype: str) -> list[Path]:
    """Return the existing resource files for ``rtype`` (never raises).

    Only files that actually exist on disk are returned; a missing directory or
    file simply yields fewer entries (installed will be ``False``).
    """
    root = Path(getattr(settings, "models_root", "") or "")

    if rtype == "face":
        faces = root / "faces"
        found: list[Path] = []
        for pattern in ("det_*.onnx", "w600k_*.onnx"):
            try:
                matches = sorted(faces.glob(pattern)) if faces.is_dir() else []
            except OSError:  # pragma: no cover - defensive
                matches = []
            for match in matches:
                if match.is_file():
                    found.append(match)
                    break
        return found

    if rtype == "scene":
        scenes = root / "scenes"
        candidates = (scenes / "scene.onnx", scenes / "labels_zh.json")
        return [p for p in candidates if p.is_file()]

    if rtype == "geocoding":
        cities = root / "geocoding" / "cities.db"
        return [cities] if cities.is_file() else []

    return []


def _resource_enabled(settings: object, rtype: str) -> bool:
    """Return the FeatureFlag governing ``rtype`` (face/scene/place)."""
    flag_attr = {
        "face": "enable_face",
        "scene": "enable_scene",
        "geocoding": "enable_place",
    }[rtype]
    return bool(getattr(settings, flag_attr, False))


def _build_resource_status(settings: object, rtype: str) -> ResourceStatus:
    """Build a :class:`ResourceStatus` for a type, defensively (never raises).

    Missing files degrade to ``installed=False, size=0, updated_at=None``.
    """
    files = _resource_files(settings, rtype)
    required = _RESOURCE_REQUIRED.get(rtype, 1)

    size = 0
    names: list[str] = []
    latest_mtime: Optional[float] = None
    for path in files:
        try:
            stat = path.stat()
        except OSError:  # pragma: no cover - file vanished between checks
            continue
        size += stat.st_size
        names.append(path.name)
        if latest_mtime is None or stat.st_mtime > latest_mtime:
            latest_mtime = stat.st_mtime

    updated_at = (
        datetime.fromtimestamp(latest_mtime, tz=timezone.utc).isoformat()
        if latest_mtime is not None
        else None
    )

    return ResourceStatus(
        type=rtype,
        installed=len(files) >= required,
        name=", ".join(names) if names else None,
        version=None,
        size=size,
        updated_at=updated_at,
        enabled=_resource_enabled(settings, rtype),
    )


def _safe_unlink(path: Path) -> None:
    """Best-effort remove a temp file, swallowing any error."""
    try:
        path.unlink()
    except OSError:  # pragma: no cover - defensive cleanup
        pass


@router.get("/explore/resources", response_model=ResourcesResponse)
async def get_resources(
    current_user: UserInfo = Depends(get_current_user),
) -> ResourcesResponse:
    """Return the install status of the three AnalysisResource types.

    Read-only view available to any authenticated user (requirement 6.6). Built
    defensively so uninstalled resources report ``installed=False`` rather than
    erroring (requirement 8.4).
    """
    settings = get_settings()
    return ResourcesResponse(
        face=_build_resource_status(settings, "face"),
        scene=_build_resource_status(settings, "scene"),
        geocoding=_build_resource_status(settings, "geocoding"),
    )


@router.post("/explore/resources/{type}", response_model=ResourceUploadResponse)
async def upload_resource(
    type: str,
    file: UploadFile = File(...),
    current_user: UserInfo = Depends(require_admin),
) -> ResourceUploadResponse:
    """Validate and save an uploaded AnalysisResource file (admin only).

    Enforces the per-type extension allow-list and a sanitized target filename
    to prevent path traversal. The upload is streamed to a temp file in the
    target directory and only atomically renamed into place after it is fully
    written, so a rejected or failed upload never corrupts an existing installed
    resource (requirements 6.7, 6.3). Files become usable on the next analysis
    without a restart (analyzers reload by mtime).

    Raises:
        HTTPException 400: unknown type, illegal filename, disallowed extension,
            or empty/failed upload — in all cases existing files are untouched.
        HTTPException 403: caller is not an admin (via ``require_admin``).
    """
    settings = get_settings()

    if type not in _RESOURCE_DIRS:
        raise HTTPException(
            status_code=400,
            detail=f"未知的资源类型: {type}（应为 face / scene / geocoding）",
        )

    # Sanitize the target filename: strip any directory components and reject
    # traversal so the upload can only ever land in the type's directory.
    raw_name = file.filename or ""
    basename = os.path.basename(raw_name.replace("\\", "/"))
    if not basename or basename in (".", "..") or "/" in basename or "\\" in basename:
        raise HTTPException(status_code=400, detail="非法的文件名")

    ext = os.path.splitext(basename)[1].lower()
    allowed = _ALLOWED_EXTENSIONS[type]
    if ext not in allowed:
        raise HTTPException(
            status_code=400,
            detail=(
                f"{type} 资源不接受扩展名 “{ext or '(空)'}”，"
                f"仅支持: {', '.join(sorted(allowed))}"
            ),
        )

    target_dir = Path(settings.models_root) / _RESOURCE_DIRS[type]
    try:
        target_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise HTTPException(status_code=400, detail=f"无法创建资源目录: {exc}")

    target_path = target_dir / basename

    # Stream to a temp file in the SAME directory, then atomically replace the
    # target only on full success — a failed/invalid upload leaves any existing
    # installed file intact (requirement 6.7).
    tmp_fd, tmp_name = tempfile.mkstemp(dir=str(target_dir), suffix=".upload")
    tmp_path = Path(tmp_name)
    try:
        written = 0
        with os.fdopen(tmp_fd, "wb") as out:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)
                written += len(chunk)

        if written == 0:
            raise HTTPException(status_code=400, detail="上传文件为空")

        os.replace(str(tmp_path), str(target_path))
    except HTTPException:
        _safe_unlink(tmp_path)
        raise
    except Exception as exc:  # noqa: BLE001 - surface a readable 400
        _safe_unlink(tmp_path)
        raise HTTPException(status_code=400, detail=f"保存资源失败: {exc}")
    finally:
        await file.close()

    status = _build_resource_status(settings, type)
    return ResourceUploadResponse(
        success=True,
        message=f"已保存 {basename}",
        status=status,
    )


class DownloadProgressResponse(BaseModel):
    """Snapshot of an in-flight (or last) resource download for polling."""

    status: str  # idle | running | success | error
    phase: str = ""  # downloading | installing | ""
    downloaded: int = 0
    total: Optional[int] = None
    percent: Optional[float] = None
    message: str = ""
    error: Optional[str] = None
    resource: Optional[ResourceStatus] = None


class DownloadStartResponse(BaseModel):
    """Ack for a started background download."""

    started: bool


# Retain references to in-flight download tasks so they are not garbage
# collected before completion.
_download_tasks: set = set()


async def _run_resource_download_job(rtype: str, url: str, models_root: str) -> None:
    """Background job: fetch + install a resource, updating the progress registry."""
    import asyncio

    from app.services import resource_downloader as rd

    try:
        message = await asyncio.to_thread(
            rd.download_and_install, rtype, url, models_root
        )
        status = _build_resource_status(get_settings(), rtype)
        rd.mark_success(rtype, message, status.model_dump())
    except rd.ResourceDownloadError as exc:
        rd.mark_error(rtype, str(exc))
    except Exception as exc:  # noqa: BLE001 - surface a readable error
        logger.exception("Resource download failed for type=%s url=%s", rtype, url)
        rd.mark_error(rtype, f"下载/安装失败: {exc}")


@router.post("/explore/resources/{type}/download", response_model=DownloadStartResponse)
async def download_resource(
    type: str,
    body: DownloadResourceRequest,
    current_user: UserInfo = Depends(require_admin),
) -> DownloadStartResponse:
    """Start a background download+install of an AnalysisResource (admin only).

    Returns immediately after launching the job; the client polls
    ``GET /explore/resources/{type}/download/progress`` for a progress bar.

    Supports a direct model file (``.onnx`` / ``.json`` / ``.db``), a ``.zip``
    archive (e.g. InsightFace ``buffalo_l.zip`` → the face models), or a GeoNames
    text dump / zip (converted into ``cities.db``). The fetch runs in a worker
    thread, is limited to ``http``/``https``, size-capped and timeout-bounded. A
    failed or invalid download leaves any existing installed resource intact.

    Raises:
        HTTPException 400: unknown type or empty URL.
        HTTPException 409: a download for this type is already running.
        HTTPException 403: caller is not an admin (via ``require_admin``).
    """
    import asyncio

    from app.services import resource_downloader as rd

    settings = get_settings()
    url = (body.url or "").strip()
    if not url:
        raise HTTPException(status_code=400, detail="下载链接不能为空")

    if type not in _RESOURCE_DIRS:
        raise HTTPException(
            status_code=400,
            detail=f"未知的资源类型: {type}（应为 face / scene / geocoding）",
        )

    if rd.is_running(type):
        raise HTTPException(status_code=409, detail="该资源正在下载中，请稍候")

    rd.begin_progress(type)
    task = asyncio.create_task(
        _run_resource_download_job(type, url, settings.models_root)
    )
    _download_tasks.add(task)
    task.add_done_callback(_download_tasks.discard)

    return DownloadStartResponse(started=True)


@router.get(
    "/explore/resources/{type}/download/progress",
    response_model=DownloadProgressResponse,
)
async def download_progress(
    type: str,
    current_user: UserInfo = Depends(get_current_user),
) -> DownloadProgressResponse:
    """Return the current (or last) download progress for a resource type."""
    from app.services import resource_downloader as rd

    if type not in _RESOURCE_DIRS:
        raise HTTPException(
            status_code=400,
            detail=f"未知的资源类型: {type}（应为 face / scene / geocoding）",
        )

    snap = rd.get_progress(type)
    downloaded = snap.get("downloaded", 0) or 0
    total = snap.get("total")
    percent: Optional[float] = None
    if total:
        percent = round(min(downloaded / total, 1.0) * 100, 1)

    resource_dict = snap.get("resource")
    resource = ResourceStatus(**resource_dict) if resource_dict else None

    return DownloadProgressResponse(
        status=snap.get("status", "idle"),
        phase=snap.get("phase", ""),
        downloaded=downloaded,
        total=total,
        percent=percent,
        message=snap.get("message", ""),
        error=snap.get("error"),
        resource=resource,
    )


@router.post("/explore/reanalyze", response_model=ReanalyzeResponse)
async def reanalyze(
    body: Optional[ReanalyzeRequest] = None,
    current_user: UserInfo = Depends(require_admin),
) -> ReanalyzeResponse:
    """Trigger re-analysis of the current admin's existing photos (admin only).

    Enqueues all of the user's live files for analysis; per-dimension gating is
    handled at analysis time by the FeatureFlags, so ``dimensions`` in the body
    is advisory and currently ignored (requirements 6.4, 6.6, 7.3). Returns the
    number of files enqueued.
    """
    queued = await enqueue_reanalysis_all(current_user.id)
    return ReanalyzeResponse(success=True, queued=queued)


@router.get("/explore/status", response_model=AnalysisStatusResponse)
async def get_analysis_status(
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> AnalysisStatusResponse:
    """Return analysis progress counts for the current user's live library.

    ``photos`` counts photos that produced a result in each dimension;
    ``groups`` counts clusters (people) / cities (places) / labels (scenes).
    ``queue_pending`` is the global in-memory analysis backlog (all users).
    """
    from app.core.config import get_analysis_flags
    from app.services.analysis_queue import get_analysis_queue

    uid = current_user.id
    live = "fr.deleted_at IS NULL AND fr.purged_at IS NULL"

    async def _scalar(sql: str, params: tuple) -> int:
        cursor = await db.execute(sql, params)
        row = await cursor.fetchone()
        return int(row[0]) if row and row[0] is not None else 0

    total_images = await _scalar(
        f"SELECT COUNT(*) FROM file_records fr "
        f"WHERE fr.user_id = ? AND fr.media_type = 'image' AND {live}",
        (uid,),
    )

    place_photos = await _scalar(
        f"SELECT COUNT(DISTINCT pg.file_id) FROM photo_gps pg "
        f"JOIN file_records fr ON fr.id = pg.file_id "
        f"WHERE pg.user_id = ? AND {live}",
        (uid,),
    )
    place_cities = await _scalar(
        f"SELECT COUNT(DISTINCT pg.city) FROM photo_gps pg "
        f"JOIN file_records fr ON fr.id = pg.file_id "
        f"WHERE pg.user_id = ? AND pg.city IS NOT NULL AND pg.city != '' AND {live}",
        (uid,),
    )

    scene_photos = await _scalar(
        f"SELECT COUNT(DISTINCT ps.file_id) FROM photo_scenes ps "
        f"JOIN file_records fr ON fr.id = ps.file_id "
        f"WHERE ps.user_id = ? AND {live}",
        (uid,),
    )
    scene_labels = await _scalar(
        f"SELECT COUNT(DISTINCT ps.scene_label) FROM photo_scenes ps "
        f"JOIN file_records fr ON fr.id = ps.file_id "
        f"WHERE ps.user_id = ? AND {live}",
        (uid,),
    )

    face_photos = await _scalar(
        f"SELECT COUNT(DISTINCT fa.file_id) FROM faces fa "
        f"JOIN file_records fr ON fr.id = fa.file_id "
        f"WHERE fa.user_id = ? AND {live}",
        (uid,),
    )
    face_clusters = await _scalar(
        f"SELECT COUNT(DISTINCT fa.cluster_id) FROM faces fa "
        f"JOIN file_records fr ON fr.id = fa.file_id "
        f"WHERE fa.user_id = ? AND fa.cluster_id IS NOT NULL AND {live}",
        (uid,),
    )

    flags = get_analysis_flags()
    settings = get_settings()

    try:
        queue_pending = get_analysis_queue().qsize()
    except Exception:  # pragma: no cover - defensive
        queue_pending = 0

    # The ONNX runtime is required for the face/scene dimensions; place only
    # needs Pillow. Report it so the UI can explain "模型已装但缺运行时".
    try:
        import onnxruntime  # noqa: F401

        runtime_ok = True
    except Exception:
        runtime_ok = False

    def _installed(rtype: str) -> bool:
        return _build_resource_status(settings, rtype).installed

    return AnalysisStatusResponse(
        total_images=total_images,
        queue_pending=queue_pending,
        runtime_ok=runtime_ok,
        people=DimensionStatus(
            enabled=flags["enable_face"],
            installed=_installed("face"),
            photos=face_photos,
            groups=face_clusters,
        ),
        places=DimensionStatus(
            enabled=flags["enable_place"],
            installed=_installed("geocoding"),
            photos=place_photos,
            groups=place_cities,
        ),
        scenes=DimensionStatus(
            enabled=flags["enable_scene"],
            installed=_installed("scene"),
            photos=scene_photos,
            groups=scene_labels,
        ),
    )


@router.get("/explore/settings", response_model=AnalysisFlags)
async def get_analysis_settings(
    current_user: UserInfo = Depends(get_current_user),
) -> AnalysisFlags:
    """Return the current analysis feature flags (any authenticated user)."""
    from app.core.config import get_analysis_flags

    return AnalysisFlags(**get_analysis_flags())


@router.put("/explore/settings", response_model=AnalysisFlags)
async def update_analysis_settings(
    body: AnalysisFlags,
    current_user: UserInfo = Depends(require_admin),
) -> AnalysisFlags:
    """Enable/disable the analysis dimensions at runtime (admin only).

    Persists the flags and applies them to the live settings so the background
    worker picks them up immediately — no restart required.
    """
    from app.core.config import set_analysis_flags

    flags = set_analysis_flags(
        enable_place=body.enable_place,
        enable_scene=body.enable_scene,
        enable_face=body.enable_face,
    )
    return AnalysisFlags(**flags)
