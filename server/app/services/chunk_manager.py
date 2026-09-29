"""Chunk manager.

Manages chunked file uploads with resume capability:
- Session creation and expiration (7 days)
- Chunk storage and verification
- Chunk merging and integrity validation
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import math
import shutil
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import List, Optional

import aiosqlite

from app.core.config import get_settings


class ChunkChecksumError(Exception):
    """Raised when a chunk's MD5 checksum does not match."""

    def __init__(self, chunk_index: int, expected: str, actual: str):
        self.chunk_index = chunk_index
        self.expected = expected
        self.actual = actual
        super().__init__(
            f"Chunk {chunk_index} checksum mismatch: expected {expected}, got {actual}"
        )


class SessionNotFoundError(Exception):
    """Raised when a session ID does not exist."""

    def __init__(self, session_id: str):
        self.session_id = session_id
        super().__init__(f"Upload session not found: {session_id}")


class InvalidChunkIndexError(Exception):
    """Raised when a chunk index is outside its session's valid range."""

    def __init__(self, chunk_index: int, total_chunks: int):
        self.chunk_index = chunk_index
        self.total_chunks = total_chunks
        super().__init__(
            f"Chunk index {chunk_index} is outside valid range "
            f"0..{max(total_chunks - 1, 0)}"
        )


class ChunkManager:
    """Manages chunked file uploads with resume capability.

    Attributes:
        CHUNK_SIZE: Default chunk size in bytes (2 MB).
    """

    CHUNK_SIZE = 2 * 1024 * 1024  # 2MB

    def __init__(
        self,
        db: aiosqlite.Connection,
        media_root: str,
        chunk_size: Optional[int] = None,
        session_expire_days: Optional[int] = None,
    ):
        """Initialize ChunkManager using configured upload limits by default."""
        settings = get_settings()
        self._db = db
        self._media_root = media_root
        self.chunk_size = chunk_size or settings.chunk_size_bytes
        self.session_expire_days = (
            session_expire_days
            if session_expire_days is not None
            else settings.session_expire_days
        )

    @property
    def _chunks_base_dir(self) -> Path:
        """Base directory for all chunk temporary storage."""
        return Path(self._media_root) / ".chunks"

    def _session_chunk_dir(self, session_id: str) -> Path:
        """Directory for a specific session's chunks."""
        return self._chunks_base_dir / session_id

    @staticmethod
    def _chunk_filename(index: int) -> str:
        """Generate zero-padded chunk filename."""
        return f"chunk_{index:06d}"

    async def create_session(
        self,
        user_id: int,
        file_hash: str,
        file_name: str,
        file_size: int,
        target_path: str,
        device_name: str,
        original_path: str,
        exif_time: Optional[datetime] = None,
        mime_type: Optional[str] = None,
    ) -> str:
        """Create an upload session.

        Writes a new record to the upload_sessions table using the configured
        session expiry.

        Args:
            user_id: ID of the authenticated user.
            file_hash: SHA-256 hash of the complete file.
            file_name: Original file name.
            file_size: Total file size in bytes.
            target_path: Resolved storage path on the NAS.
            device_name: Name of the source device.
            original_path: Original file path on the device.
            exif_time: Optional EXIF capture time, persisted so it can be
                carried over to the file_records table on completion.

        Returns:
            The generated session ID (UUID string).
        """
        session_id = str(uuid.uuid4())
        total_chunks = math.ceil(file_size / self.chunk_size)
        now = datetime.now(timezone.utc)
        expires_at = now + timedelta(days=self.session_expire_days)

        await self._db.execute(
            """
            INSERT INTO upload_sessions
                (id, user_id, file_hash, file_name, file_size, total_chunks,
                 received_chunks, target_path, device_name, original_path,
                 exif_time, mime_type, status, created_at, updated_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                session_id,
                user_id,
                file_hash,
                file_name,
                file_size,
                total_chunks,
                "[]",
                target_path,
                device_name,
                original_path,
                exif_time.isoformat() if exif_time else None,
                mime_type,
                "active",
                now.isoformat(),
                now.isoformat(),
                expires_at.isoformat(),
            ),
        )
        await self._db.commit()

        # Create the chunk directory without blocking the event loop.
        chunk_dir = self._session_chunk_dir(session_id)
        await asyncio.to_thread(chunk_dir.mkdir, parents=True, exist_ok=True)

        return session_id

    async def store_chunk(
        self,
        session_id: str,
        chunk_index: int,
        data: bytes,
        md5_checksum: str,
        user_id: Optional[int] = None,
    ) -> bool:
        """Validate, persist and atomically record one upload chunk.

        ``user_id`` scopes API writes to the owning user. Progress is updated by
        one SQLite ``UPDATE`` expression, so concurrent requests on different
        connections merge their indices instead of overwriting a stale JSON list.
        """
        session = await self.get_session(
            session_id, user_id=user_id, require_active=True
        )
        if session is None:
            raise SessionNotFoundError(session_id)

        total_chunks = int(session["total_chunks"])
        if chunk_index < 0 or chunk_index >= total_chunks:
            raise InvalidChunkIndexError(chunk_index, total_chunks)

        actual_md5 = await asyncio.to_thread(lambda: hashlib.md5(data).hexdigest())
        if actual_md5 != md5_checksum:
            raise ChunkChecksumError(chunk_index, md5_checksum, actual_md5)

        chunk_dir = self._session_chunk_dir(session_id)
        await asyncio.to_thread(chunk_dir.mkdir, parents=True, exist_ok=True)
        chunk_path = chunk_dir / self._chunk_filename(chunk_index)
        await asyncio.to_thread(chunk_path.write_bytes, data)

        now = datetime.now(timezone.utc).isoformat()
        cursor = await self._db.execute(
            """
            UPDATE upload_sessions
            SET received_chunks = (
                    SELECT json_group_array(chunk_index)
                    FROM (
                        SELECT CAST(value AS INTEGER) AS chunk_index
                        FROM json_each(COALESCE(upload_sessions.received_chunks, '[]'))
                        UNION
                        SELECT ?
                        ORDER BY chunk_index
                    )
                ),
                updated_at = ?
            WHERE id = ?
              AND status = 'active'
              AND expires_at > ?
              AND (? IS NULL OR user_id = ?)
              AND ? >= 0
              AND ? < total_chunks
            """,
            (
                chunk_index,
                now,
                session_id,
                now,
                user_id,
                user_id,
                chunk_index,
                chunk_index,
            ),
        )
        await self._db.commit()
        if cursor.rowcount == 0:
            # The session may have expired or been removed after the initial
            # lookup. The file is harmless staging data; report it as unavailable.
            raise SessionNotFoundError(session_id)

        return True

    async def get_received_chunks(
        self,
        session_id: str,
        user_id: Optional[int] = None,
        require_active: bool = False,
    ) -> List[int]:
        """Return sorted received indices, optionally scoped to an owner."""
        session = await self.get_session(
            session_id, user_id=user_id, require_active=require_active
        )
        if session is None:
            raise SessionNotFoundError(session_id)
        return sorted(json.loads(session["received_chunks"] or "[]"))

    async def merge_chunks(
        self,
        session_id: str,
        user_id: Optional[int] = None,
        require_active: bool = False,
    ) -> str:
        """Merge all chunks in order into a complete file.

        Reads chunks sequentially (0, 1, 2, ...) and writes them to a single
        output file at {media_root}/.chunks/{session_id}/merged_{file_name}.

        Args:
            session_id: The upload session ID.

        Returns:
            Absolute path to the merged file.

        Raises:
            SessionNotFoundError: If the session does not exist.
            FileNotFoundError: If any expected chunk file is missing.
        """
        session = await self.get_session(
            session_id, user_id=user_id, require_active=require_active
        )
        if session is None:
            raise SessionNotFoundError(session_id)

        chunk_dir = self._session_chunk_dir(session_id)
        total_chunks = session["total_chunks"]
        file_name = session["file_name"]
        merged_path = chunk_dir / f"merged_{file_name}"

        def _merge() -> None:
            with open(merged_path, "wb") as out_file:
                for i in range(total_chunks):
                    chunk_path = chunk_dir / self._chunk_filename(i)
                    if not chunk_path.exists():
                        raise FileNotFoundError(
                            f"Missing chunk {i} for session {session_id}"
                        )
                    with open(chunk_path, "rb") as chunk_file:
                        shutil.copyfileobj(chunk_file, out_file)

        await asyncio.to_thread(_merge)
        return str(merged_path)

    @staticmethod
    def compute_file_hash(file_path: str) -> str:
        """Compute the SHA-256 hex digest of a file.

        Args:
            file_path: Path to the file.

        Returns:
            The SHA-256 hex digest.
        """
        sha256 = hashlib.sha256()
        with open(file_path, "rb") as f:
            while True:
                chunk = f.read(8192)
                if not chunk:
                    break
                sha256.update(chunk)
        return sha256.hexdigest()

    def verify_integrity(self, file_path: str, expected_hash: str) -> bool:
        """Compare SHA-256 of merged file with expected hash.

        Args:
            file_path: Path to the merged file.
            expected_hash: Expected SHA-256 hex digest.

        Returns:
            True if the file's SHA-256 matches the expected hash.
        """
        return self.compute_file_hash(file_path) == expected_hash

    async def cleanup_session(self, session_id: str) -> None:
        """Delete temp chunk files and session record from database.

        Args:
            session_id: The upload session ID.
        """
        # Remove chunk directory from disk
        chunk_dir = self._session_chunk_dir(session_id)
        if await asyncio.to_thread(chunk_dir.exists):
            await asyncio.to_thread(shutil.rmtree, chunk_dir)

        # Remove session record from database
        await self._db.execute(
            "DELETE FROM upload_sessions WHERE id = ?", (session_id,)
        )
        await self._db.commit()

    async def get_session(
        self,
        session_id: str,
        user_id: Optional[int] = None,
        require_active: bool = False,
    ) -> Optional[dict]:
        """Get a session by ID, optionally requiring owner and live status."""
        now = datetime.now(timezone.utc).isoformat()
        cursor = await self._db.execute(
            """SELECT * FROM upload_sessions
               WHERE id = ?
                 AND (? IS NULL OR user_id = ?)
                 AND (? = 0 OR (status = 'active' AND expires_at > ?))""",
            (session_id, user_id, user_id, int(require_active), now),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        # Convert aiosqlite.Row to dict
        return dict(row)

    async def cleanup_expired_sessions(self) -> int:
        """Clean up sessions that have expired (>7 days).

        Deletes chunk files from disk and removes session records from the
        database for all sessions whose expires_at is in the past.

        Returns:
            Number of sessions cleaned up.
        """
        now = datetime.now(timezone.utc).isoformat()

        # Find expired sessions
        cursor = await self._db.execute(
            "SELECT id FROM upload_sessions WHERE expires_at < ? AND status = 'active'",
            (now,),
        )
        expired_rows = await cursor.fetchall()

        count = 0
        for row in expired_rows:
            session_id = row["id"] if isinstance(row, dict) else row[0]
            # Remove chunk directory
            chunk_dir = self._session_chunk_dir(session_id)
            if chunk_dir.exists():
                shutil.rmtree(chunk_dir)
            count += 1

        # Delete all expired session records
        if count > 0:
            await self._db.execute(
                "DELETE FROM upload_sessions WHERE expires_at < ? AND status = 'active'",
                (now,),
            )
            await self._db.commit()

        return count
