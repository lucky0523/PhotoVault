"""File browsing API endpoints.

Endpoints:
- GET /api/v1/files/browse
- GET /api/v1/files/list
- GET /api/v1/files/thumbnail/{file_id}
- GET /api/v1/files/download/{file_id}
- DELETE /api/v1/files/{file_id}
- DELETE /api/v1/files/directory
"""

from __future__ import annotations

import logging
from typing import List, Optional

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

from app.core.config import get_settings
from app.core.database import get_db
from app.core.security import get_current_user, get_current_user_or_query_token
from app.models.auth import UserInfo
from app.services.file_browse_service import FileBrowseService

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


class FileInfoResponse(BaseModel):
    """File info in browse/list response."""

    id: int
    file_name: str
    file_size: int
    mime_type: Optional[str] = None
    exif_time: Optional[str] = None
    media_type: str = "image"
    created_at: Optional[str] = None
    thumbnail_url: str = ""


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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    current_user: UserInfo = Depends(get_current_user_or_query_token),
    db: aiosqlite.Connection = Depends(get_db),
) -> StreamingResponse:
    """Download original file.

    Returns the file as a streaming response with proper Content-Type
    and Content-Disposition headers for large file downloads.
    """
    settings = get_settings()
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

    result = await service.get_original_stream(
        user_id=current_user.id,
        file_id=file_id,
    )

    if result is None:
        raise HTTPException(status_code=404, detail="File not found")

    stream_fn, file_name, file_size, mime_type = result

    # Use RFC 5987 encoding for filename with non-ASCII characters
    safe_filename = file_name.encode("ascii", errors="ignore").decode("ascii") or "download"
    headers = {
        "Content-Disposition": f'attachment; filename="{safe_filename}"; filename*=UTF-8\'\'{file_name}',
        "Content-Length": str(file_size),
    }

    return StreamingResponse(
        content=stream_fn(),
        media_type=mime_type,
        headers=headers,
    )


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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

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
    service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)

    success, message = await service.delete_file(
        user_id=current_user.id,
        file_id=file_id,
    )

    if not success:
        raise HTTPException(status_code=404, detail=message)

    return DeleteFileResponse(success=True, message=message)
