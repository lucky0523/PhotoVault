"""Tests for ChunkManager service.

Tests cover:
- Session creation with 7-day expiry
- Chunk storage with MD5 verification
- Received chunks tracking
- Chunk merging into complete file
- SHA-256 integrity verification
- Session cleanup
- Expired session cleanup
"""

import hashlib
import json
import math
import os
from datetime import datetime, timezone, timedelta
from pathlib import Path

import pytest
import aiosqlite

from app.core.config import reset_settings
from app.core.database import init_db
from app.services.chunk_manager import (
    ChunkChecksumError,
    ChunkManager,
    SessionNotFoundError,
)


@pytest.fixture(autouse=True)
def _reset_settings():
    """Reset settings singleton before each test."""
    reset_settings()
    yield
    reset_settings()


@pytest.fixture
async def storage_root(tmp_path):
    """Create a temporary storage root directory."""
    root = tmp_path / "storage"
    root.mkdir()
    return str(root)


@pytest.fixture
async def db(tmp_path, storage_root):
    """Create a temp database with schema initialized."""
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = storage_root
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key"
    reset_settings()

    db_path = str(tmp_path / "test.db")
    await init_db(db_path)

    conn = await aiosqlite.connect(db_path)
    await conn.execute("PRAGMA foreign_keys=ON;")
    conn.row_factory = aiosqlite.Row

    # Create a test user
    await conn.execute(
        "INSERT INTO users (username, password_hash, is_admin) VALUES (?, ?, ?)",
        ("testuser", "fakehash", False),
    )
    await conn.commit()

    try:
        yield conn
    finally:
        await conn.close()
        os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
        os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
        os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
        reset_settings()


@pytest.fixture
def chunk_manager(db, storage_root):
    """Create a ChunkManager instance."""
    return ChunkManager(db, storage_root)


def make_file_data(size: int) -> bytes:
    """Generate deterministic test data of given size."""
    # Use a repeating pattern for predictable content
    pattern = b"ABCDEFGHIJKLMNOP"  # 16 bytes
    repeats = size // len(pattern) + 1
    return (pattern * repeats)[:size]


def compute_sha256(data: bytes) -> str:
    """Compute SHA-256 hex digest of data."""
    return hashlib.sha256(data).hexdigest()


def compute_md5(data: bytes) -> str:
    """Compute MD5 hex digest of data."""
    return hashlib.md5(data).hexdigest()


# ---------------------------------------------------------------------------
# Session creation tests
# ---------------------------------------------------------------------------


class TestCreateSession:
    """Tests for create_session method."""

    @pytest.mark.asyncio
    async def test_create_session_returns_uuid(self, chunk_manager):
        """create_session returns a valid UUID string."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc123",
            file_name="photo.jpg",
            file_size=5 * 1024 * 1024,
            target_path="/data/testuser/device/DCIM",
            device_name="Pixel9Pro",
            original_path="/DCIM/Camera/photo.jpg",
        )
        assert session_id is not None
        assert len(session_id) == 36  # UUID format

    @pytest.mark.asyncio
    async def test_create_session_writes_to_db(self, chunk_manager, db):
        """create_session writes a record to upload_sessions table."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc123",
            file_name="photo.jpg",
            file_size=5 * 1024 * 1024,
            target_path="/data/testuser/device/DCIM",
            device_name="Pixel9Pro",
            original_path="/DCIM/Camera/photo.jpg",
        )

        session = await chunk_manager.get_session(session_id)
        assert session is not None
        assert session["file_hash"] == "abc123"
        assert session["file_name"] == "photo.jpg"
        assert session["file_size"] == 5 * 1024 * 1024
        assert session["total_chunks"] == 3  # ceil(5MB / 2MB)
        assert session["status"] == "active"
        assert session["device_name"] == "Pixel9Pro"

    @pytest.mark.asyncio
    async def test_create_session_sets_7_day_expiry(self, chunk_manager):
        """create_session sets expires_at to 7 days from creation."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc123",
            file_name="photo.jpg",
            file_size=1024,
            target_path="/data/testuser/device/DCIM",
            device_name="Pixel9Pro",
            original_path="/DCIM/Camera/photo.jpg",
        )

        session = await chunk_manager.get_session(session_id)
        created_at = datetime.fromisoformat(session["created_at"])
        expires_at = datetime.fromisoformat(session["expires_at"])
        diff = expires_at - created_at
        assert diff.days == 7

    @pytest.mark.asyncio
    async def test_create_session_creates_chunk_directory(
        self, chunk_manager, storage_root
    ):
        """create_session creates the chunk temp directory."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc123",
            file_name="photo.jpg",
            file_size=1024,
            target_path="/data/testuser/device/DCIM",
            device_name="Pixel9Pro",
            original_path="/DCIM/Camera/photo.jpg",
        )

        chunk_dir = Path(storage_root) / ".chunks" / session_id
        assert chunk_dir.exists()

    @pytest.mark.asyncio
    async def test_total_chunks_calculation(self, chunk_manager):
        """total_chunks is ceil(file_size / CHUNK_SIZE)."""
        # Exactly 2MB -> 1 chunk
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="h1",
            file_name="a.jpg",
            file_size=2 * 1024 * 1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )
        session = await chunk_manager.get_session(session_id)
        assert session["total_chunks"] == 1

    @pytest.mark.asyncio
    async def test_total_chunks_not_exact_multiple(self, chunk_manager):
        """total_chunks rounds up for non-exact multiples."""
        # 2MB + 1 byte -> 2 chunks
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="h2",
            file_name="b.jpg",
            file_size=2 * 1024 * 1024 + 1,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )
        session = await chunk_manager.get_session(session_id)
        assert session["total_chunks"] == 2


# ---------------------------------------------------------------------------
# Chunk storage tests
# ---------------------------------------------------------------------------


class TestStoreChunk:
    """Tests for store_chunk method."""

    @pytest.mark.asyncio
    async def test_store_chunk_success(self, chunk_manager, storage_root):
        """store_chunk writes chunk data to disk and returns True."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="photo.jpg",
            file_size=4 * 1024 * 1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        data = b"x" * 1024
        md5 = compute_md5(data)
        result = await chunk_manager.store_chunk(session_id, 0, data, md5)
        assert result is True

        # Verify file on disk
        chunk_path = (
            Path(storage_root) / ".chunks" / session_id / "chunk_000000"
        )
        assert chunk_path.exists()
        assert chunk_path.read_bytes() == data

    @pytest.mark.asyncio
    async def test_store_chunk_updates_received_chunks(self, chunk_manager):
        """store_chunk updates received_chunks in the database."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="photo.jpg",
            file_size=6 * 1024 * 1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        data = b"chunk0"
        await chunk_manager.store_chunk(session_id, 0, data, compute_md5(data))

        received = await chunk_manager.get_received_chunks(session_id)
        assert received == [0]

        data2 = b"chunk2"
        await chunk_manager.store_chunk(session_id, 2, data2, compute_md5(data2))

        received = await chunk_manager.get_received_chunks(session_id)
        assert received == [0, 2]

    @pytest.mark.asyncio
    async def test_store_chunk_md5_mismatch_raises(self, chunk_manager):
        """store_chunk raises ChunkChecksumError on MD5 mismatch."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="photo.jpg",
            file_size=4 * 1024 * 1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        data = b"hello"
        wrong_md5 = "0000000000000000000000000000dead"

        with pytest.raises(ChunkChecksumError) as exc_info:
            await chunk_manager.store_chunk(session_id, 0, data, wrong_md5)

        assert exc_info.value.chunk_index == 0
        assert exc_info.value.expected == wrong_md5

    @pytest.mark.asyncio
    async def test_store_chunk_session_not_found(self, chunk_manager):
        """store_chunk raises SessionNotFoundError for invalid session."""
        with pytest.raises(SessionNotFoundError):
            await chunk_manager.store_chunk(
                "nonexistent-session", 0, b"data", compute_md5(b"data")
            )

    @pytest.mark.asyncio
    async def test_store_chunk_idempotent(self, chunk_manager):
        """Storing the same chunk index twice does not duplicate in received list."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="photo.jpg",
            file_size=4 * 1024 * 1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        data = b"chunk_data"
        md5 = compute_md5(data)
        await chunk_manager.store_chunk(session_id, 0, data, md5)
        await chunk_manager.store_chunk(session_id, 0, data, md5)

        received = await chunk_manager.get_received_chunks(session_id)
        assert received == [0]


# ---------------------------------------------------------------------------
# Merge and integrity tests
# ---------------------------------------------------------------------------


class TestMergeChunks:
    """Tests for merge_chunks and verify_integrity methods."""

    @pytest.mark.asyncio
    async def test_merge_chunks_produces_correct_file(self, chunk_manager):
        """merge_chunks combines chunks in order to produce the original file."""
        file_data = make_file_data(5 * 1024 * 1024)  # 5MB -> 3 chunks
        file_hash = compute_sha256(file_data)
        chunk_size = ChunkManager.CHUNK_SIZE

        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash=file_hash,
            file_name="big.jpg",
            file_size=len(file_data),
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        # Store all chunks
        total_chunks = math.ceil(len(file_data) / chunk_size)
        for i in range(total_chunks):
            start = i * chunk_size
            end = min(start + chunk_size, len(file_data))
            chunk_data = file_data[start:end]
            await chunk_manager.store_chunk(
                session_id, i, chunk_data, compute_md5(chunk_data)
            )

        # Merge
        merged_path = await chunk_manager.merge_chunks(session_id)
        assert os.path.exists(merged_path)

        # Verify content
        with open(merged_path, "rb") as f:
            merged_data = f.read()
        assert merged_data == file_data

    @pytest.mark.asyncio
    async def test_verify_integrity_success(self, chunk_manager):
        """verify_integrity returns True when SHA-256 matches."""
        file_data = b"test file content for integrity check"
        file_hash = compute_sha256(file_data)
        chunk_size = ChunkManager.CHUNK_SIZE

        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash=file_hash,
            file_name="test.txt",
            file_size=len(file_data),
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        # Store single chunk
        await chunk_manager.store_chunk(
            session_id, 0, file_data, compute_md5(file_data)
        )

        merged_path = await chunk_manager.merge_chunks(session_id)
        assert chunk_manager.verify_integrity(merged_path, file_hash) is True

    @pytest.mark.asyncio
    async def test_verify_integrity_failure(self, chunk_manager):
        """verify_integrity returns False when SHA-256 does not match."""
        file_data = b"some data"

        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="wrong_hash",
            file_name="test.txt",
            file_size=len(file_data),
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        await chunk_manager.store_chunk(
            session_id, 0, file_data, compute_md5(file_data)
        )

        merged_path = await chunk_manager.merge_chunks(session_id)
        assert chunk_manager.verify_integrity(merged_path, "wrong_hash") is False

    @pytest.mark.asyncio
    async def test_merge_chunks_missing_chunk_raises(self, chunk_manager):
        """merge_chunks raises FileNotFoundError if a chunk is missing."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="test.txt",
            file_size=4 * 1024 * 1024,  # 2 chunks
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        # Only store chunk 0, skip chunk 1
        data = b"x" * (2 * 1024 * 1024)
        await chunk_manager.store_chunk(session_id, 0, data, compute_md5(data))

        with pytest.raises(FileNotFoundError):
            await chunk_manager.merge_chunks(session_id)

    @pytest.mark.asyncio
    async def test_merge_session_not_found(self, chunk_manager):
        """merge_chunks raises SessionNotFoundError for invalid session."""
        with pytest.raises(SessionNotFoundError):
            await chunk_manager.merge_chunks("nonexistent")


# ---------------------------------------------------------------------------
# Cleanup tests
# ---------------------------------------------------------------------------


class TestCleanupSession:
    """Tests for cleanup_session method."""

    @pytest.mark.asyncio
    async def test_cleanup_removes_files_and_record(
        self, chunk_manager, storage_root
    ):
        """cleanup_session removes chunk directory and DB record."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="photo.jpg",
            file_size=1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        # Store a chunk
        data = b"chunk"
        await chunk_manager.store_chunk(session_id, 0, data, compute_md5(data))

        # Verify exists
        chunk_dir = Path(storage_root) / ".chunks" / session_id
        assert chunk_dir.exists()

        # Cleanup
        await chunk_manager.cleanup_session(session_id)

        # Verify removed
        assert not chunk_dir.exists()
        session = await chunk_manager.get_session(session_id)
        assert session is None


class TestCleanupExpiredSessions:
    """Tests for cleanup_expired_sessions method."""

    @pytest.mark.asyncio
    async def test_cleanup_expired_sessions(self, chunk_manager, db, storage_root):
        """cleanup_expired_sessions removes sessions past their expiry."""
        # Create a session manually with an expired timestamp
        expired_time = (
            datetime.now(timezone.utc) - timedelta(days=8)
        ).isoformat()
        now = datetime.now(timezone.utc).isoformat()

        await db.execute(
            """
            INSERT INTO upload_sessions
                (id, user_id, file_hash, file_name, file_size, total_chunks,
                 received_chunks, target_path, device_name, original_path,
                 status, created_at, updated_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                "expired-session-1",
                1,
                "hash1",
                "old.jpg",
                1024,
                1,
                "[]",
                "/data/t",
                "d",
                "/p",
                "active",
                expired_time,
                expired_time,
                expired_time,
            ),
        )
        await db.commit()

        # Create chunk dir for expired session
        expired_dir = Path(storage_root) / ".chunks" / "expired-session-1"
        expired_dir.mkdir(parents=True)
        (expired_dir / "chunk_000000").write_bytes(b"old data")

        # Create a non-expired session
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="hash2",
            file_name="new.jpg",
            file_size=1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        # Run cleanup
        count = await chunk_manager.cleanup_expired_sessions()
        assert count == 1

        # Expired session should be gone
        assert not expired_dir.exists()
        session = await chunk_manager.get_session("expired-session-1")
        assert session is None

        # Non-expired session should still exist
        session = await chunk_manager.get_session(session_id)
        assert session is not None

    @pytest.mark.asyncio
    async def test_cleanup_no_expired_sessions(self, chunk_manager):
        """cleanup_expired_sessions returns 0 when nothing is expired."""
        await chunk_manager.create_session(
            user_id=1,
            file_hash="hash",
            file_name="new.jpg",
            file_size=1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        count = await chunk_manager.cleanup_expired_sessions()
        assert count == 0


# ---------------------------------------------------------------------------
# get_received_chunks tests
# ---------------------------------------------------------------------------


class TestGetReceivedChunks:
    """Tests for get_received_chunks method."""

    @pytest.mark.asyncio
    async def test_empty_initially(self, chunk_manager):
        """New session has no received chunks."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="photo.jpg",
            file_size=4 * 1024 * 1024,
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        received = await chunk_manager.get_received_chunks(session_id)
        assert received == []

    @pytest.mark.asyncio
    async def test_returns_sorted_list(self, chunk_manager):
        """get_received_chunks returns indices in sorted order."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc",
            file_name="photo.jpg",
            file_size=10 * 1024 * 1024,  # 5 chunks
            target_path="/data/t",
            device_name="d",
            original_path="/p",
        )

        # Store chunks out of order
        for idx in [3, 1, 4, 0, 2]:
            data = f"chunk{idx}".encode()
            await chunk_manager.store_chunk(session_id, idx, data, compute_md5(data))

        received = await chunk_manager.get_received_chunks(session_id)
        assert received == [0, 1, 2, 3, 4]

    @pytest.mark.asyncio
    async def test_session_not_found(self, chunk_manager):
        """get_received_chunks raises SessionNotFoundError for invalid session."""
        with pytest.raises(SessionNotFoundError):
            await chunk_manager.get_received_chunks("nonexistent")


# ---------------------------------------------------------------------------
# get_session tests
# ---------------------------------------------------------------------------


class TestGetSession:
    """Tests for get_session method."""

    @pytest.mark.asyncio
    async def test_returns_none_for_nonexistent(self, chunk_manager):
        """get_session returns None for non-existent session ID."""
        result = await chunk_manager.get_session("does-not-exist")
        assert result is None

    @pytest.mark.asyncio
    async def test_returns_dict_with_all_fields(self, chunk_manager):
        """get_session returns a dict with all expected fields."""
        session_id = await chunk_manager.create_session(
            user_id=1,
            file_hash="abc123",
            file_name="photo.jpg",
            file_size=2048,
            target_path="/data/testuser/device/DCIM",
            device_name="Pixel9Pro",
            original_path="/DCIM/Camera/photo.jpg",
        )

        session = await chunk_manager.get_session(session_id)
        assert session["id"] == session_id
        assert session["user_id"] == 1
        assert session["file_hash"] == "abc123"
        assert session["file_name"] == "photo.jpg"
        assert session["file_size"] == 2048
        assert session["target_path"] == "/data/testuser/device/DCIM"
        assert session["device_name"] == "Pixel9Pro"
        assert session["original_path"] == "/DCIM/Camera/photo.jpg"
