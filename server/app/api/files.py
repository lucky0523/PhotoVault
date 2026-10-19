"""File browsing API endpoints.

Endpoints:
- GET /api/v1/files/browse
- GET /api/v1/files/list
- GET /api/v1/files/all
- GET /api/v1/files/timeline/facets   (Web timeline: options + photo dates)
- GET /api/v1/files/timeline/summary  (Web timeline: per-month counts)
- GET /api/v1/files/timeline/files    (Web timeline: one page of a local month)
- GET /api/v1/files/thumbnail/{file_id}
- GET /api/v1/files/download/{file_id}
- DELETE /api/v1/files/{file_id}
- DELETE /api/v1/files/directory
"""

from __future__ import annotations

import asyncio
import logging
import sqlite3
from datetime import date, datetime, timezone
from typing import List, Literal, Optional
from urllib.parse import quote

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

from app.core.config import get_settings
from app.core.database import get_db
from app.core.security import get_current_user, get_current_user_or_query_token
from app.core.validators import normalize_virtual_dir_path
from app.models.auth import UserInfo
from app.services.file_browse_service import FileBrowseInfo, FileBrowseService
from app.services.filename_search import parse_search_query
from app.services.stats_buckets import (
    ResolvedTz,
    is_valid_tz_name,
    resolve_default_tz,
    utc_offset_intervals,
)
from app.services.timeline_service import (
    TimelineCursor,
    TimelineFilters,
    TimelineService,
    resolve_timeline_tz,
)

logger = logging.getLogger("photovault.api.files")

router = APIRouter()


# ---------------------------------------------------------------------------
# Response models
# ---------------------------------------------------------------------------


class DirectoryInfoResponse(BaseModel):
    """Directory info in browse response."""

    name: str
    path: str
    file_count: int = 0
    size: int = 0
    latest_file_time: Optional[str] = None
    # Per-directory counts bucketed by status
    backed_up_count: int = 0
    trashed_count: int = 0
    purged_count: int = 0


class FileInfoResponse(BaseModel):
    """File info in browse/list response."""

    id: int
    file_name: str
    file_size: int
    mime_type: Optional[str] = None
    exif_time: Optional[str] = None
    media_type: str = "image"
    is_motion_photo: bool = False
    is_ultra_hdr: bool = False
    created_at: Optional[str] = None
    thumbnail_url: str = ""
    device_name: Optional[str] = None
    focal_length: Optional[float] = None


class DeviceStatsResponse(BaseModel):
    """Per-device file counts broken down by status."""

    name: str
    path: str
    backed_up_count: int = 0
    trashed_count: int = 0
    purged_count: int = 0
    file_count: int = 0  # backed_up_count, kept for backward compatibility
    latest_file_time: Optional[str] = None


class BrowseResponse(BaseModel):
    """Response for directory browsing."""

    current_path: str
    parent_path: Optional[str] = None
    directories: List[DirectoryInfoResponse] = []
    files: List[FileInfoResponse] = []
    total_files: int = 0
    page: int = 1
    page_size: int = 50


class FileDetailResponse(BaseModel):
    """Detailed file info response."""

    id: int
    file_name: str
    file_size: int
    file_hash: str
    original_path: str
    device_name: str
    mime_type: Optional[str] = None
    exif_time: Optional[str] = None
    media_type: str = "image"
    is_motion_photo: bool = False
    is_ultra_hdr: bool = False
    created_at: Optional[str] = None


class DeleteFileResponse(BaseModel):
    """Response for file deletion."""

    success: bool
    message: str


class DeleteDirectoryResponse(BaseModel):
    """Response for directory deletion."""

    success: bool
    deleted_count: int
    message: str


class TrashItemResponse(BaseModel):
    """Response model for a trash item."""

    id: int
    file_name: str
    file_size: int
    file_path: str
    original_path: str
    display_path: str
    device_name: str
    mime_type: Optional[str] = None
    media_type: str = "image"
    exif_time: Optional[str] = None
    created_at: Optional[str] = None
    deleted_at: Optional[str] = None
    deleted_batch_id: Optional[str] = None
    expires_at: Optional[str] = None
    is_reference: bool = False


class TrashListResponse(BaseModel):
    """Response for trash listing."""

    items: List[TrashItemResponse]
    total: int
    page: int
    page_size: int


class TrashActionResponse(BaseModel):
    """Response for trash actions (restore/purge)."""

    success: bool
    message: str
    count: Optional[int] = None


class FilesConfigResponse(BaseModel):
    """Client-facing file/trash configuration."""

    trash_retention_days: int


class StatusSyncItem(BaseModel):
    """Single item in status sync response."""

    file_hash: str
    status: str
    deleted_at: Optional[str] = None
    purged_at: Optional[str] = None
    expires_at: Optional[str] = None


class StatusSyncResponse(BaseModel):
    """Response for client status sync."""

    items: List[StatusSyncItem]


class TimelineFacetsResponse(BaseModel):
    """Library-wide timeline filter options (independent of the filters)."""

    tz: str
    devices: List[str]
    formats: List[str]
    focal_buckets: List[str]
    photo_dates: List[str]


class TimelineMonthResponse(BaseModel):
    """Filtered file count of one browser-local month."""

    year: int
    month: int
    count: int


class TimelineSummaryResponse(BaseModel):
    """Timeline counts: library total, filtered total, filtered months (newest first)."""

    tz: str
    total_count: int
    filtered_count: int
    months: List[TimelineMonthResponse]


class TimelineFilesResponse(BaseModel):
    """One page of a browser-local month."""

    month: str
    files: List[FileInfoResponse]
    has_more: bool


# ---------------------------------------------------------------------------
# Timeline parameters
# ---------------------------------------------------------------------------

_TIMELINE_MAX_VALUES = 50
_TIMELINE_QUERY_FAILED = "时间线查询失败，请稍后重试"

FocalBucket = Literal["ultrawide", "wide", "standard", "tele", "supertele", "unknown"]


def _timeline_422(code: str, message: str) -> HTTPException:
    return HTTPException(status_code=422, detail={"code": code, "message": message})


def timeline_tz_param(
    tz: Optional[str] = Query(None, max_length=64, description="Browser IANA time zone"),
) -> Optional[str]:
    """Reject a non-IANA ``tz`` with 422 ``invalid_tz`` (same as ``/stats/media``)."""
    if tz is not None and not is_valid_tz_name(tz):
        raise _timeline_422("invalid_tz", f"无效的时区: {tz}")
    return tz


def timeline_filter_params(
    q: Optional[str] = Query(None, max_length=200, description="File name fuzzy search"),
    device_: List[str] = Query(default=[], alias="device", description="Repeated key"),
    format_: List[str] = Query(default=[], alias="format", description="Repeated key"),
    focal: List[FocalBucket] = Query(default=[], description="Repeated key"),
    date_from: Optional[date] = Query(None, description="Local date, inclusive"),
    date_to: Optional[date] = Query(None, description="Local date, inclusive"),
    path: str = Query(
        "", max_length=1100, description="Virtual directory; empty = whole library"
    ),
    recursive: bool = Query(True, description="Include subdirectories (ignored at root)"),
    capture_date: Optional[date] = Query(
        None, description="Local capture date, /stats/media rule"
    ),
    current_user: UserInfo = Depends(get_current_user),
) -> TimelineFilters:
    """Timeline filters shared by ``summary`` and ``files``."""
    if len(device_) > _TIMELINE_MAX_VALUES or len(format_) > _TIMELINE_MAX_VALUES:
        raise _timeline_422(
            "too_many_values", f"device / format 最多各 {_TIMELINE_MAX_VALUES} 个"
        )
    if (date_from is None) != (date_to is None):
        raise _timeline_422("invalid_date_range", "date_from 与 date_to 必须同时提供")
    try:
        norm_path = normalize_virtual_dir_path(path)
    except ValueError as e:
        raise _timeline_422("invalid_path", str(e))
    # Same scope as /stats/media: root = no folder filter (custom paths
    # included, recursive ignored).
    dir_prefix = (
        f"{get_settings().media_root}/{current_user.username}/{norm_path}/"
        if norm_path
        else None
    )
    return TimelineFilters(
        keywords=tuple(parse_search_query(q)),
        devices=tuple(device_),
        formats=tuple(format_),
        focals=tuple(dict.fromkeys(focal)),
        date_from=date_from.isoformat() if date_from is not None else None,
        date_to=date_to.isoformat() if date_to is not None else None,
        dir_prefix=dir_prefix,
        dir_recursive=recursive if dir_prefix is not None else True,
        capture_date=capture_date.isoformat() if capture_date is not None else None,
    )


async def _timeline_tz(tz: Optional[str]) -> tuple[ResolvedTz, tuple[tuple[str, str], ...]]:
    rtz = resolve_timeline_tz(tz, get_settings())
    last_year = datetime.now(timezone.utc).year + 1
    intervals = await asyncio.to_thread(utc_offset_intervals, rtz, last_year)
    return rtz, intervals


async def _capture_intervals(
    tz: Optional[str], filters: TimelineFilters
) -> Optional[tuple[tuple[str, str], ...]]:
    """Stats-rule intervals (``resolve_default_tz``) when ``capture_date`` is set."""
    if filters.capture_date is None:
        return None
    rtz = resolve_default_tz(tz, get_settings())
    last_year = datetime.now(timezone.utc).year + 1
    return await asyncio.to_thread(utc_offset_intervals, rtz, last_year)


def _file_info_response(f: FileBrowseInfo) -> FileInfoResponse:
    """Same fields as the ``/files/all`` items."""
    return FileInfoResponse(
        id=f.id,
        file_name=f.file_name,
        file_size=f.file_size,
        mime_type=f.mime_type,
        exif_time=f.exif_time,
        media_type=f.media_type,
        is_motion_photo=f.is_motion_photo,
        is_ultra_hdr=f.is_ultra_hdr,
        created_at=f.created_at,
        thumbnail_url=f"/api/v1/files/thumbnail/{f.id}",
        device_name=f.device_name,
        focal_length=f.focal_length,
    )


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/files/browse", response_model=BrowseResponse)
async def browse_directory(
    path: str = Query(default="", description="Virtual path to browse"),
    page: int = Query(default=1, ge=1, description="Page number"),
    page_size: int = Query(default=50, ge=1, le=200, description="Items per page"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> BrowseResponse:
    """Browse directory structure.

    Returns subdirectories and files at the given path.
    Path is relative to the user's storage root (e.g., "Pixel9Pro/DCIM/Camera").
    Empty path returns the root level (typically device names).
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    listing = await service.list_directory(
        user_id=current_user.id,
        path=path,
        page=page,
        page_size=page_size,
    )

    directories = [
        DirectoryInfoResponse(
            name=d.name,
            path=d.path,
            file_count=d.file_count,
            size=d.size,
            latest_file_time=d.latest_file_time,
            backed_up_count=d.backed_up_count,
            trashed_count=d.trashed_count,
            purged_count=d.purged_count,
        )
        for d in listing.directories
    ]

    files = [
        FileInfoResponse(
            id=f.id,
            file_name=f.file_name,
            file_size=f.file_size,
            mime_type=f.mime_type,
            exif_time=f.exif_time,
            media_type=f.media_type,
            is_motion_photo=f.is_motion_photo,
            is_ultra_hdr=f.is_ultra_hdr,
            created_at=f.created_at,
            thumbnail_url=f"/api/v1/files/thumbnail/{f.id}",
        )
        for f in listing.files
    ]

    return BrowseResponse(
        current_path=listing.current_path,
        parent_path=listing.parent_path,
        directories=directories,
        files=files,
        total_files=listing.total_files,
        page=listing.page,
        page_size=listing.page_size,
    )


@router.get("/files/devices", response_model=List[DeviceStatsResponse])
async def get_device_stats(
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> List[DeviceStatsResponse]:
    """List devices with file counts broken down by status.

    Status buckets:
    - backed_up_count: files currently backed up (not deleted)
    - trashed_count: files in the recycle bin (soft-deleted, not yet purged)
    - purged_count: files permanently deleted (history retained)
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    stats = await service.get_device_stats(user_id=current_user.id)

    return [
        DeviceStatsResponse(
            name=d.name,
            path=d.path,
            backed_up_count=d.backed_up_count,
            trashed_count=d.trashed_count,
            purged_count=d.purged_count,
            file_count=d.backed_up_count,
            latest_file_time=d.latest_file_time,
        )
        for d in stats
    ]


@router.get("/files/list", response_model=BrowseResponse)
async def list_files(
    path: str = Query(default="", description="Virtual path to list"),
    page: int = Query(default=1, ge=1, description="Page number"),
    page_size: int = Query(default=50, ge=1, le=200, description="Items per page"),
    sort_by: str = Query(default="name", description="Sort by: name, size, time"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> BrowseResponse:
    """List files in a directory (flat list with sorting).

    Similar to browse but with sort support. Returns files and subdirectories
    at the given path.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    # Validate sort_by
    if sort_by not in ("name", "size", "time"):
        sort_by = "name"

    listing = await service.list_directory(
        user_id=current_user.id,
        path=path,
        page=page,
        page_size=page_size,
        sort_by=sort_by,
    )

    directories = [
        DirectoryInfoResponse(
            name=d.name,
            path=d.path,
            file_count=d.file_count,
            size=d.size,
            latest_file_time=d.latest_file_time,
            backed_up_count=d.backed_up_count,
            trashed_count=d.trashed_count,
            purged_count=d.purged_count,
        )
        for d in listing.directories
    ]

    files = [
        FileInfoResponse(
            id=f.id,
            file_name=f.file_name,
            file_size=f.file_size,
            mime_type=f.mime_type,
            exif_time=f.exif_time,
            media_type=f.media_type,
            is_motion_photo=f.is_motion_photo,
            is_ultra_hdr=f.is_ultra_hdr,
            created_at=f.created_at,
            thumbnail_url=f"/api/v1/files/thumbnail/{f.id}",
        )
        for f in listing.files
    ]

    return BrowseResponse(
        current_path=listing.current_path,
        parent_path=listing.parent_path,
        directories=directories,
        files=files,
        total_files=listing.total_files,
        page=listing.page,
        page_size=listing.page_size,
    )


@router.get("/files/config", response_model=FilesConfigResponse)
async def get_files_config(
    current_user: UserInfo = Depends(get_current_user),
) -> FilesConfigResponse:
    """Return client-facing file/trash configuration.

    Currently exposes the trash retention period so the web UI can show the
    correct auto-purge window instead of a hardcoded value.
    """
    settings = get_settings()
    return FilesConfigResponse(trash_retention_days=settings.trash_retention_days)


@router.get("/files/all", response_model=BrowseResponse)
async def list_all_files(
    page: int = Query(default=1, ge=1, description="Page number"),
    page_size: int = Query(default=200, ge=1, le=500, description="Items per page"),
    sort_by: str = Query(default="time", description="Sort by: name, size, time"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> BrowseResponse:
    """List ALL of the user's files across every directory (flat, recursive).

    Returns every non-deleted file regardless of directory nesting. Used by the
    timeline view, which groups photos by time rather than by folder.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    if sort_by not in ("name", "size", "time"):
        sort_by = "time"

    listing = await service.list_all_files(
        user_id=current_user.id,
        page=page,
        page_size=page_size,
        sort_by=sort_by,
    )

    files = [
        FileInfoResponse(
            id=f.id,
            file_name=f.file_name,
            file_size=f.file_size,
            mime_type=f.mime_type,
            exif_time=f.exif_time,
            media_type=f.media_type,
            is_motion_photo=f.is_motion_photo,
            is_ultra_hdr=f.is_ultra_hdr,
            created_at=f.created_at,
            thumbnail_url=f"/api/v1/files/thumbnail/{f.id}",
            device_name=f.device_name,
            focal_length=f.focal_length,
        )
        for f in listing.files
    ]

    return BrowseResponse(
        current_path="",
        parent_path=None,
        directories=[],
        files=files,
        total_files=listing.total_files,
        page=listing.page,
        page_size=listing.page_size,
    )


@router.get("/files/timeline/facets", response_model=TimelineFacetsResponse)
async def get_timeline_facets(
    tz: Optional[str] = Depends(timeline_tz_param),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TimelineFacetsResponse:
    """Web timeline: device / format / focal options and the dates with photos.

    Covers all of the user's active files, independent of any filter.
    """
    rtz, intervals = await _timeline_tz(tz)
    try:
        facets = await TimelineService(db).facets(current_user.id, intervals)
    except (aiosqlite.Error, sqlite3.Error):
        logger.error(
            "Timeline query failed user_id=%s endpoint=facets",
            current_user.id,
            exc_info=True,
        )
        raise HTTPException(status_code=500, detail=_TIMELINE_QUERY_FAILED)
    return TimelineFacetsResponse(
        tz=rtz.name,
        devices=facets.devices,
        formats=facets.formats,
        focal_buckets=facets.focal_buckets,
        photo_dates=facets.photo_dates,
    )


@router.get("/files/timeline/summary", response_model=TimelineSummaryResponse)
async def get_timeline_summary(
    tz: Optional[str] = Depends(timeline_tz_param),
    filters: TimelineFilters = Depends(timeline_filter_params),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TimelineSummaryResponse:
    """Web timeline: library total, filtered total and filtered per-month counts."""
    rtz, intervals = await _timeline_tz(tz)
    capture_intervals = await _capture_intervals(tz, filters)
    try:
        summary = await TimelineService(db).summary(
            current_user.id, filters, intervals, capture_intervals=capture_intervals
        )
    except (aiosqlite.Error, sqlite3.Error):
        logger.error(
            "Timeline query failed user_id=%s endpoint=summary",
            current_user.id,
            exc_info=True,
        )
        raise HTTPException(status_code=500, detail=_TIMELINE_QUERY_FAILED)
    return TimelineSummaryResponse(
        tz=rtz.name,
        total_count=summary.total_count,
        filtered_count=summary.filtered_count,
        months=[
            TimelineMonthResponse(year=m.year, month=m.month, count=m.count)
            for m in summary.months
        ],
    )


@router.get("/files/timeline/files", response_model=TimelineFilesResponse)
async def list_timeline_files(
    month: str = Query(
        ..., pattern=r"^\d{4}-(0[1-9]|1[0-2])$", description="Local month YYYY-MM"
    ),
    limit: int = Query(default=200, ge=1, le=500),
    offset: int = Query(default=0, ge=0, le=1_000_000, description="In-month offset"),
    after_time: Optional[str] = Query(
        None, max_length=64, description="Keyset: exif_time ?? created_at of the last row"
    ),
    after_id: Optional[int] = Query(None, ge=1, description="Keyset: id of the last row"),
    tz: Optional[str] = Depends(timeline_tz_param),
    filters: TimelineFilters = Depends(timeline_filter_params),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TimelineFilesResponse:
    """Web timeline: one page of a browser-local month, newest first.

    Continue downwards with the keyset cursor (``after_time`` + ``after_id``);
    jump into a month with ``offset``. The two are mutually exclusive.
    """
    year, mon = int(month[:4]), int(month[5:7])
    if not 1000 <= year <= 9998:
        raise _timeline_422("invalid_month", f"月份超出范围: {month}")
    if (after_time is None) != (after_id is None):
        raise _timeline_422("invalid_cursor", "after_time 与 after_id 必须同时提供")
    if after_time is not None and offset > 0:
        raise _timeline_422("invalid_cursor", "游标与 offset 不能同时使用")
    after = TimelineCursor(after_time, after_id) if after_time is not None else None

    _rtz, intervals = await _timeline_tz(tz)
    capture_intervals = await _capture_intervals(tz, filters)
    try:
        files, has_more = await TimelineService(db).list_month(
            current_user.id,
            filters,
            intervals,
            year,
            mon,
            offset=offset,
            after=after,
            limit=limit,
            capture_intervals=capture_intervals,
        )
    except (aiosqlite.Error, sqlite3.Error):
        logger.error(
            "Timeline query failed user_id=%s endpoint=files month=%s",
            current_user.id,
            month,
            exc_info=True,
        )
        raise HTTPException(status_code=500, detail=_TIMELINE_QUERY_FAILED)
    return TimelineFilesResponse(
        month=month,
        files=[_file_info_response(f) for f in files],
        has_more=has_more,
    )


@router.get("/files/thumbnail/{file_id}")
async def get_thumbnail(
    file_id: int,
    size: str = Query(default="small", description="Thumbnail size: small or medium"),
    current_user: UserInfo = Depends(get_current_user_or_query_token),
    db: aiosqlite.Connection = Depends(get_db),
) -> Response:
    """Get thumbnail for a file.

    Generates thumbnail on first request and caches it for subsequent requests.
    Sizes: 'small' = 200x200, 'medium' = 600x600.
    Returns JPEG image bytes.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    if size not in ("small", "medium"):
        size = "small"

    thumbnail_data = await service.get_thumbnail(
        user_id=current_user.id,
        file_id=file_id,
        size=size,
    )

    if thumbnail_data is None:
        raise HTTPException(status_code=404, detail="File not found or thumbnail generation failed")

    return Response(
        content=thumbnail_data,
        media_type="image/jpeg",
        headers={"Cache-Control": "public, max-age=86400"},
    )


@router.get("/files/download/{file_id}")
async def download_file(
    file_id: int,
    request: Request,
    current_user: UserInfo = Depends(get_current_user_or_query_token),
    db: aiosqlite.Connection = Depends(get_db),
) -> StreamingResponse:
    """Download or stream the original file.

    Supports HTTP Range requests so videos can be streamed/seeked in the
    browser. Images and other files are returned in full. Media files
    (image/*, video/*) are served inline; everything else as an attachment.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    target = await service.resolve_download_target(
        user_id=current_user.id,
        file_id=file_id,
    )

    if target is None:
        raise HTTPException(status_code=404, detail="File not found")

    actual_path, file_name, file_size, mime_type = target

    chunk_size = 64 * 1024

    # RFC 5987 encoding for Unicode plus a quoted ASCII fallback. Strip line
    # breaks from the fallback and escape quoted-string metacharacters so an
    # untrusted filename cannot inject or truncate response headers.
    ascii_filename = file_name.replace("\r", "").replace("\n", "")
    ascii_filename = (
        ascii_filename.encode("ascii", errors="ignore").decode("ascii") or "download"
    )
    ascii_filename = ascii_filename.replace("\\", "\\\\").replace('"', '\\"')
    encoded_filename = quote(file_name, safe="")
    is_media = mime_type.startswith(("image/", "video/", "audio/"))
    disposition_type = "inline" if is_media else "attachment"
    content_disposition = (
        f'{disposition_type}; filename="{ascii_filename}"; '
        f"filename*=UTF-8''{encoded_filename}"
    )

    range_header = request.headers.get("range") or request.headers.get("Range")

    # Full-content response (no Range)
    if not range_header:
        def full_stream():
            with open(actual_path, "rb") as f:
                while True:
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    yield chunk

        return StreamingResponse(
            content=full_stream(),
            media_type=mime_type,
            headers={
                "Content-Disposition": content_disposition,
                "Content-Length": str(file_size),
                "Accept-Ranges": "bytes",
            },
        )

    # Parse a single "bytes=start-end" range
    start, end = _parse_range(range_header, file_size)
    if start is None:
        # Unsatisfiable range
        return StreamingResponse(
            content=iter(()),
            status_code=416,
            media_type=mime_type,
            headers={"Content-Range": f"bytes */{file_size}"},
        )

    length = end - start + 1

    def ranged_stream():
        with open(actual_path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(chunk_size, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    return StreamingResponse(
        content=ranged_stream(),
        status_code=206,
        media_type=mime_type,
        headers={
            "Content-Disposition": content_disposition,
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Content-Length": str(length),
            "Accept-Ranges": "bytes",
        },
    )


@router.get("/files/motion/{file_id}")
async def motion_video(
    file_id: int,
    request: Request,
    current_user: UserInfo = Depends(get_current_user_or_query_token),
    db: aiosqlite.Connection = Depends(get_db),
) -> StreamingResponse:
    """Stream the embedded video of a motion photo (动态照片).

    A motion photo is a JPEG with an MP4 appended; this returns only the trailing
    video bytes as ``video/mp4`` with HTTP Range support so the browser can play
    and seek the motion clip.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    resolved = await service.resolve_motion_video(user_id=current_user.id, file_id=file_id)
    if resolved is None:
        raise HTTPException(status_code=404, detail="Motion photo video not found")

    actual_path, base_offset, video_size = resolved
    chunk_size = 64 * 1024
    mime_type = "video/mp4"

    range_header = request.headers.get("range") or request.headers.get("Range")

    if not range_header:
        def full_stream():
            with open(actual_path, "rb") as f:
                f.seek(base_offset)
                remaining = video_size
                while remaining > 0:
                    chunk = f.read(min(chunk_size, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                    yield chunk

        return StreamingResponse(
            content=full_stream(),
            media_type=mime_type,
            headers={
                "Content-Length": str(video_size),
                "Accept-Ranges": "bytes",
                "Content-Disposition": "inline",
                "Cache-Control": "public, max-age=86400",
            },
        )

    # Range is expressed relative to the video (0 .. video_size-1)
    start, end = _parse_range(range_header, video_size)
    if start is None:
        return StreamingResponse(
            content=iter(()),
            status_code=416,
            media_type=mime_type,
            headers={"Content-Range": f"bytes */{video_size}"},
        )

    length = end - start + 1

    def ranged_stream():
        with open(actual_path, "rb") as f:
            f.seek(base_offset + start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(chunk_size, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    return StreamingResponse(
        content=ranged_stream(),
        status_code=206,
        media_type=mime_type,
        headers={
            "Content-Range": f"bytes {start}-{end}/{video_size}",
            "Content-Length": str(length),
            "Accept-Ranges": "bytes",
            "Content-Disposition": "inline",
            "Cache-Control": "public, max-age=86400",
        },
    )


def _parse_range(range_header: str, file_size: int) -> tuple[Optional[int], Optional[int]]:
    """Parse a single-range 'bytes=start-end' header.

    Returns (start, end) inclusive byte offsets, or (None, None) if the range
    is invalid/unsatisfiable.
    """
    try:
        units, _, range_spec = range_header.partition("=")
        if units.strip().lower() != "bytes":
            return None, None
        # Only the first range is honored
        first = range_spec.split(",")[0].strip()
        start_str, _, end_str = first.partition("-")

        if start_str == "":
            # Suffix range: last N bytes
            suffix = int(end_str)
            if suffix <= 0:
                return None, None
            start = max(file_size - suffix, 0)
            end = file_size - 1
        else:
            start = int(start_str)
            end = int(end_str) if end_str else file_size - 1

        if start > end or start >= file_size:
            return None, None
        end = min(end, file_size - 1)
        return start, end
    except (ValueError, AttributeError):
        return None, None


# ---------------------------------------------------------------------------
# Trash (recycle bin) endpoints — must be before /files/{file_id}
# ---------------------------------------------------------------------------


@router.get("/files/trash", response_model=TrashListResponse)
async def list_trash(
    page: int = Query(default=1, ge=1, description="Page number"),
    page_size: int = Query(default=50, ge=1, le=200, description="Items per page"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TrashListResponse:
    """List files in trash for the current user."""
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    items, total = await service.list_trash(
        user_id=current_user.id,
        page=page,
        page_size=page_size,
    )

    return TrashListResponse(
        items=[
            TrashItemResponse(
                id=item.id,
                file_name=item.file_name,
                file_size=item.file_size,
                file_path=item.file_path,
                original_path=item.original_path,
                display_path=item.display_path,
                device_name=item.device_name,
                mime_type=item.mime_type,
                media_type=item.media_type,
                exif_time=item.exif_time,
                created_at=item.created_at,
                deleted_at=item.deleted_at,
                deleted_batch_id=item.deleted_batch_id,
                expires_at=item.expires_at,
                is_reference=item.is_reference,
            )
            for item in items
        ],
        total=total,
        page=page,
        page_size=page_size,
    )


@router.get("/files/status-sync", response_model=StatusSyncResponse)
async def status_sync(
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> StatusSyncResponse:
    """Get file status changes for client sync.

    Returns all trashed and purged files with their hashes and timestamps.
    Clients use this to update local photo status (trashed/purged).
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    changes = await service.get_status_changes(user_id=current_user.id)

    return StatusSyncResponse(
        items=[
            StatusSyncItem(
                file_hash=c.file_hash,
                status=c.status,
                deleted_at=c.deleted_at,
                purged_at=c.purged_at,
                expires_at=c.expires_at,
            )
            for c in changes
        ]
    )


@router.post("/files/trash/{file_id}/restore", response_model=TrashActionResponse)
async def restore_file(
    file_id: int,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TrashActionResponse:
    """Restore a single file from trash."""
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    success, message = await service.restore_file(
        user_id=current_user.id,
        file_id=file_id,
    )

    if not success:
        raise HTTPException(status_code=404, detail=message)

    return TrashActionResponse(success=True, message=message)


@router.post("/files/trash/batch/{batch_id}/restore", response_model=TrashActionResponse)
async def restore_batch(
    batch_id: str,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TrashActionResponse:
    """Restore all files in a batch from trash."""
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    count, message = await service.restore_batch(
        user_id=current_user.id,
        batch_id=batch_id,
    )

    if count == 0:
        raise HTTPException(status_code=404, detail="No files found for this batch")

    return TrashActionResponse(success=True, message=message, count=count)


@router.delete("/files/trash/{file_id}", response_model=TrashActionResponse)
async def purge_file(
    file_id: int,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TrashActionResponse:
    """Permanently delete a single file from trash."""
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    success, message = await service.purge_file(
        user_id=current_user.id,
        file_id=file_id,
    )

    if not success:
        raise HTTPException(status_code=404, detail=message)

    return TrashActionResponse(success=True, message=message)


@router.delete("/files/trash", response_model=TrashActionResponse)
async def purge_all(
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> TrashActionResponse:
    """Permanently delete all files in trash."""
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    count, message = await service.purge_all(user_id=current_user.id)

    return TrashActionResponse(success=True, message=message, count=count)


@router.delete("/files/directory", response_model=DeleteDirectoryResponse)
async def delete_directory(
    path: str = Query(description="Virtual path of the directory to delete"),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> DeleteDirectoryResponse:
    """Delete a directory and all its contents.

    Removes all file records in the specified directory path.
    Physical files are only deleted if no other records reference them.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    deleted_count, message = await service.delete_directory(
        user_id=current_user.id,
        path=path,
    )

    if deleted_count == 0:
        raise HTTPException(status_code=404, detail=message)

    return DeleteDirectoryResponse(success=True, deleted_count=deleted_count, message=message)


@router.delete("/files/{file_id}", response_model=DeleteFileResponse)
async def delete_file(
    file_id: int,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> DeleteFileResponse:
    """Delete a single file.

    If the file is a deduplication reference, only the reference record is deleted.
    If it's an original file with other references, the physical file is kept.
    If it's an original file with no references, both the record and physical file are deleted.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.media_root, settings.trash_retention_days)

    success, message = await service.delete_file(
        user_id=current_user.id,
        file_id=file_id,
    )

    if not success:
        raise HTTPException(status_code=404, detail=message)

    return DeleteFileResponse(success=True, message=message)
