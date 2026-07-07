"""Deduplication service.

Detects duplicate files using SHA-256 hash comparison.
Creates file references (symlinks) when same content exists at different paths.

Provides:
- ``check`` — Check if a file hash already exists for a user.
- ``register_file`` — Register a new file record in the database.
- ``create_reference`` — Create a reference record for a duplicate file at a different path.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Optional

import aiosqlite

logger = logging.getLogger("photovault.dedup")


@dataclass
class FileRecord:
    """Represents a file record from the database."""

    id: int
    user_id: int
    file_hash: str
    file_path: str
    original_path: str
    device_name: str
    file_size: int
    file_name: str
    mime_type: Optional[str] = None
    exif_time: Optional[str] = None
    focal_length: Optional[float] = None
    is_reference: bool = False
    reference_to: Optional[int] = None
    live_photo_group_id: Optional[str] = None
    live_photo_type: Optional[str] = None
    media_type: str = "image"
    is_motion_photo: bool = False
    motion_video_offset: Optional[int] = None
    is_ultra_hdr: bool = False
    created_at: Optional[str] = None


class DeduplicationService:
    """Service for detecting and managing duplicate files.

    Uses SHA-256 file hashes to detect duplicates within a user's records.
    Ensures user isolation — only checks within the current user's records.
    """

    def __init__(self, db: aiosqlite.Connection):
        """Initialize with a database connection.

        Args:
            db: An aiosqlite connection with row_factory set to aiosqlite.Row.
        """
        self._db = db

    async def check(self, user_id: int, file_hash: str) -> Optional[FileRecord]:
        """Check if a file with this hash already exists for this user.

        Only checks within the current user's records (user isolation).

        Args:
            user_id: The ID of the current user.
            file_hash: The SHA-256 hash of the file to check.

        Returns:
            The existing FileRecord if found, None otherwise.
        """
        cursor = await self._db.execute(
            "SELECT * FROM file_records WHERE user_id = ? AND file_hash = ? AND deleted_at IS NULL AND purged_at IS NULL LIMIT 1",
            (user_id, file_hash),
        )
        row = await cursor.fetchone()
        if row is None:
            return None

        return self._row_to_record(row)

    async def register_file(
        self,
        user_id: int,
        file_hash: str,
        file_path: str,
        original_path: str,
        device_name: str,
        file_size: int,
        file_name: str,
        mime_type: Optional[str] = None,
        exif_time: Optional[str] = None,
        focal_length: Optional[float] = None,
        media_type: str = "image",
        is_motion_photo: bool = False,
        motion_video_offset: Optional[int] = None,
        is_ultra_hdr: bool = False,
    ) -> FileRecord:
        """Register a new file record in the database.

        Args:
            user_id: The ID of the file owner.
            file_hash: The SHA-256 hash of the file.
            file_path: The storage path on the NAS.
            original_path: The original path on the phone.
            device_name: The device name.
            file_size: The file size in bytes.
            file_name: The file name.
            mime_type: Optional MIME type.
            exif_time: Optional EXIF capture time.
            focal_length: Optional focal length in mm (35mm-equivalent preferred).
            media_type: 'image' or 'video'. Defaults to 'image'.
            is_motion_photo: True if the image embeds a motion-photo video.
            motion_video_offset: Byte offset where the embedded video begins.

        Returns:
            The newly created FileRecord.
        """
        cursor = await self._db.execute(
            """INSERT INTO file_records
               (user_id, file_hash, file_path, original_path, device_name,
                file_size, file_name, mime_type, exif_time, focal_length,
                media_type, is_motion_photo, motion_video_offset, is_ultra_hdr,
                is_reference, reference_to)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, NULL)""",
            (
                user_id,
                file_hash,
                file_path,
                original_path,
                device_name,
                file_size,
                file_name,
                mime_type,
                exif_time,
                focal_length,
                media_type,
                is_motion_photo,
                motion_video_offset,
                is_ultra_hdr,
            ),
        )
        await self._db.commit()

        record_id = cursor.lastrowid

        # Fetch the full record to return
        cursor = await self._db.execute(
            "SELECT * FROM file_records WHERE id = ?", (record_id,)
        )
        row = await cursor.fetchone()
        return self._row_to_record(row)

    async def register_or_reactivate_file(
        self,
        user_id: int,
        file_hash: str,
        file_path: str,
        original_path: str,
        device_name: str,
        file_size: int,
        file_name: str,
        mime_type: Optional[str] = None,
        exif_time: Optional[str] = None,
        focal_length: Optional[float] = None,
        media_type: str = "image",
        is_motion_photo: bool = False,
        motion_video_offset: Optional[int] = None,
        is_ultra_hdr: bool = False,
    ) -> FileRecord:
        """Register a file, reactivating an existing trashed/purged record if present.

        Used by the re-backup flow: when the user re-uploads a file that is
        currently trashed or purged on the server, we must restore the SAME
        record (clearing ``deleted_at`` / ``deleted_batch_id`` / ``purged_at``)
        and refresh its metadata/path — NOT insert a duplicate record that would
        leave the stale trashed/purged row behind.

        If no existing record is found (normal first-time upload), this falls
        back to :meth:`register_file`.

        Note: :meth:`check` already filters out deleted rows and short-circuits
        active duplicates during ``init_upload``, so by the time completion runs
        any existing same-hash record is trashed/purged (or absent).

        Returns:
            The reactivated (or newly created) FileRecord.
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
            # No trashed/purged record to restore — insert a fresh record.
            return await self.register_file(
                user_id=user_id,
                file_hash=file_hash,
                file_path=file_path,
                original_path=original_path,
                device_name=device_name,
                file_size=file_size,
                file_name=file_name,
                mime_type=mime_type,
                exif_time=exif_time,
                focal_length=focal_length,
                media_type=media_type,
                is_motion_photo=is_motion_photo,
                motion_video_offset=motion_video_offset,
                is_ultra_hdr=is_ultra_hdr,
            )

        record_id = row["id"]
        # Reactivate: clear deletion markers and refresh the stored metadata/path.
        await self._db.execute(
            """UPDATE file_records
               SET deleted_at = NULL,
                   deleted_batch_id = NULL,
                   purged_at = NULL,
                   file_path = ?,
                   original_path = ?,
                   device_name = ?,
                   file_size = ?,
                   file_name = ?,
                   mime_type = ?,
                   exif_time = ?,
                   focal_length = ?,
                   media_type = ?,
                   is_motion_photo = ?,
                   motion_video_offset = ?,
                   is_ultra_hdr = ?,
                   is_reference = FALSE,
                   reference_to = NULL
               WHERE id = ?""",
            (
                file_path,
                original_path,
                device_name,
                file_size,
                file_name,
                mime_type,
                exif_time,
                focal_length,
                media_type,
                is_motion_photo,
                motion_video_offset,
                is_ultra_hdr,
                record_id,
            ),
        )
        await self._db.commit()
        logger.info(
            "Reactivated record %d for file_hash %s (user %d) on re-backup",
            record_id,
            file_hash[:12],
            user_id,
        )

        cursor = await self._db.execute(
            "SELECT * FROM file_records WHERE id = ?", (record_id,)
        )
        return self._row_to_record(await cursor.fetchone())

    async def create_reference(
        self,
        user_id: int,
        file_hash: str,
        target_path: str,
        source_record_id: int,
        original_path: str,
        device_name: str,
        file_size: int,
        file_name: str,
    ) -> FileRecord:
        """Create a reference to an existing file when same hash exists but at a different path.

        Sets is_reference=True and reference_to=source_record_id.

        Args:
            user_id: The ID of the file owner.
            file_hash: The SHA-256 hash of the file.
            target_path: The target storage path for the reference.
            source_record_id: The ID of the original file record being referenced.
            original_path: The original path on the phone.
            device_name: The device name.
            file_size: The file size in bytes.
            file_name: The file name.

        Returns:
            The newly created reference FileRecord.
        """
        cursor = await self._db.execute(
            """INSERT INTO file_records
               (user_id, file_hash, file_path, original_path, device_name,
                file_size, file_name, is_reference, reference_to)
               VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?)""",
            (
                user_id,
                file_hash,
                target_path,
                original_path,
                device_name,
                file_size,
                file_name,
                source_record_id,
            ),
        )
        await self._db.commit()

        record_id = cursor.lastrowid

        # Fetch the full record to return
        cursor = await self._db.execute(
            "SELECT * FROM file_records WHERE id = ?", (record_id,)
        )
        row = await cursor.fetchone()
        return self._row_to_record(row)

    @staticmethod
    def _row_to_record(row: aiosqlite.Row) -> FileRecord:
        """Convert a database row to a FileRecord dataclass."""
        return FileRecord(
            id=row["id"],
            user_id=row["user_id"],
            file_hash=row["file_hash"],
            file_path=row["file_path"],
            original_path=row["original_path"],
            device_name=row["device_name"],
            file_size=row["file_size"],
            file_name=row["file_name"],
            mime_type=row["mime_type"],
            exif_time=row["exif_time"],
            focal_length=row["focal_length"],
            is_reference=bool(row["is_reference"]),
            reference_to=row["reference_to"],
            live_photo_group_id=row["live_photo_group_id"],
            live_photo_type=row["live_photo_type"],
            media_type=row["media_type"] or "image",
            is_motion_photo=bool(row["is_motion_photo"]) if "is_motion_photo" in row.keys() else False,
            motion_video_offset=(
                row["motion_video_offset"] if "motion_video_offset" in row.keys() else None
            ),
            is_ultra_hdr=bool(row["is_ultra_hdr"]) if "is_ultra_hdr" in row.keys() else False,
            created_at=row["created_at"],
        )
