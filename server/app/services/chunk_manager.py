"""Chunk manager.

Manages chunked file uploads with resume capability:
- Session creation and expiration (7 days)
- Chunk storage and verification
- Chunk merging and integrity validation
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import shutil
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import List, Optional

import aiosqlite


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


class ChunkManager:
    """Manages chunked file uploads with resume capability.

    Attributes:
        CHUNK_SIZE: Default chunk size in bytes (2 MB).
    """

    CHUNK_SIZE = 2 * 1024 * 1024  # 2MB

    def __init__(self, db: aiosqlite.Connection, storage_root: str):
        """Initialize ChunkManager.

        Args:
            db: An aiosqlite database connection.
            storage_root: Root directory for file storage.
        """
        self._db = db
        self._storage_root = storage_root

    @property
    def _chunks_base_dir(self) -> Path:
        """Base directory for all chunk temporary storage."""
        return Path(self._storage_root) / ".chunks"

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
    ) -> str:
        """Create an upload session.

        Writes a new record to the upload_sessions table with a 7-day expiry.

        Args:
            user_id: ID of the authenticated user.
            file_hash: SHA-256 hash of the complete file.
            file_name: Original file name.
            file_size: Total file size in bytes.
            target_path: Resolved storage path on the NAS.
            device_name: Name of the source device.
            original_path: Original file path on the device.

        Returns:
            The generated session ID (UUID string).
        """
        session_id = str(uuid.uuid4())
        total_chunks = math.ceil(file_size / self.CHUNK_SIZE)
        now = datetime.now(timezone.utc)
        expires_at = now + timedelta(days=7)

        await self._db.execute(
            """
            INSERT INTO upload_sessions
                (id, user_id, file_hash, file_name, file_size, total_chunks,
                 received_chunks, target_path, device_name, original_path,
                 status, created_at, updated_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                "active",
                now.isoformat(),
                now.isoformat(),
                expires_at.isoformat(),
            ),
        )
        await self._db.commit()

        # Create the chunk directory
        chunk_dir = self._session_chunk_dir(session_id)
        chunk_dir.mkdir(parents=True, exist_ok=True)

        return session_id

    async def store_chunk(
        self, session_id: str, chunk_index: int, data: bytes, md5_checksum: str
    ) -> bool:
        """Store a chunk to the temp directory and verify its MD5 checksum.

        Args:
            session_id: The upload session ID.
            chunk_index: Zero-based index of the chunk.
            data: Raw chunk bytes.
            md5_checksum: Expected MD5 hex digest of the chunk data.

        Returns:
            True if the chunk was stored successfully.

        Raises:
            ChunkChecksumError: If the computed MD5 does not match.
            SessionNotFoundError: If the session does not exist.
        """
        session = await self.get_session(session_id)
        if session is None:
            raise SessionNotFoundError(session_id)

        # Verify MD5 checksum
        actual_md5 = hashlib.md5(data).hexdigest()
        if actual_md5 != md5_checksum:
            raise ChunkChecksumError(chunk_index, md5_checksum, actual_md5)

        # Write chunk to disk
        chunk_dir = self._session_chunk_dir(session_id)
        chunk_dir.mkdir(parents=True, exist_ok=True)
        chunk_path = chunk_dir / self._chunk_filename(chunk_index)
        chunk_path.write_bytes(data)

        # Update received_chunks in the database
        received = json.loads(session["received_chunks"])
        if chunk_index not in received:
            received.append(chunk_index)
            received.sort()

        now = datetime.now(timezone.utc).isoformat()
        await self._db.execute(
            """
            UPDATE upload_sessions
            SET received_chunks = ?, updated_at = ?
            WHERE id = ?
            """,
            (json.dumps(received), now, session_id),
        )
        await self._db.commit()

        return True

    async def get_received_chunks(self, session_id: str) -> List[int]:
        """Return list of successfully received chunk indices.

        Args:
            session_id: The upload session ID.

        Returns:
            Sorted list of chunk indices that have been received.

        Raises:
            SessionNotFoundError: If the session does not exist.
        """
        session = await self.get_session(session_id)
        if session is None:
            raise SessionNotFoundError(session_id)
        return json.loads(session["received_chunks"])

    async def merge_chunks(self, session_id: str) -> str:
        """Merge all chunks in order into a complete file.

        Reads chunks sequentially (0, 1, 2, ...) and writes them to a single
        output file at {storage_root}/.chunks/{session_id}/merged_{file_name}.

        Args:
            session_id: The upload session ID.

        Returns:
            Absolute path to the merged file.

        Raises:
            SessionNotFoundError: If the session does not exist.
            FileNotFoundError: If any expected chunk file is missing.
        """
        session = await self.get_session(session_id)
        if session is None:
            raise SessionNotFoundError(session_id)

        chunk_dir = self._session_chunk_dir(session_id)
        total_chunks = session["total_chunks"]
        file_name = session["file_name"]
        merged_path = chunk_dir / f"merged_{file_name}"

        with open(merged_path, "wb") as out_file:
            for i in range(total_chunks):
                chunk_path = chunk_dir / self._chunk_filename(i)
                if not chunk_path.exists():
                    raise FileNotFoundError(
                        f"Missing chunk {i} for session {session_id}"
                    )
                out_file.write(chunk_path.read_bytes())

        return str(merged_path)

    def verify_integrity(self, file_path: str, expected_hash: str) -> bool:
        """Compare SHA-256 of merged file with expected hash.

        Args:
            file_path: Path to the merged file.
            expected_hash: Expected SHA-256 hex digest.

        Returns:
            True if the file's SHA-256 matches the expected hash.
        """
        sha256 = hashlib.sha256()
        with open(file_path, "rb") as f:
            while True:
                chunk = f.read(8192)
                if not chunk:
                    break
                sha256.update(chunk)
        return sha256.hexdigest() == expected_hash

    async def cleanup_session(self, session_id: str) -> None:
        """Delete temp chunk files and session record from database.

        Args:
            session_id: The upload session ID.
        """
        # Remove chunk directory from disk
        chunk_dir = self._session_chunk_dir(session_id)
        if chunk_dir.exists():
            shutil.rmtree(chunk_dir)

        # Remove session record from database
        await self._db.execute(
            "DELETE FROM upload_sessions WHERE id = ?", (session_id,)
        )
        await self._db.commit()

    async def get_session(self, session_id: str) -> Optional[dict]:
        """Get session info by ID.

        Args:
            session_id: The upload session ID.

        Returns:
            A dict with session fields, or None if not found.
        """
        cursor = await self._db.execute(
            "SELECT * FROM upload_sessions WHERE id = ?", (session_id,)
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
