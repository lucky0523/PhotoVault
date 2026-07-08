"""File browse service.

Provides directory listing, thumbnail generation, and file download capabilities.
Ensures user isolation - each user can only access their own files.
"""

from __future__ import annotations

import fcntl
import logging
import os
import shutil
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import AsyncGenerator, Optional

import aiosqlite

logger = logging.getLogger("photovault.file_browse")


def derive_file_status(deleted_at: Optional[str], purged_at: Optional[str]) -> str:
    """Derive a file's status bucket from its lifecycle columns.

    Mirrors get_device_stats:
    - purged_at IS NOT NULL                      -> "purged"
    - deleted_at IS NOT NULL (and not purged)    -> "trashed"
    - otherwise                                  -> "backed_up"

    Args:
        deleted_at: The file's deleted_at column value (None if not trashed).
        purged_at: The file's purged_at column value (None if not purged).

    Returns:
        One of "purged", "trashed", or "backed_up".
    """
    if purged_at is not None:
        return "purged"
    if deleted_at is not None:
        return "trashed"
    return "backed_up"


# Path segment marking a physical file that lives in the recycle bin.
_TRASH_SEGMENT = "/.trash/"


def effective_file_status(
    deleted_at: Optional[str], purged_at: Optional[str], file_path: Optional[str]
) -> str:
    """Status bucket hardened against inconsistent "zombie" records.

    A record whose lifecycle columns say "backed_up" (deleted_at/purged_at both
    NULL) but whose physical file still lives under ``.trash/`` is contradictory
    — such rows were produced by an earlier re-backup bug. They must NOT inflate
    the backed-up counts (that made deleted folders keep showing a file count),
    so they are bucketed as "trashed" to reflect their physical location.

    Normal active files (path not under .trash) and normal trashed/purged files
    (which carry deleted_at/purged_at) are unaffected.
    """
    status = derive_file_status(deleted_at, purged_at)
    if status == "backed_up" and file_path and _TRASH_SEGMENT in file_path:
        return "trashed"
    return status


@dataclass
class DirectoryInfo:
    """Information about a subdirectory."""

    name: str
    path: str
    file_count: int = 0
    size: int = 0
    latest_file_time: Optional[str] = None
    # Per-directory counts bucketed by status. file_count remains the sum of
    # these three buckets (total files in the directory across all statuses).
    backed_up_count: int = 0
    trashed_count: int = 0
    purged_count: int = 0


@dataclass
class FileBrowseInfo:
    """Information about a file for browsing."""

    id: int
    file_name: str
    file_size: int
    mime_type: Optional[str] = None
    exif_time: Optional[str] = None
    media_type: str = "image"
    is_motion_photo: bool = False
    is_ultra_hdr: bool = False
    created_at: Optional[str] = None
    device_name: Optional[str] = None
    focal_length: Optional[float] = None


@dataclass
class DirectoryListing:
    """Result of a directory listing operation."""

    current_path: str
    parent_path: Optional[str] = None
    directories: list[DirectoryInfo] = field(default_factory=list)
    files: list[FileBrowseInfo] = field(default_factory=list)
    total_files: int = 0
    page: int = 1
    page_size: int = 50


@dataclass
class FileDetail:
    """Detailed information about a specific file."""

    id: int
    file_name: str
    file_path: str
    original_path: str
    device_name: str
    file_size: int
    file_hash: str
    mime_type: Optional[str] = None
    exif_time: Optional[str] = None
    media_type: str = "image"
    is_motion_photo: bool = False
    motion_video_offset: Optional[int] = None
    is_ultra_hdr: bool = False
    created_at: Optional[str] = None
    is_reference: bool = False
    reference_to: Optional[int] = None


@dataclass
class TrashItemInfo:
    """Information about a file in the trash."""

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
    reference_to: Optional[int] = None


@dataclass
class StatusChangeInfo:
    """File status change info for client sync."""

    file_hash: str
    status: str  # 'trashed' or 'purged'
    deleted_at: Optional[str] = None
    purged_at: Optional[str] = None
    expires_at: Optional[str] = None


@dataclass
class DeviceStats:
    """Per-device file counts broken down by status."""

    name: str
    path: str
    backed_up_count: int = 0
    trashed_count: int = 0
    purged_count: int = 0
    latest_file_time: Optional[str] = None


class FileBrowseService:
    """Service for browsing user files, generating thumbnails, and streaming downloads.

    Ensures user isolation — all operations are scoped to the authenticated user.
    """

    THUMBNAIL_SIZES = {
        "small": (200, 200),
        "medium": (600, 600),
    }

    def __init__(self, db: aiosqlite.Connection, storage_root: str, trash_retention_days: int = 30):
        """Initialize with a database connection and storage root path.

        Args:
            db: An aiosqlite connection with row_factory set to aiosqlite.Row.
            storage_root: The root directory for file storage.
            trash_retention_days: Number of days to retain trash items before auto-purge.
        """
        self._db = db
        self._storage_root = storage_root
        self._trash_retention_days = trash_retention_days
        self._lock_files: dict[str, int] = {}

    def _get_lock_path(self, username: str, file_hash: str) -> str:
        """Get the lock file path for a file.

        Args:
            username: Current user's username.
            file_hash: The file's hash.

        Returns:
            Lock file path.
        """
        lock_dir = f"{self._storage_root}/{username}/.locks"
        os.makedirs(lock_dir, exist_ok=True)
        return f"{lock_dir}/{file_hash}.lock"

    def _acquire_lock(self, username: str, file_hash: str) -> Optional[int]:
        """Acquire a file lock.

        Args:
            username: Current user's username.
            file_hash: The file's hash.

        Returns:
            File descriptor of the lock file, or None if failed.
        """
        lock_path = self._get_lock_path(username, file_hash)
        try:
            fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o644)
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._lock_files[file_hash] = fd
            logger.debug("Acquired lock for file hash %s", file_hash)
            return fd
        except OSError:
            logger.debug("Failed to acquire lock for file hash %s", file_hash)
            return None

    def _release_lock(self, username: str, file_hash: str) -> None:
        """Release a file lock and delete the lock file.

        Args:
            username: Current user's username.
            file_hash: The file's hash.
        """
        fd = self._lock_files.get(file_hash)
        if fd is not None:
            try:
                fcntl.flock(fd, fcntl.LOCK_UN)
                os.close(fd)
                # Delete the lock file
                lock_path = self._get_lock_path(username, file_hash)
                try:
                    os.unlink(lock_path)
                    logger.debug("Released and deleted lock file for %s", file_hash)
                except OSError:
                    logger.debug("Lock file already deleted: %s", lock_path)
            except OSError:
                logger.warning("Failed to release lock for file hash %s", file_hash)
            del self._lock_files[file_hash]

    def _get_trash_path(self, username: str, device_name: str) -> str:
        """Get the trash directory path for a user and device.

        Args:
            username: Current user's username.
            device_name: The device name.

        Returns:
            Trash directory path.
        """
        trash_path = f"{self._storage_root}/{username}/.trash/{device_name}"
        os.makedirs(trash_path, exist_ok=True)
        return trash_path

    def _move_to_trash(self, username: str, device_name: str, file_path: str) -> Optional[str]:
        """Move a physical file to trash.

        Args:
            username: Current user's username.
            device_name: The device name.
            file_path: Current file path.

        Returns:
            New path in trash, or None if failed.
        """
        try:
            src = Path(file_path)
            if not src.exists():
                logger.warning("File not found for move to trash: %s", file_path)
                return None

            trash_dir = Path(self._get_trash_path(username, device_name))
            relative_path = Path(file_path).relative_to(
                Path(self._storage_root) / username / device_name
            )
            dst = trash_dir / relative_path

            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))
            logger.info("Moved file to trash: %s -> %s", file_path, str(dst))
            return str(dst)
        except Exception as e:
            logger.error("Failed to move file to trash %s: %s", file_path, e)
            return None

    def _restore_from_trash(self, username: str, device_name: str, file_path: str) -> Optional[str]:
        """Restore a physical file from trash.

        Args:
            username: Current user's username.
            device_name: The device name.
            file_path: Current file path (in trash).

        Returns:
            New path in original location, or None if failed.
        """
        try:
            src = Path(file_path)
            if not src.exists():
                logger.warning("File not found in trash for restore: %s", file_path)
                return None

            trash_dir = Path(self._get_trash_path(username, device_name))
            relative_path = src.relative_to(trash_dir)
            dst = Path(self._storage_root) / username / device_name / relative_path

            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))
            logger.info("Restored file from trash: %s -> %s", file_path, str(dst))
            return str(dst)
        except Exception as e:
            logger.error("Failed to restore file from trash %s: %s", file_path, e)
            return None

    def _delete_from_trash(self, file_path: str) -> bool:
        """Delete a physical file from trash.

        Args:
            file_path: Path of file in trash.

        Returns:
            True if successful, False otherwise.
        """
        try:
            p = Path(file_path)
            if p.exists():
                p.unlink()
                logger.info("Deleted file from trash: %s", file_path)
                self._cleanup_empty_directories(file_path)
                return True
            return False
        except Exception as e:
            logger.error("Failed to delete file from trash %s: %s", file_path, e)
            return False

    async def list_directory(
        self,
        user_id: int,
        path: str = "",
        page: int = 1,
        page_size: int = 50,
        sort_by: str = "name",
    ) -> DirectoryListing:
        """List directory contents for a user.

        Aggregates from file_records table to build a virtual directory structure.
        path="" means root (shows devices), path="Pixel9Pro" shows that device's folders, etc.

        The virtual path structure is derived from the file_path column by stripping
        the storage_root prefix and username directory.

        Args:
            user_id: The authenticated user's ID.
            path: The virtual path to browse (relative to user root).
            page: Page number (1-based).
            page_size: Number of files per page.
            sort_by: Sort order for files (name, size, time).

        Returns:
            DirectoryListing with subdirectories and files.
        """
        # Normalize path: strip leading/trailing slashes
        path = path.strip("/")

        # Get the username for this user
        username = await self._get_username(user_id)
        if username is None:
            return DirectoryListing(current_path=path)

        # Build the base prefix for file_path matching
        # file_path in DB is like: {storage_root}/{username}/{device}/{source_folder}/file.jpg
        base_prefix = f"{self._storage_root}/{username}/"
        if path:
            search_prefix = f"{base_prefix}{path}/"
        else:
            search_prefix = base_prefix

        # Build directory structure from paths
        directories: dict[str, DirectoryInfo] = {}

        # Get file sizes, times, and lifecycle columns for directory aggregation.
        # Note: this query is NOT pre-filtered on deleted_at/purged_at so that
        # trashed and purged files also participate in the per-directory
        # status-bucket counts.
        #
        # Moving a file to the recycle bin rewrites its file_path to
        # {base}/.trash/{device}/{rel}, so trashed/purged rows no longer sit
        # under search_prefix. We therefore ALSO fetch rows under the mirrored
        # .trash prefix and normalize their path back to the logical (pre-trash)
        # location below, so they still aggregate under their original directory.
        if path:
            trash_search_prefix = f"{base_prefix}.trash/{path}/"
        else:
            trash_search_prefix = f"{base_prefix}.trash/"
        trash_marker = f"{base_prefix}.trash/"
        cursor = await self._db.execute(
            """SELECT file_path, file_size, exif_time, created_at, deleted_at, purged_at
               FROM file_records
               WHERE user_id = ? AND (file_path LIKE ? OR file_path LIKE ?)""",
            (user_id, f"{search_prefix}%", f"{trash_search_prefix}%"),
        )
        all_files = await cursor.fetchall()

        for row in all_files:
            file_path: str = row["file_path"]
            file_size: int = row["file_size"]
            exif_time: Optional[str] = row["exif_time"]
            created_at: str = row["created_at"]
            deleted_at: Optional[str] = row["deleted_at"]
            purged_at: Optional[str] = row["purged_at"]

            # Normalize a trashed/purged path back to its logical location by
            # stripping the ".trash/" segment that sits right after the user root
            # ({base}/.trash/{device}/... -> {base}/{device}/...). Active files
            # are left untouched.
            logical_path = file_path
            if logical_path.startswith(trash_marker):
                logical_path = base_prefix + logical_path[len(trash_marker):]

            # Get the relative path after the search prefix
            relative = logical_path[len(search_prefix):]
            parts = relative.split("/")

            if len(parts) > 1:
                # This file is in a subdirectory
                subdir_name = parts[0]
                if subdir_name and subdir_name not in directories:
                    subdir_path = f"{path}/{subdir_name}" if path else subdir_name
                    directories[subdir_name] = DirectoryInfo(
                        name=subdir_name,
                        path=subdir_path,
                        file_count=0,
                        size=0,
                    )
                if subdir_name:
                    dir_info = directories[subdir_name]
                    dir_info.file_count += 1
                    dir_info.size += file_size
                    # Bucket the file into backed_up / trashed / purged.
                    # Use effective_file_status so an "active but physically in
                    # .trash" zombie record doesn't inflate backed_up_count.
                    status = effective_file_status(deleted_at, purged_at, file_path)
                    if status == "purged":
                        dir_info.purged_count += 1
                    elif status == "trashed":
                        dir_info.trashed_count += 1
                    else:
                        dir_info.backed_up_count += 1
                        # Update latest_file_time from backed_up files only, so
                        # the "latest" semantics stay active-only (unchanged).
                        file_time = exif_time or created_at
                        if file_time:
                            if dir_info.latest_file_time is None or file_time > dir_info.latest_file_time:
                                dir_info.latest_file_time = file_time
            # Files directly in this directory will be fetched separately

        # Get files directly in this directory (not in subdirectories)
        # These are files where the path after search_prefix has no more slashes
        order_clause = self._get_sort_clause(sort_by)
        offset = (page - 1) * page_size

        # Count total files directly in this directory
        cursor = await self._db.execute(
            """SELECT COUNT(*) as cnt FROM file_records
               WHERE user_id = ? AND file_path LIKE ? AND file_path NOT LIKE ?
               AND deleted_at IS NULL AND purged_at IS NULL""",
            (user_id, f"{search_prefix}%", f"{search_prefix}%/%"),
        )
        count_row = await cursor.fetchone()
        total_files = count_row["cnt"] if count_row else 0

        # Fetch paginated files
        cursor = await self._db.execute(
            f"""SELECT id, file_name, file_size, mime_type, exif_time, media_type,
                       is_motion_photo, is_ultra_hdr, created_at
                FROM file_records
                WHERE user_id = ? AND file_path LIKE ? AND file_path NOT LIKE ?
                AND deleted_at IS NULL AND purged_at IS NULL
                ORDER BY {order_clause}
                LIMIT ? OFFSET ?""",
            (user_id, f"{search_prefix}%", f"{search_prefix}%/%", page_size, offset),
        )
        file_rows = await cursor.fetchall()

        files = [
            FileBrowseInfo(
                id=r["id"],
                file_name=r["file_name"],
                file_size=r["file_size"],
                mime_type=r["mime_type"],
                exif_time=r["exif_time"],
                media_type=r["media_type"] or "image",
                is_motion_photo=bool(r["is_motion_photo"]),
                is_ultra_hdr=bool(r["is_ultra_hdr"]),
                created_at=r["created_at"],
            )
            for r in file_rows
        ]

        # Get latest file time for each directory
        for dir_name, dir_info in directories.items():
            dir_prefix = f"{search_prefix}{dir_name}/"
            cursor = await self._db.execute(
                """SELECT MAX(COALESCE(exif_time, created_at)) as latest
                   FROM file_records
                   WHERE user_id = ? AND file_path LIKE ?
                   AND deleted_at IS NULL AND purged_at IS NULL""",
                (user_id, f"{dir_prefix}%"),
            )
            latest_row = await cursor.fetchone()
            if latest_row and latest_row["latest"]:
                dir_info.latest_file_time = latest_row["latest"]

        # Determine parent path
        parent_path: Optional[str] = None
        if path:
            parts = path.split("/")
            if len(parts) > 1:
                parent_path = "/".join(parts[:-1])
            else:
                parent_path = ""

        # Sort directories by name
        sorted_dirs = sorted(directories.values(), key=lambda d: d.name)

        return DirectoryListing(
            current_path=path,
            parent_path=parent_path,
            directories=sorted_dirs,
            files=files,
            total_files=total_files,
            page=page,
            page_size=page_size,
        )

    async def get_device_stats(self, user_id: int) -> list[DeviceStats]:
        """List devices (top-level directories) with file counts broken down by status.

        Status buckets, derived from file_records columns:
        - backed_up: deleted_at IS NULL AND purged_at IS NULL
        - trashed:   deleted_at IS NOT NULL AND purged_at IS NULL (in recycle bin)
        - purged:    purged_at IS NOT NULL (permanently deleted, row retained for history)

        Args:
            user_id: The authenticated user's ID.

        Returns:
            List of DeviceStats, one per top-level device directory, sorted by name.
        """
        username = await self._get_username(user_id)
        if username is None:
            return []

        # Group by the device_name column (stable, unaffected by trash relocation)
        # rather than parsing file_path, which changes to a ".trash/..." path once
        # a file is moved to the recycle bin.
        cursor = await self._db.execute(
            """SELECT device_name, file_path, exif_time, created_at, deleted_at, purged_at
               FROM file_records
               WHERE user_id = ?""",
            (user_id,),
        )
        rows = await cursor.fetchall()

        devices: dict[str, DeviceStats] = {}

        for row in rows:
            device_name: str = row["device_name"]
            if not device_name:
                continue

            device = devices.get(device_name)
            if device is None:
                device = DeviceStats(name=device_name, path=device_name)
                devices[device_name] = device

            # Hardened against "active but physically in .trash" zombie records.
            status = effective_file_status(
                row["deleted_at"], row["purged_at"], row["file_path"]
            )
            if status == "purged":
                device.purged_count += 1
            elif status == "trashed":
                device.trashed_count += 1
            else:
                device.backed_up_count += 1
                file_time = row["exif_time"] or row["created_at"]
                if file_time and (
                    device.latest_file_time is None or file_time > device.latest_file_time
                ):
                    device.latest_file_time = file_time

        return sorted(devices.values(), key=lambda d: d.name)

    async def list_all_files(
        self,
        user_id: int,
        page: int = 1,
        page_size: int = 200,
        sort_by: str = "time",
    ) -> DirectoryListing:
        """List ALL of a user's files across every directory (flat, recursive).

        Unlike list_directory (which only returns files directly under a given
        path), this returns every non-deleted file regardless of how deeply it is
        nested under device/source-folder directories. Used by the timeline view,
        which groups photos by capture/upload time rather than by folder.

        Args:
            user_id: The authenticated user's ID.
            page: Page number (1-based).
            page_size: Number of files per page.
            sort_by: Sort order (name, size, time). Defaults to time.

        Returns:
            DirectoryListing with a flat file list (no directories).
        """
        order_clause = self._get_sort_clause(sort_by)
        offset = (page - 1) * page_size

        cursor = await self._db.execute(
            """SELECT COUNT(*) as cnt FROM file_records
               WHERE user_id = ? AND deleted_at IS NULL AND purged_at IS NULL""",
            (user_id,),
        )
        count_row = await cursor.fetchone()
        total_files = count_row["cnt"] if count_row else 0

        cursor = await self._db.execute(
            f"""SELECT id, file_name, file_size, mime_type, exif_time, media_type,
                       is_motion_photo, is_ultra_hdr, created_at, device_name, focal_length
                FROM file_records
                WHERE user_id = ? AND deleted_at IS NULL AND purged_at IS NULL
                ORDER BY {order_clause}
                LIMIT ? OFFSET ?""",
            (user_id, page_size, offset),
        )
        file_rows = await cursor.fetchall()

        files = [
            FileBrowseInfo(
                id=r["id"],
                file_name=r["file_name"],
                file_size=r["file_size"],
                mime_type=r["mime_type"],
                exif_time=r["exif_time"],
                media_type=r["media_type"] or "image",
                is_motion_photo=bool(r["is_motion_photo"]),
                is_ultra_hdr=bool(r["is_ultra_hdr"]),
                created_at=r["created_at"],
                device_name=r["device_name"],
                focal_length=r["focal_length"],
            )
            for r in file_rows
        ]

        return DirectoryListing(
            current_path="",
            parent_path=None,
            directories=[],
            files=files,
            total_files=total_files,
            page=page,
            page_size=page_size,
        )

    async def get_file_info(self, user_id: int, file_id: int) -> Optional[FileDetail]:
        """Get detailed info about a specific file. Ensures user isolation.

        Args:
            user_id: The authenticated user's ID.
            file_id: The file record ID.

        Returns:
            FileDetail if found and belongs to user, None otherwise.
        """
        cursor = await self._db.execute(
            """SELECT id, file_name, file_path, original_path, device_name,
                      file_size, file_hash, mime_type, exif_time, media_type,
                      is_motion_photo, motion_video_offset, is_ultra_hdr,
                      created_at, is_reference, reference_to
               FROM file_records
               WHERE id = ? AND user_id = ?""",
            (file_id, user_id),
        )
        row = await cursor.fetchone()
        if row is None:
            return None

        return FileDetail(
            id=row["id"],
            file_name=row["file_name"],
            file_path=row["file_path"],
            original_path=row["original_path"],
            device_name=row["device_name"],
            file_size=row["file_size"],
            file_hash=row["file_hash"],
            mime_type=row["mime_type"],
            exif_time=row["exif_time"],
            media_type=row["media_type"] or "image",
            is_motion_photo=bool(row["is_motion_photo"]),
            motion_video_offset=row["motion_video_offset"],
            is_ultra_hdr=bool(row["is_ultra_hdr"]),
            created_at=row["created_at"],
            is_reference=bool(row["is_reference"]),
            reference_to=row["reference_to"],
        )

    async def get_thumbnail(
        self, user_id: int, file_id: int, size: str = "small"
    ) -> Optional[bytes]:
        """Generate or return cached thumbnail.

        Sizes: 'small' = 200x200, 'medium' = 600x600.
        Cache path: {storage_root}/.thumbnails/{username}/{file_hash}_{size}.jpg

        Args:
            user_id: The authenticated user's ID.
            file_id: The file record ID.
            size: Thumbnail size ('small' or 'medium').

        Returns:
            JPEG thumbnail bytes, or None if file not found or not an image.
        """
        if size not in self.THUMBNAIL_SIZES:
            size = "small"

        # Get file info (with user isolation)
        file_info = await self.get_file_info(user_id, file_id)
        if file_info is None:
            return None

        # Get username for cache path
        username = await self._get_username(user_id)
        if username is None:
            return None

        # Check cache first
        cache_dir = Path(self._storage_root) / ".thumbnails" / username
        cache_filename = f"{file_info.file_hash}_{size}.jpg"
        cache_path = cache_dir / cache_filename

        if cache_path.exists():
            return cache_path.read_bytes()

        # Resolve actual file path (follow references if needed)
        actual_path = await self._resolve_file_path(file_info)
        if actual_path is None or not Path(actual_path).exists():
            return None

        # Generate thumbnail — use a video frame grab for videos, Pillow for images
        if self._is_video(file_info):
            thumbnail_data = self._generate_video_thumbnail(actual_path, size)
        else:
            thumbnail_data = self._generate_thumbnail(actual_path, size)
        if thumbnail_data is None:
            return None

        # Cache the thumbnail
        cache_dir.mkdir(parents=True, exist_ok=True)
        cache_path.write_bytes(thumbnail_data)

        return thumbnail_data

    async def get_original_stream(
        self, user_id: int, file_id: int
    ) -> Optional[tuple[AsyncGenerator[bytes, None], str, int, str]]:
        """Return streaming info for the original file.

        Args:
            user_id: The authenticated user's ID.
            file_id: The file record ID.

        Returns:
            Tuple of (async generator, filename, file_size, mime_type) or None.
        """
        file_info = await self.get_file_info(user_id, file_id)
        if file_info is None:
            return None

        # Resolve actual file path
        actual_path = await self._resolve_file_path(file_info)
        if actual_path is None or not Path(actual_path).exists():
            return None

        file_size = os.path.getsize(actual_path)
        mime_type = file_info.mime_type or "application/octet-stream"
        file_name = file_info.file_name

        async def file_stream() -> AsyncGenerator[bytes, None]:
            chunk_size = 64 * 1024  # 64KB chunks for streaming
            with open(actual_path, "rb") as f:
                while True:
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    yield chunk

        return file_stream, file_name, file_size, mime_type

    async def resolve_download_target(
        self, user_id: int, file_id: int
    ) -> Optional[tuple[str, str, int, str]]:
        """Resolve the on-disk path and metadata for a file, for direct serving.

        Unlike ``get_original_stream`` this returns the resolved filesystem path
        so the caller can serve it with HTTP Range support (needed for video
        seeking / streaming in the browser).

        Returns:
            Tuple of (actual_path, file_name, file_size, mime_type) or None.
        """
        file_info = await self.get_file_info(user_id, file_id)
        if file_info is None:
            return None

        actual_path = await self._resolve_file_path(file_info)
        if actual_path is None or not Path(actual_path).exists():
            return None

        file_size = os.path.getsize(actual_path)
        mime_type = file_info.mime_type
        if not mime_type:
            import mimetypes

            guessed, _ = mimetypes.guess_type(file_info.file_name)
            mime_type = guessed or "application/octet-stream"

        return actual_path, file_info.file_name, file_size, mime_type

    async def resolve_motion_video(
        self, user_id: int, file_id: int
    ) -> Optional[tuple[str, int, int]]:
        """Resolve the embedded motion-photo video for [file_id].

        Returns:
            Tuple of (actual_path, video_offset, video_size) where video_offset
            is the byte position within the file where the MP4 begins and
            video_size is the number of trailing video bytes. Returns None if
            the file isn't a motion photo or can't be resolved.
        """
        file_info = await self.get_file_info(user_id, file_id)
        if file_info is None or not file_info.is_motion_photo:
            return None

        actual_path = await self._resolve_file_path(file_info)
        if actual_path is None or not Path(actual_path).exists():
            return None

        file_size = os.path.getsize(actual_path)

        offset = file_info.motion_video_offset
        # Fall back to on-the-fly detection if the offset wasn't stored
        # (e.g. records created before motion-photo support / not yet backfilled).
        if offset is None:
            from app.services.motion_photo import detect_motion_photo

            _is_motion, offset = detect_motion_photo(actual_path)
            if offset is None:
                return None

        if offset <= 0 or offset >= file_size:
            return None

        return actual_path, offset, file_size - offset

    # -----------------------------------------------------------------------
    # Private helpers
    # -----------------------------------------------------------------------

    async def _get_username(self, user_id: int) -> Optional[str]:
        """Get username for a user ID."""
        cursor = await self._db.execute(
            "SELECT username FROM users WHERE id = ?", (user_id,)
        )
        row = await cursor.fetchone()
        return row["username"] if row else None

    async def _resolve_file_path(self, file_info: FileDetail) -> Optional[str]:
        """Resolve the actual file path, following references if needed."""
        if file_info.is_reference and file_info.reference_to is not None:
            # Follow the reference to get the actual file
            cursor = await self._db.execute(
                "SELECT file_path FROM file_records WHERE id = ?",
                (file_info.reference_to,),
            )
            row = await cursor.fetchone()
            if row:
                return row["file_path"]
            return None
        return file_info.file_path

    _VIDEO_EXTENSIONS = {
        ".mp4", ".mov", ".mkv", ".webm", ".3gp", ".avi", ".mpeg", ".mpg",
        ".wmv", ".flv", ".m4v", ".ts", ".m2ts", ".mts",
    }

    @classmethod
    def _is_video(cls, file_info: "FileDetail") -> bool:
        """Return True if the file is a video (by media_type or extension)."""
        if (file_info.media_type or "").lower() == "video":
            return True
        if file_info.mime_type and file_info.mime_type.lower().startswith("video/"):
            return True
        return Path(file_info.file_name).suffix.lower() in cls._VIDEO_EXTENSIONS

    def _generate_video_thumbnail(self, file_path: str, size: str) -> Optional[bytes]:
        """Generate a poster-frame thumbnail for a video using ffmpeg.

        Extracts a single frame near the start of the video, then downscales it
        with Pillow to the requested thumbnail size. Requires ffmpeg to be
        available on the PATH; if it is not, returns None so the client can fall
        back to a generic video placeholder.

        Args:
            file_path: Path to the source video file.
            size: Thumbnail size key ('small' or 'medium').

        Returns:
            JPEG bytes of the thumbnail, or None if generation fails.
        """
        import io
        import shutil as _shutil
        import subprocess
        import tempfile

        ffmpeg = _shutil.which("ffmpeg")
        if ffmpeg is None:
            logger.warning("ffmpeg not found; cannot generate video thumbnail for %s", file_path)
            return None

        target_size = self.THUMBNAIL_SIZES[size]
        tmp_frame = None
        try:
            from PIL import Image

            with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tf:
                tmp_frame = tf.name

            # Seek 1s in and grab a single frame. -y overwrites the temp file.
            cmd = [
                ffmpeg, "-y", "-loglevel", "error",
                "-ss", "00:00:01",
                "-i", file_path,
                "-frames:v", "1",
                tmp_frame,
            ]
            result = subprocess.run(
                cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30
            )
            # If seeking past the end failed (very short clip), retry from the start.
            if result.returncode != 0 or not os.path.getsize(tmp_frame):
                cmd_retry = [
                    ffmpeg, "-y", "-loglevel", "error",
                    "-i", file_path,
                    "-frames:v", "1",
                    tmp_frame,
                ]
                subprocess.run(
                    cmd_retry, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30
                )

            if not os.path.exists(tmp_frame) or os.path.getsize(tmp_frame) == 0:
                return None

            with Image.open(tmp_frame) as img:
                if img.mode not in ("RGB", "L"):
                    img = img.convert("RGB")
                img.thumbnail(target_size, Image.LANCZOS)
                buffer = io.BytesIO()
                img.save(buffer, format="JPEG", quality=85)
                return buffer.getvalue()
        except Exception as e:
            logger.warning("Failed to generate video thumbnail for %s: %s", file_path, e)
            return None
        finally:
            if tmp_frame and os.path.exists(tmp_frame):
                try:
                    os.unlink(tmp_frame)
                except OSError:
                    pass

    def _generate_thumbnail(self, file_path: str, size: str) -> Optional[bytes]:
        """Generate a thumbnail for the given file.

        Uses Pillow to resize images. Supports JPEG, PNG, WebP, GIF, BMP, TIFF.

        Args:
            file_path: Path to the source image file.
            size: Thumbnail size key ('small' or 'medium').

        Returns:
            JPEG bytes of the thumbnail, or None if generation fails.
        """
        try:
            from PIL import Image
            import io

            target_size = self.THUMBNAIL_SIZES[size]

            with Image.open(file_path) as img:
                # Convert to RGB if necessary (e.g., RGBA, P mode)
                if img.mode not in ("RGB", "L"):
                    img = img.convert("RGB")

                # Use thumbnail() which preserves aspect ratio
                img.thumbnail(target_size, Image.LANCZOS)

                # Save as JPEG
                buffer = io.BytesIO()
                img.save(buffer, format="JPEG", quality=85)
                return buffer.getvalue()

        except Exception as e:
            logger.warning("Failed to generate thumbnail for %s: %s", file_path, e)
            return None

    @staticmethod
    def _get_sort_clause(sort_by: str) -> str:
        """Get SQL ORDER BY clause for the given sort option."""
        if sort_by == "size":
            return "file_size DESC"
        elif sort_by == "time":
            return "COALESCE(exif_time, created_at) DESC"
        else:
            return "file_name ASC"

    async def delete_file(self, user_id: int, file_id: int) -> tuple[bool, str]:
        """Soft-delete a single file record (move to trash).

        Sets deleted_at and deleted_batch_id. Physical file is moved to .trash directory.
        Reference counting and physical deletion happen in purge_file.

        Args:
            user_id: The authenticated user's ID.
            file_id: The file record ID to delete.

        Returns:
            Tuple of (success: bool, message: str).
        """
        file_info = await self.get_file_info(user_id, file_id)
        if file_info is None:
            return False, "File not found"

        if file_info.is_reference:
            batch_id = str(uuid.uuid4())
            now = datetime.now(timezone.utc).isoformat()
            await self._db.execute(
                "UPDATE file_records SET deleted_at = ?, deleted_batch_id = ? WHERE id = ? AND user_id = ?",
                (now, batch_id, file_id, user_id),
            )
            await self._db.commit()
            logger.info("Soft-deleted reference %d for user %d (batch=%s)", file_id, user_id, batch_id)
            return True, "Reference moved to trash"

        username = await self._get_username(user_id)
        if username is None:
            return False, "User not found"

        lock_acquired = self._acquire_lock(username, file_info.file_hash)
        if not lock_acquired:
            return False, "File is currently being processed"

        try:
            new_path = self._move_to_trash(username, file_info.device_name, file_info.file_path)

            batch_id = str(uuid.uuid4())
            now = datetime.now(timezone.utc).isoformat()

            if new_path:
                await self._db.execute(
                    "UPDATE file_records SET deleted_at = ?, deleted_batch_id = ?, file_path = ? WHERE id = ? AND user_id = ?",
                    (now, batch_id, new_path, file_id, user_id),
                )
            else:
                await self._db.execute(
                    "UPDATE file_records SET deleted_at = ?, deleted_batch_id = ? WHERE id = ? AND user_id = ?",
                    (now, batch_id, file_id, user_id),
                )
            await self._db.commit()
            logger.info("Soft-deleted file %d for user %d (batch=%s)", file_id, user_id, batch_id)
            return True, "File moved to trash"
        finally:
            self._release_lock(username, file_info.file_hash)

    async def delete_directory(self, user_id: int, path: str) -> tuple[int, str]:
        """Soft-delete all files in a directory path (move to trash).

        All records share the same deleted_batch_id for batch restore.
        Physical files are moved to .trash directory.

        Args:
            user_id: The authenticated user's ID.
            path: The virtual directory path to delete (relative to user root).

        Returns:
            Tuple of (deleted_count: int, message: str).
        """
        path = path.strip("/")

        username = await self._get_username(user_id)
        if username is None:
            return 0, "User not found"

        base_prefix = f"{self._storage_root}/{username}/"
        if path:
            search_prefix = f"{base_prefix}{path}/"
        else:
            search_prefix = base_prefix

        cursor = await self._db.execute(
            """SELECT id, file_hash, file_path, device_name, is_reference FROM file_records
               WHERE user_id = ? AND file_path LIKE ?
               AND deleted_at IS NULL AND purged_at IS NULL""",
            (user_id, f"{search_prefix}%"),
        )
        rows = await cursor.fetchall()

        if not rows:
            return 0, "Directory not found or empty"

        record_ids = []
        path_updates = []

        for row in rows:
            record_ids.append(row["id"])
            if not row["is_reference"]:
                file_hash = row["file_hash"]
                lock_acquired = self._acquire_lock(username, file_hash)
                if lock_acquired:
                    try:
                        new_path = self._move_to_trash(username, row["device_name"], row["file_path"])
                        if new_path:
                            path_updates.append((new_path, row["id"]))
                    finally:
                        self._release_lock(username, file_hash)

        batch_id = str(uuid.uuid4())
        now = datetime.now(timezone.utc).isoformat()

        placeholders = ",".join("?" * len(record_ids))
        await self._db.execute(
            f"UPDATE file_records SET deleted_at = ?, deleted_batch_id = ? WHERE id IN ({placeholders}) AND user_id = ?",
            [now, batch_id] + record_ids + [user_id],
        )

        for new_path, record_id in path_updates:
            await self._db.execute(
                "UPDATE file_records SET file_path = ? WHERE id = ?",
                (new_path, record_id),
            )

        await self._db.commit()

        count = len(record_ids)
        logger.info("Soft-deleted %d files in directory '%s' for user %d (batch=%s)", count, path, user_id, batch_id)
        return count, f"Moved {count} file(s) to trash"

    def _cleanup_empty_directories(self, file_path: str) -> None:
        """Remove empty parent directories up to storage root."""
        try:
            path = Path(file_path)
            parent = path.parent

            # Walk up the directory tree and remove empty dirs
            while parent.exists() and parent != parent.parent:
                try:
                    # Only remove if directory is empty
                    if parent.is_dir() and not any(parent.iterdir()):
                        parent.rmdir()
                        logger.debug("Removed empty directory: %s", parent)
                    else:
                        # Stop if not empty
                        break
                except Exception:
                    break
                parent = parent.parent
        except Exception as e:
            logger.debug("Error during directory cleanup: %s", e)

    # -----------------------------------------------------------------------
    # Trash (recycle bin) methods
    # -----------------------------------------------------------------------

    async def list_trash(
        self, user_id: int, page: int = 1, page_size: int = 50
    ) -> tuple[list[TrashItemInfo], int]:
        """List files in trash for a user.

        Args:
            user_id: The authenticated user's ID.
            page: Page number (1-based).
            page_size: Number of items per page.

        Returns:
            Tuple of (trash_items, total_count).
        """
        offset = (page - 1) * page_size

        username = await self._get_username(user_id)

        cursor = await self._db.execute(
            """SELECT COUNT(*) as cnt FROM file_records
               WHERE user_id = ? AND deleted_at IS NOT NULL AND purged_at IS NULL""",
            (user_id,),
        )
        count_row = await cursor.fetchone()
        total = count_row["cnt"] if count_row else 0

        cursor = await self._db.execute(
            """SELECT id, file_name, file_size, file_path, original_path, device_name,
                      mime_type, media_type, exif_time, created_at,
                      deleted_at, deleted_batch_id, is_reference, reference_to
               FROM file_records
               WHERE user_id = ? AND deleted_at IS NOT NULL AND purged_at IS NULL
               ORDER BY deleted_at DESC
               LIMIT ? OFFSET ?""",
            (user_id, page_size, offset),
        )
        rows = await cursor.fetchall()

        retention = timedelta(days=self._trash_retention_days)
        items = []
        for r in rows:
            expires_at = None
            deleted_at = r["deleted_at"]
            if deleted_at:
                try:
                    if isinstance(deleted_at, str):
                        deleted = datetime.fromisoformat(deleted_at)
                    else:
                        deleted = deleted_at
                    if deleted.tzinfo is None:
                        deleted = deleted.replace(tzinfo=timezone.utc)
                    expires_at = (deleted + retention).isoformat()
                except (ValueError, TypeError) as e:
                    logger.debug("Failed to parse deleted_at %s: %s", deleted_at, e)
            
            items.append(TrashItemInfo(
                id=r["id"],
                file_name=r["file_name"],
                file_size=r["file_size"],
                file_path=r["file_path"],
                original_path=r["original_path"],
                display_path=self._get_display_path(r["file_path"], username),
                device_name=r["device_name"],
                mime_type=r["mime_type"],
                media_type=r["media_type"] or "image",
                exif_time=r["exif_time"],
                created_at=r["created_at"],
                deleted_at=r["deleted_at"],
                deleted_batch_id=r["deleted_batch_id"],
                expires_at=expires_at,
                is_reference=bool(r["is_reference"]),
                reference_to=r["reference_to"],
            ))
        return items, total

    def _get_display_path(self, file_path: str, username: str) -> str:
        """Generate user-readable display path from server file path.

        Extracts format: /device_name/relative/path/original_filename.ext

        Args:
            file_path: Full server file path.
            username: Current user's username.

        Returns:
            User-readable path like "/25010PN30C/Pictures/ithome/photo.jpg"
        """
        if not username:
            return file_path

        prefix = f"{self._storage_root}/{username}/"
        trash_prefix = f"{self._storage_root}/{username}/.trash/"

        if file_path.startswith(trash_prefix):
            relative_path = file_path[len(trash_prefix):]
            parts = relative_path.split("/")
            if len(parts) >= 2:
                device_name = parts[0]
                rest = "/".join(parts[1:-1])
                if rest:
                    return f"/{device_name}/{rest}/{parts[-1]}"
                else:
                    return f"/{device_name}/{parts[-1]}"
            elif len(parts) == 1:
                return f"/{device_name}/{parts[0]}"
            return relative_path

        if file_path.startswith(prefix):
            relative_path = file_path[len(prefix):]
            parts = relative_path.split("/")
            if len(parts) >= 2:
                device_name = parts[0]
                rest = "/".join(parts[1:-1])
                if rest:
                    return f"/{device_name}/{rest}/{parts[-1]}"
                else:
                    return f"/{device_name}/{parts[-1]}"
            elif len(parts) == 1:
                return f"/{device_name}/{parts[0]}"

        return file_path

    async def restore_file(self, user_id: int, file_id: int) -> tuple[bool, str]:
        """Restore a single file from trash.

        Moves physical file back from .trash to original location.

        Args:
            user_id: The authenticated user's ID.
            file_id: The file record ID to restore.

        Returns:
            Tuple of (success, message).
        """
        cursor = await self._db.execute(
            """SELECT id, file_hash, file_path, device_name, is_reference, deleted_at
               FROM file_records WHERE id = ? AND user_id = ?""",
            (file_id, user_id),
        )
        row = await cursor.fetchone()
        if row is None:
            return False, "File not found"
        if row["deleted_at"] is None:
            return False, "File not in trash"

        if not row["is_reference"]:
            username = await self._get_username(user_id)
            if username is None:
                return False, "User not found"

            lock_acquired = self._acquire_lock(username, row["file_hash"])
            if not lock_acquired:
                return False, "File is currently being processed"

            try:
                new_path = self._restore_from_trash(username, row["device_name"], row["file_path"])
                if new_path:
                    await self._db.execute(
                        "UPDATE file_records SET file_path = ? WHERE id = ? AND user_id = ?",
                        (new_path, file_id, user_id),
                    )
            finally:
                self._release_lock(username, row["file_hash"])

        await self._db.execute(
            "UPDATE file_records SET deleted_at = NULL, deleted_batch_id = NULL WHERE id = ? AND user_id = ?",
            (file_id, user_id),
        )
        await self._db.commit()
        logger.info("Restored file %d for user %d", file_id, user_id)
        return True, "File restored"

    async def restore_batch(self, user_id: int, batch_id: str) -> tuple[int, str]:
        """Restore all files in a batch from trash.

        Moves physical files back from .trash to original locations.

        Args:
            user_id: The authenticated user's ID.
            batch_id: The batch ID shared by files deleted together.

        Returns:
            Tuple of (restored_count, message).
        """
        username = await self._get_username(user_id)
        if username is None:
            return 0, "User not found"

        cursor = await self._db.execute(
            """SELECT id, file_hash, file_path, device_name, is_reference
               FROM file_records
               WHERE user_id = ? AND deleted_batch_id = ? AND deleted_at IS NOT NULL""",
            (user_id, batch_id),
        )
        rows = await cursor.fetchall()

        path_updates = []
        for row in rows:
            if not row["is_reference"]:
                lock_acquired = self._acquire_lock(username, row["file_hash"])
                if lock_acquired:
                    try:
                        new_path = self._restore_from_trash(username, row["device_name"], row["file_path"])
                        if new_path:
                            path_updates.append((new_path, row["id"]))
                    finally:
                        self._release_lock(username, row["file_hash"])

        for new_path, record_id in path_updates:
            await self._db.execute(
                "UPDATE file_records SET file_path = ? WHERE id = ?",
                (new_path, record_id),
            )

        cursor = await self._db.execute(
            """UPDATE file_records SET deleted_at = NULL, deleted_batch_id = NULL
               WHERE user_id = ? AND deleted_batch_id = ? AND deleted_at IS NOT NULL""",
            (user_id, batch_id),
        )
        count = cursor.rowcount
        await self._db.commit()
        logger.info("Restored %d files (batch=%s) for user %d", count, batch_id, user_id)
        return count, f"Restored {count} file(s)"

    async def purge_file(self, user_id: int, file_id: int) -> tuple[bool, str]:
        """Permanently delete a file from trash (mark as purged).

        Sets purged_at and deletes the physical file from .trash. Record is kept.
        Handles deduplication references via ownership promotion.

        Args:
            user_id: The authenticated user's ID.
            file_id: The file record ID to purge.

        Returns:
            Tuple of (success, message).
        """
        cursor = await self._db.execute(
            """SELECT id, file_hash, file_path, is_reference, reference_to, deleted_at, purged_at, device_name
               FROM file_records WHERE id = ? AND user_id = ?""",
            (file_id, user_id),
        )
        row = await cursor.fetchone()
        if row is None:
            return False, "File not found"
        if row["deleted_at"] is None:
            return False, "File not in trash"
        if row["purged_at"] is not None:
            return False, "File already purged"

        now = datetime.now(timezone.utc).isoformat()

        if row["is_reference"]:
            # Reference doesn't own physical file — just mark purged
            await self._db.execute(
                "UPDATE file_records SET purged_at = ? WHERE id = ? AND user_id = ?",
                (now, file_id, user_id),
            )
            await self._db.commit()
            logger.info("Purged reference record %d for user %d", file_id, user_id)
            return True, "Reference permanently deleted"

        # Original file — check for surviving references
        cursor = await self._db.execute(
            """SELECT id, file_path FROM file_records
               WHERE user_id = ? AND reference_to = ? AND id != ?
               AND purged_at IS NULL""",
            (user_id, file_id, file_id),
        )
        refs = await cursor.fetchall()

        physical_path = row["file_path"]
        file_hash = row["file_hash"]
        username = await self._get_username(user_id)

        if username:
            lock_acquired = self._acquire_lock(username, file_hash)
        else:
            lock_acquired = False

        try:
            if not refs:
                # No surviving references — delete physical file and mark purged
                await self._db.execute(
                    "UPDATE file_records SET purged_at = ? WHERE id = ? AND user_id = ?",
                    (now, file_id, user_id),
                )
                await self._db.commit()

                self._delete_from_trash(physical_path)
                return True, "File permanently deleted"

            # Has surviving references — promote first active reference to original
            active_refs = [r for r in refs if True]
            ref_row = active_refs[0]
            ref_id = ref_row["id"]
            ref_file_path = ref_row["file_path"]

            # Move physical file from trash to the reference's path
            try:
                src = Path(physical_path)
                dst = Path(ref_file_path)
                if src.exists():
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(src), str(dst))
                    logger.info("Moved physical file %s -> %s (promotion)", physical_path, ref_file_path)
                else:
                    logger.warning("Source file not found during promotion: %s", physical_path)
            except Exception as e:
                logger.error("Failed to transfer file ownership: %s", e)
                return False, f"Failed to transfer file ownership: {e}"

            # Promote the reference to original
            await self._db.execute(
                "UPDATE file_records SET is_reference = 0, reference_to = NULL WHERE id = ?",
                (ref_id,),
            )

            # Repoint other references to the promoted record
            other_ref_ids = [r["id"] for r in active_refs[1:]]
            if other_ref_ids:
                placeholders = ",".join("?" * len(other_ref_ids))
                await self._db.execute(
                    f"UPDATE file_records SET reference_to = ? WHERE id IN ({placeholders})",
                    [ref_id] + other_ref_ids,
                )

            # Mark original as purged
            await self._db.execute(
                "UPDATE file_records SET purged_at = ? WHERE id = ? AND user_id = ?",
                (now, file_id, user_id),
            )
            await self._db.commit()
            logger.info("Purged original %d, promoted ref %d for user %d", file_id, ref_id, user_id)
            return True, "File permanently deleted (ownership transferred)"
        finally:
            if username and lock_acquired:
                self._release_lock(username, file_hash)

    async def purge_all(self, user_id: int) -> tuple[int, str]:
        """Permanently delete all files in trash for a user.

        Processes references first, then originals, to minimize promotion.

        Args:
            user_id: The authenticated user's ID.

        Returns:
            Tuple of (purged_count, message).
        """
        cursor = await self._db.execute(
            """SELECT id, is_reference FROM file_records
               WHERE user_id = ? AND deleted_at IS NOT NULL AND purged_at IS NULL
               ORDER BY is_reference DESC""",
            (user_id,),
        )
        rows = await cursor.fetchall()

        count = 0
        for row in rows:
            success, _ = await self.purge_file(user_id, row["id"])
            if success:
                count += 1

        logger.info("Purged %d files from trash for user %d", count, user_id)
        return count, f"Purged {count} file(s)"

    async def auto_purge_expired(self, expiry_days: int = 30) -> int:
        """Auto-purge trash items older than expiry_days.

        Called by background task. Processes per-user to reuse purge_file logic.

        Args:
            expiry_days: Number of days after which trashed files are purged.

        Returns:
            Total number of files purged.
        """
        cutoff = (datetime.now(timezone.utc) - timedelta(days=expiry_days)).isoformat()

        cursor = await self._db.execute(
            "SELECT DISTINCT user_id FROM file_records WHERE deleted_at IS NOT NULL AND purged_at IS NULL AND deleted_at < ?",
            (cutoff,),
        )
        user_rows = await cursor.fetchall()

        total = 0
        for user_row in user_rows:
            uid = user_row["user_id"]
            cursor = await self._db.execute(
                """SELECT id FROM file_records
                   WHERE user_id = ? AND deleted_at IS NOT NULL AND purged_at IS NULL
                   AND deleted_at < ? ORDER BY is_reference DESC""",
                (uid, cutoff),
            )
            file_rows = await cursor.fetchall()
            for file_row in file_rows:
                success, _ = await self.purge_file(uid, file_row["id"])
                if success:
                    total += 1

        if total > 0:
            logger.info("Auto-purged %d expired trash items", total)
        return total

    async def get_status_changes(self, user_id: int) -> list[StatusChangeInfo]:
        """Get all non-active file statuses for client sync.

        Returns trashed and purged files with their hashes and timestamps.

        Args:
            user_id: The authenticated user's ID.

        Returns:
            List of StatusChangeInfo for all trashed/purged files.
        """
        cursor = await self._db.execute(
            """SELECT file_hash, deleted_at, purged_at
               FROM file_records
               WHERE user_id = ? AND (deleted_at IS NOT NULL OR purged_at IS NOT NULL)""",
            (user_id,),
        )
        rows = await cursor.fetchall()

        result = []
        retention = timedelta(days=self._trash_retention_days)
        for r in rows:
            if r["purged_at"] is not None:
                result.append(StatusChangeInfo(
                    file_hash=r["file_hash"],
                    status="purged",
                    deleted_at=r["deleted_at"],
                    purged_at=r["purged_at"],
                ))
            elif r["deleted_at"] is not None:
                # Calculate expiry
                try:
                    deleted = datetime.fromisoformat(r["deleted_at"])
                    if deleted.tzinfo is None:
                        deleted = deleted.replace(tzinfo=timezone.utc)
                    expires = deleted + retention
                    expires_str = expires.isoformat()
                except (ValueError, TypeError):
                    expires_str = None
                result.append(StatusChangeInfo(
                    file_hash=r["file_hash"],
                    status="trashed",
                    deleted_at=r["deleted_at"],
                    expires_at=expires_str,
                ))
        return result

    async def reactivate_record(self, user_id: int, file_hash: str) -> bool:
        """Reactivate a trashed/purged record (called on re-upload).

        Clears deleted_at, deleted_batch_id, purged_at to restore active status.

        Args:
            user_id: The authenticated user's ID.
            file_hash: The SHA-256 hash of the file being re-uploaded.

        Returns:
            True if a record was reactivated, False if no trashed/purged record found.
        """
        cursor = await self._db.execute(
            """SELECT id FROM file_records
               WHERE user_id = ? AND file_hash = ?
               AND (deleted_at IS NOT NULL OR purged_at IS NOT NULL)
               ORDER BY (purged_at IS NULL), (deleted_at IS NULL), purged_at DESC, deleted_at DESC
               LIMIT 1""",
            (user_id, file_hash),
        )
        row = await cursor.fetchone()
        if row is None:
            return False

        await self._db.execute(
            "UPDATE file_records SET deleted_at = NULL, deleted_batch_id = NULL, purged_at = NULL WHERE id = ?",
            (row["id"],),
        )
        await self._db.commit()
        logger.info("Reactivated record %d for file_hash %s (user %d)", row["id"], file_hash[:12], user_id)
        return True
