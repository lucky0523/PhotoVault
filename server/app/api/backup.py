"""Backup API endpoints.

Endpoints:
- POST /backup/validate-path     (path validation)
- POST /backup/check             (duplicate detection)
- POST /backup/init              (initialize upload session)
- POST /backup/chunk             (upload a chunk)
- POST /backup/complete          (complete upload, merge + verify)
- GET  /backup/resume/{session_id}  (get resume info)
"""

from __future__ import annotations

import logging
from typing import List, Optional

import aiosqlite
from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

from app.core.config import get_settings
from app.core.database import get_db
from app.core.security import get_current_user
from app.models.auth import UserInfo
from app.models.storage import FileMetadata, StoragePolicy
from app.services.chunk_manager import (
    ChunkChecksumError,
    ChunkManager,
    SessionNotFoundError,
)
from app.services.deduplication_service import DeduplicationService
from app.services.storage_path_engine import StoragePathEngine

logger = logging.getLogger("photovault.api.backup")

router = APIRouter()


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------


class StoragePolicyDTO(BaseModel):
    """Storage policy configuration from the client."""

    use_custom_path: bool = False
    custom_path: Optional[str] = None
    use_year_month_layer: bool = False


class ValidatePathRequest(BaseModel):
    """Request body for path validation."""

    source_folder: str
    device_name: str
    storage_policy: StoragePolicyDTO


class ValidatePathResponse(BaseModel):
    """Response body for path validation."""

    is_valid: bool
    resolved_path: str = ""
    error_message: str = ""


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.post("/backup/validate-path", response_model=ValidatePathResponse)
async def validate_path(
    body: ValidatePathRequest,
    current_user: UserInfo = Depends(get_current_user),
) -> ValidatePathResponse:
    """Validate a storage path configuration.

    Uses StoragePathEngine to resolve an example path based on the provided
    storage policy, then validates the resolved path for illegal characters
    and length limits.
    """
    settings = get_settings()

    # Convert DTO to internal StoragePolicy model
    policy = StoragePolicy(
        use_custom_path=body.storage_policy.use_custom_path,
        custom_path=body.storage_policy.custom_path,
        use_year_month_layer=body.storage_policy.use_year_month_layer,
    )

    # Use empty FileMetadata for example path generation
    file_metadata = FileMetadata()

    # Resolve the example path
    resolved_path = StoragePathEngine.resolve_path(
        storage_root=settings.storage_root,
        username=current_user.username,
        device_name=body.device_name,
        source_folder=body.source_folder,
        policy=policy,
        file_metadata=file_metadata,
    )

    # Validate the resolved path
    validation_result = StoragePathEngine.validate_path(resolved_path)

    if not validation_result.is_valid:
        return ValidatePathResponse(
            is_valid=False,
            resolved_path=resolved_path,
            error_message=validation_result.error_message,
        )

    return ValidatePathResponse(
        is_valid=True,
        resolved_path=resolved_path,
        error_message="",
    )

# ---------------------------------------------------------------------------
# Duplicate Check models
# ---------------------------------------------------------------------------


class DuplicateCheckRequest(BaseModel):
    """Request body for duplicate detection."""

    file_hash: str
    file_path: str  # original path on phone
    device_name: str


class DuplicateCheckResponse(BaseModel):
    """Response body for duplicate detection."""

    is_duplicate: bool
    file_id: Optional[int] = None
    status: str = "active"  # active/trashed/purged/not_found
    expires_at: Optional[str] = None  # trash expiry time for trashed files


# ---------------------------------------------------------------------------
# Duplicate Check endpoint
# ---------------------------------------------------------------------------


@router.post("/backup/check", response_model=DuplicateCheckResponse)
async def check_duplicate(
    body: DuplicateCheckRequest,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> DuplicateCheckResponse:
    """Check if a file has already been backed up (duplicate detection).

    Uses the file's SHA-256 hash to check if it already exists in the
    current user's records. Ensures user isolation — only checks within
    the authenticated user's file records.

    If the same hash exists (regardless of path), returns is_duplicate=True.
    The actual reference creation happens during upload completion.
    """
    dedup_service = DeduplicationService(db)
    existing_record = await dedup_service.check(
        user_id=current_user.id,
        file_hash=body.file_hash,
    )

    if existing_record is not None:
        return DuplicateCheckResponse(
            is_duplicate=True,
            file_id=existing_record.id,
            status="active",
        )

    settings = get_settings()
    # Check if file exists in trash or purged (allow re-upload)
    cursor = await db.execute(
        """SELECT id, deleted_at, purged_at FROM file_records
           WHERE user_id = ? AND file_hash = ?
           AND (deleted_at IS NOT NULL OR purged_at IS NOT NULL)
           LIMIT 1""",
        (current_user.id, body.file_hash),
    )
    row = await cursor.fetchone()
    if row is not None:
        status = "purged" if row["purged_at"] is not None else "trashed"
        expires_at = None
        if status == "trashed" and row["deleted_at"]:
            from datetime import datetime, timedelta, timezone
            try:
                deleted = datetime.fromisoformat(row["deleted_at"])
                if deleted.tzinfo is None:
                    deleted = deleted.replace(tzinfo=timezone.utc)
                expires_at = (deleted + timedelta(days=settings.trash_retention_days)).isoformat()
            except (ValueError, TypeError):
                pass
        return DuplicateCheckResponse(
            is_duplicate=False,
            file_id=None,
            status=status,
            expires_at=expires_at,
        )

    return DuplicateCheckResponse(
        is_duplicate=False,
        file_id=None,
        status="not_found",
    )


# ---------------------------------------------------------------------------
# Upload Session models
# ---------------------------------------------------------------------------


class InitUploadRequest(BaseModel):
    """Request body for initializing an upload session."""

    file_hash: str
    file_name: str
    file_size: int
    file_path: str  # original path on phone
    device_name: str
    source_folder: str
    storage_policy: StoragePolicyDTO
    exif_time: Optional[str] = None
    mime_type: Optional[str] = None

class InitUploadResponse(BaseModel):
    """Response body for upload initialization."""

    session_id: str
    total_chunks: int
    chunk_size: int
    is_duplicate: bool = False
    file_id: Optional[int] = None


class ChunkUploadResponse(BaseModel):
    """Response body for chunk upload."""

    chunk_index: int
    received: bool
    checksum_valid: bool = True


class CompleteUploadRequest(BaseModel):
    """Request body for completing an upload."""

    session_id: str


class CompleteUploadResponse(BaseModel):
    """Response body for upload completion."""

    success: bool
    file_id: Optional[int] = None
    stored_path: str = ""


class ResumeInfoResponse(BaseModel):
    """Response body for resume info."""

    session_id: str
    received_chunks: List[int]
    total_chunks: int
    file_hash: str
    expires_at: str


# ---------------------------------------------------------------------------
# Upload Session endpoints
# ---------------------------------------------------------------------------


@router.post("/backup/init", response_model=InitUploadResponse)
async def init_upload(
    body: InitUploadRequest,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> InitUploadResponse:
    """Initialize an upload session.

    Uses UploadService to orchestrate the full initialization flow:
    dedup check → path resolution → disk space check → session creation.
    """
    from app.services.upload_service import (
        DiskSpaceError,
        DirectoryCreationError,
        InitUploadInfo,
        UploadService,
    )

    settings = get_settings()

    policy = StoragePolicy(
        use_custom_path=body.storage_policy.use_custom_path,
        custom_path=body.storage_policy.custom_path,
        use_year_month_layer=body.storage_policy.use_year_month_layer,
    )

    file_metadata = FileMetadata()
    if body.exif_time:
        from datetime import datetime as dt

        try:
            file_metadata = FileMetadata(exif_time=dt.fromisoformat(body.exif_time))
        except (ValueError, TypeError):
            pass

    file_info = InitUploadInfo(
        file_hash=body.file_hash,
        file_name=body.file_name,
        file_size=body.file_size,
        file_path=body.file_path,
        device_name=body.device_name,
        source_folder=body.source_folder,
        storage_policy=policy,
        file_metadata=file_metadata,
        mime_type=body.mime_type,
    )

    upload_service = UploadService(db, settings.storage_root)

    try:
        result = await upload_service.init_upload(
            user_id=current_user.id,
            username=current_user.username,
            file_info=file_info,
        )
    except DiskSpaceError as e:
        raise HTTPException(status_code=507, detail=str(e))
    except DirectoryCreationError as e:
        raise HTTPException(status_code=500, detail=str(e))

    if result.is_duplicate:
        # Return a response indicating duplicate — client should skip upload
        return InitUploadResponse(
            session_id="",
            total_chunks=0,
            chunk_size=0,
            is_duplicate=True,
            file_id=result.file_id,
        )

    return InitUploadResponse(
        session_id=result.session_id or "",
        total_chunks=result.total_chunks or 0,
        chunk_size=result.chunk_size or ChunkManager.CHUNK_SIZE,
    )


@router.post("/backup/chunk", response_model=ChunkUploadResponse)
async def upload_chunk(
    session_id: str = Form(...),
    chunk_index: int = Form(...),
    checksum: str = Form(...),
    file: UploadFile = File(...),
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> ChunkUploadResponse:
    """Upload a single chunk.

    Accepts multipart form data with session_id, chunk_index, checksum (MD5),
    and the chunk file data. Verifies the MD5 checksum before storing.
    """
    settings = get_settings()
    chunk_manager = ChunkManager(db, settings.storage_root)

    data = await file.read()

    try:
        await chunk_manager.store_chunk(
            session_id=session_id,
            chunk_index=chunk_index,
            data=data,
            md5_checksum=checksum,
        )
    except SessionNotFoundError:
        raise HTTPException(status_code=404, detail="Upload session not found")
    except ChunkChecksumError as e:
        raise HTTPException(status_code=422, detail=str(e))

    return ChunkUploadResponse(chunk_index=chunk_index, received=True)


@router.post("/backup/complete", response_model=CompleteUploadResponse)
async def complete_upload(
    body: CompleteUploadRequest,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> CompleteUploadResponse:
    """Complete an upload session.

    Uses UploadService to orchestrate the full completion flow:
    merge chunks → verify integrity → resolve conflicts → move → register.
    """
    from app.services.upload_service import UploadService

    settings = get_settings()
    upload_service = UploadService(db, settings.storage_root)

    result = await upload_service.complete_upload(
        session_id=body.session_id,
        user_id=current_user.id,
    )

    if not result.success:
        if "not found" in result.error.lower():
            raise HTTPException(status_code=404, detail=result.error)
        elif "integrity" in result.error.lower():
            raise HTTPException(status_code=422, detail=result.error)
        else:
            raise HTTPException(status_code=400, detail=result.error)

    # Reactivate trashed/purged record if this is a re-upload
    cursor = await db.execute(
        "SELECT file_hash FROM upload_sessions WHERE id = ?",
        (body.session_id,),
    )
    session_row = await cursor.fetchone()
    if session_row:
        from app.services.file_browse_service import FileBrowseService
        browse_service = FileBrowseService(db, settings.storage_root, settings.trash_retention_days)
        await browse_service.reactivate_record(
            user_id=current_user.id,
            file_hash=session_row["file_hash"],
        )

    return CompleteUploadResponse(
        success=True,
        file_id=result.file_id,
        stored_path=result.stored_path,
    )


@router.get("/backup/resume/{session_id}", response_model=ResumeInfoResponse)
async def get_resume_info(
    session_id: str,
    current_user: UserInfo = Depends(get_current_user),
    db: aiosqlite.Connection = Depends(get_db),
) -> ResumeInfoResponse:
    """Get resume info for an upload session.

    Returns the list of already-received chunks so the client can resume
    uploading from where it left off.
    """
    settings = get_settings()
    chunk_manager = ChunkManager(db, settings.storage_root)

    session = await chunk_manager.get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Upload session not found")

    # Verify session belongs to current user
    if session["user_id"] != current_user.id:
        raise HTTPException(status_code=404, detail="Upload session not found")

    received = await chunk_manager.get_received_chunks(session_id)

    return ResumeInfoResponse(
        session_id=session_id,
        received_chunks=received,
        total_chunks=session["total_chunks"],
        file_hash=session["file_hash"],
        expires_at=session["expires_at"],
    )
