"""Upload management service.

Orchestrates the upload flow: deduplication check -> path resolution ->
directory creation -> chunk management -> file registration.

Provides:
- ``init_upload`` — Full upload initialization (dedup check, path resolve, disk check, session create).
- ``complete_upload`` — Full upload completion (merge, verify, conflict resolve, move, register).
- ``check_disk_space`` — Verify sufficient disk space for an upload.
"""

from __future__ import annotations

import logging
import math
import mimetypes
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import aiosqlite

from app.models.storage import FileMetadata, StoragePolicy
from app.services.analysis_queue import enqueue_analysis
from app.services.chunk_manager import ChunkManager
from app.services.deduplication_service import DeduplicationService, FileRecord
from app.services.motion_photo import detect_motion_photo, detect_ultra_hdr
from app.services.storage_path_engine import StoragePathEngine

logger = logging.getLogger("photovault.upload")

# Minimum buffer space required beyond the file itself (100 MB)
_DISK_SPACE_BUFFER = 100 * 1024 * 1024


# ---------------------------------------------------------------------------
# Data classes for request/response
# ---------------------------------------------------------------------------


@dataclass
class InitUploadInfo:
    """Input data for upload initialization."""

    file_hash: str
    file_name: str
    file_size: int
    file_path: str  # original path on phone
    device_name: str
    source_folder: str
    storage_policy: StoragePolicy
    file_metadata: FileMetadata
    mime_type: Optional[str] = None


@dataclass
class InitUploadResult:
    """Result of upload initialization."""

    is_duplicate: bool
    file_id: Optional[int] = None
    session_id: Optional[str] = None
    total_chunks: Optional[int] = None
    chunk_size: Optional[int] = None
    target_path: Optional[str] = None


@dataclass
class CompleteUploadResult:
    """Result of upload completion."""

    success: bool
    file_id: Optional[int] = None
    stored_path: str = ""
    is_duplicate: bool = False
    error: str = ""


# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------


class DiskSpaceError(Exception):
    """Raised when there is insufficient disk space for an upload."""

    def __init__(self, required: int, available: int):
        self.required = required
        self.available = available
        super().__init__(
            f"Insufficient disk space: required {required} bytes, "
            f"available {available} bytes"
        )


class DirectoryCreationError(Exception):
    """Raised when a target directory cannot be created."""

    def __init__(self, path: str, reason: str):
        self.path = path
        self.reason = reason
        super().__init__(f"Failed to create directory '{path}': {reason}")


# ---------------------------------------------------------------------------
# UploadService
# ---------------------------------------------------------------------------


class UploadService:
    """Orchestrates the full upload workflow.

    Integrates deduplication detection, chunk management, and storage path
    resolution into a cohesive upload flow.
    """

    def __init__(self, db: aiosqlite.Connection, storage_root: str):
        """Initialize UploadService.

        Args:
            db: An aiosqlite connection with row_factory set to aiosqlite.Row.
            storage_root: Root directory for file storage.
        """
        self._db = db
        self._storage_root = storage_root
        self._chunk_manager = ChunkManager(db, storage_root)
        self._dedup_service = DeduplicationService(db)

    async def init_upload(
        self,
        user_id: int,
        username: str,
        file_info: InitUploadInfo,
    ) -> InitUploadResult:
        """Full upload initialization flow.

        Steps:
        1. Check duplicate (if already backed up, return early with is_duplicate=True)
        2. Resolve target path using StoragePathEngine
        3. Check disk space
        4. Create target directory
        5. Create upload session

        Args:
            user_id: The authenticated user's ID.
            username: The authenticated user's username.
            file_info: Upload initialization parameters.

        Returns:
            InitUploadResult with session info or duplicate status.

        Raises:
            DiskSpaceError: If insufficient disk space.
            DirectoryCreationError: If target directory cannot be created.
        """
        # Step 1: Check for duplicates
        existing_record = await self._dedup_service.check(
            user_id=user_id,
            file_hash=file_info.file_hash,
        )
        if existing_record is not None:
            logger.info(
                "Duplicate detected for user %d, hash %s (file_id=%d)",
                user_id,
                file_info.file_hash,
                existing_record.id,
            )
            return InitUploadResult(
                is_duplicate=True,
                file_id=existing_record.id,
            )

        # Step 2: Resolve target path
        target_path = StoragePathEngine.resolve_path(
            storage_root=self._storage_root,
            username=username,
            device_name=file_info.device_name,
            source_folder=file_info.source_folder,
            policy=file_info.storage_policy,
            file_metadata=file_info.file_metadata,
        )

        # Step 3: Check disk space
        # Need space for chunks + final file + buffer
        required_space = file_info.file_size * 2 + _DISK_SPACE_BUFFER
        if not self.check_disk_space(required_space):
            usage = shutil.disk_usage(self._storage_root)
            raise DiskSpaceError(
                required=required_space,
                available=usage.free,
            )

        # Step 4: Create target directory
        self._ensure_directory(target_path)

        # Step 5: Create upload session
        session_id = await self._chunk_manager.create_session(
            user_id=user_id,
            file_hash=file_info.file_hash,
            file_name=file_info.file_name,
            file_size=file_info.file_size,
            target_path=target_path,
            device_name=file_info.device_name,
            original_path=file_info.file_path,
            exif_time=file_info.file_metadata.exif_time,
            mime_type=file_info.mime_type,
        )

        total_chunks = math.ceil(file_info.file_size / ChunkManager.CHUNK_SIZE)

        logger.info(
            "Upload session created: session_id=%s, user=%d, file=%s, chunks=%d",
            session_id,
            user_id,
            file_info.file_name,
            total_chunks,
        )

        return InitUploadResult(
            is_duplicate=False,
            session_id=session_id,
            total_chunks=total_chunks,
            chunk_size=ChunkManager.CHUNK_SIZE,
            target_path=target_path,
        )

    async def complete_upload(
        self, session_id: str, user_id: int
    ) -> CompleteUploadResult:
        """Full upload completion flow.

        Steps:
        1. Verify all chunks received
        2. Merge chunks
        3. Verify integrity (SHA-256)
        4. Handle filename conflicts (same content = skip, different = append _1, _2...)
        5. Move to target path
        6. Register file record
        7. Cleanup session chunks

        Args:
            session_id: The upload session ID.
            user_id: The authenticated user's ID.

        Returns:
            CompleteUploadResult with file info or error.
        """
        # Get session info
        session = await self._chunk_manager.get_session(session_id)
        if session is None:
            return CompleteUploadResult(
                success=False,
                error="Upload session not found",
            )

        # Verify session belongs to user
        if session["user_id"] != user_id:
            return CompleteUploadResult(
                success=False,
                error="Upload session not found",
            )

        # Step 1: Verify all chunks received
        received = await self._chunk_manager.get_received_chunks(session_id)
        total_chunks = session["total_chunks"]
        if len(received) != total_chunks:
            return CompleteUploadResult(
                success=False,
                error=f"Not all chunks received: {len(received)}/{total_chunks}",
            )

        # Step 2: Merge chunks
        try:
            merged_path = await self._chunk_manager.merge_chunks(session_id)
        except FileNotFoundError as e:
            return CompleteUploadResult(
                success=False,
                error=str(e),
            )

        # Step 3: Verify integrity
        computed_hash = self._chunk_manager.compute_file_hash(merged_path)
        merged_size = Path(merged_path).stat().st_size
        if computed_hash != session["file_hash"]:
            logger.warning(
                "Integrity check FAILED for session=%s file=%s: "
                "expected_hash=%s computed_hash=%s expected_size=%s merged_size=%s "
                "total_chunks=%s received_chunks=%s",
                session_id,
                session["file_name"],
                session["file_hash"],
                computed_hash,
                session["file_size"],
                merged_size,
                total_chunks,
                len(received),
            )
            # Cleanup on failure
            await self._chunk_manager.cleanup_session(session_id)
            return CompleteUploadResult(
                success=False,
                error="File integrity verification failed",
            )

        # Step 4 & 5: Handle filename conflicts and move to target path
        target_dir = Path(session["target_path"])
        self._ensure_directory(str(target_dir) + "/")

        final_path = target_dir / session["file_name"]

        if final_path.exists():
            # Check if content is the same
            existing_hash = self._chunk_manager.compute_file_hash(str(final_path))
            if existing_hash == session["file_hash"]:
                # Same content — skip storage, just register as reference
                logger.info(
                    "File already exists with same content at %s, skipping storage",
                    final_path,
                )
                # Remove merged file since we don't need it
                Path(merged_path).unlink(missing_ok=True)

                # Resolve MIME type and derive the media type (image vs video)
                mime_type = self._resolve_mime_type(session)
                media_type = self._infer_media_type(session["file_name"], mime_type)
                is_motion, motion_offset = detect_motion_photo(str(final_path))
                is_ultra_hdr = detect_ultra_hdr(str(final_path))

                # Register file record (pointing to existing file). Reactivates an
                # existing trashed/purged record so a re-backup restores it in place.
                record = await self._dedup_service.register_or_reactivate_file(
                    user_id=user_id,
                    file_hash=session["file_hash"],
                    file_path=str(final_path),
                    original_path=session["original_path"],
                    device_name=session["device_name"],
                    file_size=session["file_size"],
                    file_name=session["file_name"],
                    mime_type=mime_type,
                    exif_time=session["exif_time"],
                    focal_length=self._extract_focal_length(str(final_path)),
                    media_type=media_type,
                    is_motion_photo=is_motion,
                    motion_video_offset=motion_offset,
                    is_ultra_hdr=is_ultra_hdr,
                )

                # Update session status
                await self._db.execute(
                    "UPDATE upload_sessions SET status = 'completed' WHERE id = ?",
                    (session_id,),
                )
                await self._db.commit()

                # Hook into the analysis pipeline (non-blocking, best-effort;
                # never fails the upload response — requirement 7.1).
                enqueue_analysis(record.id, user_id)

                # Cleanup chunk directory
                self._cleanup_chunk_dir(session_id)

                return CompleteUploadResult(
                    success=True,
                    file_id=record.id,
                    stored_path=str(final_path),
                    is_duplicate=True,
                )
            else:
                # Different content — append numeric suffix
                final_path = self._resolve_filename_conflict(final_path)

        # Move merged file to target path
        shutil.move(merged_path, str(final_path))

        # Resolve MIME type and derive the media type (image vs video)
        mime_type = self._resolve_mime_type(session)
        media_type = self._infer_media_type(session["file_name"], mime_type)
        is_motion, motion_offset = detect_motion_photo(str(final_path))
        is_ultra_hdr = detect_ultra_hdr(str(final_path))

        # Step 6: Register file record. Reactivates an existing trashed/purged
        # record so a re-backup restores it in place instead of leaving a stale row.
        record = await self._dedup_service.register_or_reactivate_file(
            user_id=user_id,
            file_hash=session["file_hash"],
            file_path=str(final_path),
            original_path=session["original_path"],
            device_name=session["device_name"],
            file_size=session["file_size"],
            file_name=session["file_name"],
            mime_type=mime_type,
            exif_time=session["exif_time"],
            focal_length=self._extract_focal_length(str(final_path)),
            media_type=media_type,
            is_motion_photo=is_motion,
            motion_video_offset=motion_offset,
            is_ultra_hdr=is_ultra_hdr,
        )

        # Update session status
        await self._db.execute(
            "UPDATE upload_sessions SET status = 'completed' WHERE id = ?",
            (session_id,),
        )
        await self._db.commit()

        # Hook into the analysis pipeline (non-blocking, best-effort; never
        # fails the upload response — requirement 7.1).
        enqueue_analysis(record.id, user_id)

        # Step 7: Cleanup chunk directory
        self._cleanup_chunk_dir(session_id)

        logger.info(
            "Upload completed: session_id=%s, file_id=%d, path=%s",
            session_id,
            record.id,
            final_path,
        )

        return CompleteUploadResult(
            success=True,
            file_id=record.id,
            stored_path=str(final_path),
        )

    def check_disk_space(self, required_bytes: int) -> bool:
        """Check if storage_root has enough free space.

        Args:
            required_bytes: Number of bytes needed.

        Returns:
            True if sufficient space is available.
        """
        try:
            usage = shutil.disk_usage(self._storage_root)
            return usage.free >= required_bytes
        except OSError:
            # If we can't check disk space, assume insufficient
            return False

    # ---------------------------------------------------------------------------
    # Private helpers
    # ---------------------------------------------------------------------------

    def _ensure_directory(self, path: str) -> None:
        """Create directory recursively if it doesn't exist.

        Args:
            path: Directory path to create (may end with /).

        Raises:
            DirectoryCreationError: If directory creation fails.
        """
        dir_path = Path(path.rstrip("/"))
        try:
            dir_path.mkdir(parents=True, exist_ok=True)
        except PermissionError as e:
            raise DirectoryCreationError(str(dir_path), f"Permission denied: {e}")
        except OSError as e:
            raise DirectoryCreationError(str(dir_path), str(e))

    @staticmethod
    def _resolve_filename_conflict(file_path: Path) -> Path:
        """Resolve filename conflict by appending numeric suffix.

        If target file exists with different content, appends _1, _2, etc.
        to the filename stem until a non-existing path is found.

        Args:
            file_path: The conflicting file path.

        Returns:
            A new Path with numeric suffix that doesn't exist.
        """
        stem = file_path.stem
        suffix = file_path.suffix
        parent = file_path.parent
        counter = 1
        while True:
            new_path = parent / f"{stem}_{counter}{suffix}"
            if not new_path.exists():
                return new_path
            counter += 1

    @staticmethod
    def _resolve_mime_type(session: dict) -> Optional[str]:
        """Resolve the MIME type for a completed upload.

        Prefers the client-supplied MIME type stored on the session; falls back
        to guessing from the file name extension.

        Args:
            session: The upload session dict.

        Returns:
            A MIME type string, or None if it cannot be determined.
        """
        mime = session.get("mime_type")
        if mime:
            return mime
        guessed, _ = mimetypes.guess_type(session.get("file_name", ""))
        return guessed

    # Extensions that identify video files, in case the MIME type is missing
    # or generic (e.g. application/octet-stream).
    _VIDEO_EXTENSIONS = {
        ".mp4", ".mov", ".mkv", ".webm", ".3gp", ".avi", ".mpeg", ".mpg",
        ".wmv", ".flv", ".m4v", ".ts", ".m2ts", ".mts",
    }

    @classmethod
    def _infer_media_type(cls, file_name: str, mime_type: Optional[str]) -> str:
        """Infer the media type ('image' or 'video') from MIME type / extension.

        Args:
            file_name: The file name (used for extension fallback).
            mime_type: The resolved MIME type, if any.

        Returns:
            'video' for video files, otherwise 'image'.
        """
        if mime_type and mime_type.lower().startswith("video/"):
            return "video"
        ext = Path(file_name).suffix.lower()
        if ext in cls._VIDEO_EXTENSIONS:
            return "video"
        return "image"

    @staticmethod
    def _extract_focal_length(file_path: str) -> Optional[float]:
        """Extract the focal length (mm) from an image's EXIF metadata.

        Prefers the 35mm-equivalent focal length (tag 41989) for consistency
        across sensors, falling back to the raw FocalLength (tag 37386).

        Args:
            file_path: Path to the image file.

        Returns:
            Focal length in mm, or None if unavailable / not an image.
        """
        try:
            from PIL import Image

            with Image.open(file_path) as img:
                exif = img.getexif()
                if not exif:
                    return None

                # 41989 = FocalLengthIn35mmFilm, 37386 = FocalLength
                # The 35mm-equivalent lives in the ExifIFD sub-directory.
                focal_35mm = None
                focal_raw = None
                try:
                    from PIL.ExifTags import IFD

                    exif_ifd = exif.get_ifd(IFD.Exif)
                    focal_35mm = exif_ifd.get(41989)
                    focal_raw = exif_ifd.get(37386)
                except Exception:
                    pass

                # Fallback: some files expose the tags on the root IFD
                if focal_35mm is None:
                    focal_35mm = exif.get(41989)
                if focal_raw is None:
                    focal_raw = exif.get(37386)

                value = focal_35mm if focal_35mm else focal_raw
                if value is None:
                    return None

                # EXIF rationals may come back as a Fraction/IFDRational
                focal = float(value)
                if focal <= 0:
                    return None
                return round(focal, 1)
        except Exception as e:
            logger.debug("Could not extract focal length from %s: %s", file_path, e)
            return None

    def _cleanup_chunk_dir(self, session_id: str) -> None:
        """Remove the chunk directory for a session.

        Args:
            session_id: The upload session ID.
        """
        chunk_dir = self._chunk_manager._session_chunk_dir(session_id)
        if chunk_dir.exists():
            shutil.rmtree(chunk_dir)
