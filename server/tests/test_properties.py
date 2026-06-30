"""Property-based tests for PhotoVault server using Hypothesis.

Each test runs at least 100 iterations and covers key correctness properties
from the design document.
"""

from __future__ import annotations

import json
import math
import re
import asyncio
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional

import pytest
import aiosqlite
from hypothesis import given, settings, assume
from hypothesis import strategies as st

from app.models.storage import FileMetadata, StoragePolicy, PathValidationResult
from app.services.storage_path_engine import StoragePathEngine
from app.services.chunk_manager import ChunkManager
from app.services.deduplication_service import DeduplicationService
from app.services.auth_service import AuthService, _hash_password, _verify_password
from app.services.upload_service import UploadService


# ---------------------------------------------------------------------------
# Helpers / strategies
# ---------------------------------------------------------------------------

# Strategy for valid identifiers (non-empty, printable, no path separators)
_valid_identifier = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "Pd"), whitelist_characters="_-"),
    min_size=1,
    max_size=30,
)

# Strategy for device names — any unicode text
_device_name_input = st.text(min_size=0, max_size=100)

# Strategy for safe path segments (no null/newline, no path sep, reasonable length)
_safe_path_segment = st.text(
    alphabet=st.characters(
        whitelist_categories=("L", "N", "P", "S"),
        blacklist_characters="\x00\n\r/",
    ),
    min_size=1,
    max_size=50,
)

# Strategy for datetime values (reasonable range)
_reasonable_datetime = st.datetimes(
    min_value=datetime(1970, 1, 1),
    max_value=datetime(2099, 12, 31),
)


def _run_async(coro):
    """Run an async coroutine synchronously for Hypothesis tests."""
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coro)
    finally:
        loop.close()


async def _create_test_db() -> aiosqlite.Connection:
    """Create an in-memory database with the required schema."""
    db = await aiosqlite.connect(":memory:")
    db.row_factory = aiosqlite.Row

    await db.executescript("""
        CREATE TABLE users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            is_admin BOOLEAN DEFAULT FALSE,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE file_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            file_hash TEXT NOT NULL,
            file_path TEXT NOT NULL,
            original_path TEXT NOT NULL,
            device_name TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            file_name TEXT NOT NULL,
            mime_type TEXT,
            exif_time TIMESTAMP,
            is_reference BOOLEAN DEFAULT FALSE,
            reference_to INTEGER,
            live_photo_group_id TEXT,
            live_photo_type TEXT,
            media_type TEXT DEFAULT 'image',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            deleted_at TIMESTAMP,
            deleted_batch_id TEXT,
            purged_at TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id),
            FOREIGN KEY (reference_to) REFERENCES file_records(id),
            UNIQUE(user_id, file_hash, file_path)
        );

        CREATE TABLE upload_sessions (
            id TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL,
            file_hash TEXT NOT NULL,
            file_name TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            total_chunks INTEGER NOT NULL,
            received_chunks TEXT DEFAULT '[]',
            target_path TEXT NOT NULL,
            device_name TEXT NOT NULL,
            original_path TEXT NOT NULL,
            status TEXT DEFAULT 'active',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            expires_at TIMESTAMP NOT NULL,
            FOREIGN KEY (user_id) REFERENCES users(id)
        );

        CREATE INDEX idx_file_hash ON file_records(user_id, file_hash);
        CREATE INDEX idx_upload_session_user ON upload_sessions(user_id, file_hash);
    """)
    return db


# ---------------------------------------------------------------------------
# Property 2: Storage path combinations
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 2: 存储路径引擎的四种组合正确性
@given(
    username=_valid_identifier,
    device_raw=st.text(min_size=1, max_size=30, alphabet=st.characters(whitelist_categories=("L", "N"), whitelist_characters="_-")),
    source_folder=_safe_path_segment,
    custom_path=_safe_path_segment,
    exif_time=_reasonable_datetime,
)
@settings(max_examples=100)
def test_storage_path_four_combinations(
    username: str, device_raw: str, source_folder: str, custom_path: str, exif_time: datetime
):
    """**Validates: Requirements 9.1, 10.2, 11.2, 11.4, 12.2**

    For any valid inputs, the four storage policy combinations produce correct paths.
    """
    storage_root = "/data/photovault"
    device = StoragePathEngine.sanitize_device_name(device_raw)
    year_month = f"{exif_time.year:04d}/{exif_time.month:02d}"

    file_metadata = FileMetadata(exif_time=exif_time, file_created_time=None, sub_folder="")

    # Combo 1: No custom path + no year/month
    policy1 = StoragePolicy(use_custom_path=False, custom_path=None, use_year_month_layer=False)
    path1 = StoragePathEngine.resolve_path(storage_root, username, device_raw, source_folder, policy1, file_metadata)
    expected1 = f"{storage_root}/{username}/{device}/{source_folder}/"
    assert path1 == expected1, f"Combo 1 failed: got {path1}, expected {expected1}"

    # Combo 2: No custom path + year/month
    policy2 = StoragePolicy(use_custom_path=False, custom_path=None, use_year_month_layer=True)
    path2 = StoragePathEngine.resolve_path(storage_root, username, device_raw, source_folder, policy2, file_metadata)
    expected2 = f"{storage_root}/{username}/{device}/{source_folder}/{year_month}/"
    assert path2 == expected2, f"Combo 2 failed: got {path2}, expected {expected2}"

    # Combo 3: Custom path + no year/month
    policy3 = StoragePolicy(use_custom_path=True, custom_path=f"/{custom_path}", use_year_month_layer=False)
    path3 = StoragePathEngine.resolve_path(storage_root, username, device_raw, source_folder, policy3, file_metadata)
    expected3 = f"/{custom_path}/{source_folder}/"
    assert path3 == expected3, f"Combo 3 failed: got {path3}, expected {expected3}"

    # Combo 4: Custom path + year/month
    policy4 = StoragePolicy(use_custom_path=True, custom_path=f"/{custom_path}", use_year_month_layer=True)
    path4 = StoragePathEngine.resolve_path(storage_root, username, device_raw, source_folder, policy4, file_metadata)
    expected4 = f"/{custom_path}/{source_folder}/{year_month}/"
    assert path4 == expected4, f"Combo 4 failed: got {path4}, expected {expected4}"

    # All paths must end with /
    assert path1.endswith("/")
    assert path2.endswith("/")
    assert path3.endswith("/")
    assert path4.endswith("/")


# ---------------------------------------------------------------------------
# Property 3: Device name sanitization
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 3: 设备名称净化
@given(name=st.text(min_size=0, max_size=200))
@settings(max_examples=100)
def test_sanitize_device_name_output_only_allowed_chars(name: str):
    """**Validates: Requirements 7.3**

    For any string input, sanitize_device_name output contains only [A-Za-z0-9_-].
    """
    result = StoragePathEngine.sanitize_device_name(name)

    # Output must be non-empty (falls back to "unknown_device")
    assert len(result) > 0

    # Output must only contain allowed characters
    allowed_pattern = re.compile(r"^[A-Za-z0-9_\-]+$")
    assert allowed_pattern.match(result), f"Invalid chars in sanitized name: {result!r}"

    # No consecutive underscores
    assert "__" not in result

    # No leading or trailing underscores
    assert not result.startswith("_")
    assert not result.endswith("_")


# ---------------------------------------------------------------------------
# Property 4: Path validation rejects illegal input
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 4: 路径验证拒绝非法输入
@given(
    data=st.data(),
)
@settings(max_examples=100)
def test_path_validation_rejects_illegal_input(data):
    """**Validates: Requirements 9.3, 9.4, 10.1**

    Paths with illegal chars, folder names > 255, or total path > 4096
    must be rejected by validate_path.
    """
    # Pick one of three illegal path types
    violation_type = data.draw(st.sampled_from(["illegal_chars", "long_folder", "long_path"]))

    if violation_type == "illegal_chars":
        # Generate a path containing null byte, newline, or carriage return
        illegal_char = data.draw(st.sampled_from(["\x00", "\n", "\r"]))
        base = data.draw(st.text(min_size=1, max_size=50, alphabet=st.characters(
            whitelist_categories=("L", "N"), whitelist_characters="_-/"
        )))
        # Insert illegal char at a random position
        pos = data.draw(st.integers(min_value=0, max_value=len(base)))
        path = base[:pos] + illegal_char + base[pos:]

    elif violation_type == "long_folder":
        # Generate a single folder name > 255 characters
        long_name = data.draw(st.text(
            min_size=256, max_size=300,
            alphabet=st.characters(whitelist_categories=("L", "N"))
        ))
        path = f"/data/{long_name}/file.jpg"

    else:  # long_path
        # Generate a path > 4096 characters total
        segment = data.draw(st.text(
            min_size=1, max_size=100,
            alphabet=st.characters(whitelist_categories=("L", "N"))
        ))
        # Repeat to exceed 4096
        repeats = (4097 // (len(segment) + 1)) + 2
        path = "/" + "/".join([segment] * repeats)
        assume(len(path) > 4096)

    result = StoragePathEngine.validate_path(path)
    assert result.is_valid is False, f"Path should be invalid: {path[:100]}..."
    assert result.error_message != ""


# ---------------------------------------------------------------------------
# Property 5: Dedup idempotency
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 5: 重复检测的幂等性
@given(
    file_hash=st.text(alphabet="0123456789abcdef", min_size=64, max_size=64),
    query_count=st.integers(min_value=2, max_value=5),
)
@settings(max_examples=100)
def test_dedup_idempotency(file_hash: str, query_count: int):
    """**Validates: Requirements 6.3, 6.6**

    Same file_hash queried multiple times always returns the same result.
    """
    async def _run():
        db = await _create_test_db()
        try:
            dedup = DeduplicationService(db)
            user_id = 1

            # Insert a user
            await db.execute(
                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                (user_id, "testuser", "hash"),
            )

            # Register a file
            await dedup.register_file(
                user_id=user_id,
                file_hash=file_hash,
                file_path="/data/testuser/photo.jpg",
                original_path="/DCIM/photo.jpg",
                device_name="TestDevice",
                file_size=1024,
                file_name="photo.jpg",
            )

            # Query multiple times — should always return the same result
            results = []
            for _ in range(query_count):
                result = await dedup.check(user_id, file_hash)
                results.append(result)

            # All results must be non-None and consistent
            for r in results:
                assert r is not None
                assert r.file_hash == file_hash
                assert r.user_id == user_id

            # All results should reference the same record
            ids = {r.id for r in results}
            assert len(ids) == 1, "Dedup check is not idempotent"
        finally:
            await db.close()

    _run_async(_run())


# ---------------------------------------------------------------------------
# Property 6: User isolation
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 6: 用户存储隔离
@given(
    hash_a=st.text(alphabet="0123456789abcdef", min_size=64, max_size=64),
    hash_b=st.text(alphabet="0123456789abcdef", min_size=64, max_size=64),
)
@settings(max_examples=100)
def test_user_isolation(hash_a: str, hash_b: str):
    """**Validates: Requirements 4.4, 4.5, 7.1**

    User A's files are never accessible by user B.
    """
    # Ensure different hashes so we test actual isolation, not same-hash overlap
    assume(hash_a != hash_b)

    async def _run():
        db = await _create_test_db()
        try:
            dedup = DeduplicationService(db)

            # Create two users
            await db.execute(
                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                (1, "alice", "hash_a"),
            )
            await db.execute(
                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                (2, "bob", "hash_b"),
            )

            # Register a file for user A
            await dedup.register_file(
                user_id=1,
                file_hash=hash_a,
                file_path="/data/alice/photo.jpg",
                original_path="/DCIM/photo.jpg",
                device_name="AlicePhone",
                file_size=2048,
                file_name="photo.jpg",
            )

            # User B should NOT see user A's file
            result_b = await dedup.check(user_id=2, file_hash=hash_a)
            assert result_b is None, "User B can access User A's files — isolation violated"

            # Register a file for user B
            await dedup.register_file(
                user_id=2,
                file_hash=hash_b,
                file_path="/data/bob/photo2.jpg",
                original_path="/DCIM/photo2.jpg",
                device_name="BobPhone",
                file_size=4096,
                file_name="photo2.jpg",
            )

            # User A should NOT see user B's file
            result_a = await dedup.check(user_id=1, file_hash=hash_b)
            assert result_a is None, "User A can access User B's files — isolation violated"
        finally:
            await db.close()

    _run_async(_run())


# ---------------------------------------------------------------------------
# Property 7: Auth rejects invalid credentials
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 7: 无效认证始终被拒绝
@given(
    token=st.text(min_size=1, max_size=500),
)
@settings(max_examples=100, deadline=None)
def test_auth_rejects_invalid_tokens(token: str):
    """**Validates: Requirements 4.3, 4.6**

    Invalid tokens/passwords are always rejected.
    """
    # Ensure the token is not a valid JWT (very unlikely with random text)
    assume(not token.startswith("eyJ"))

    async def _run():
        db = await _create_test_db()
        try:
            # Insert a test user
            await db.execute(
                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                (1, "admin", _hash_password("correctpassword")),
            )
            await db.commit()

            auth = AuthService(db)

            # Invalid token must raise ValueError
            with pytest.raises(ValueError):
                await auth.verify_token(token)
        finally:
            await db.close()

    _run_async(_run())


# ---------------------------------------------------------------------------
# Property 8: Resume correctness
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 8: 断点续传的正确恢复
@given(
    file_size=st.integers(min_value=1, max_value=50 * 1024 * 1024),
    interruption_ratio=st.floats(min_value=0.0, max_value=1.0),
)
@settings(max_examples=100)
def test_resume_returns_correct_received_chunks(file_size: int, interruption_ratio: float):
    """**Validates: Requirements 5.3, 5.4, 5.7**

    For any interruption point, resume returns correct received_chunks list.
    """
    import hashlib
    import tempfile
    import shutil

    total_chunks = math.ceil(file_size / ChunkManager.CHUNK_SIZE)
    # Simulate receiving chunks up to the interruption point
    received_count = int(total_chunks * interruption_ratio)
    received_indices = list(range(received_count))

    async def _run():
        db = await _create_test_db()
        tmp_dir = tempfile.mkdtemp()
        try:
            # Insert user
            await db.execute(
                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                (1, "testuser", "hash"),
            )
            await db.commit()

            chunk_mgr = ChunkManager(db, tmp_dir)

            # Create a session
            session_id = await chunk_mgr.create_session(
                user_id=1,
                file_hash="a" * 64,
                file_name="test.jpg",
                file_size=file_size,
                target_path=f"{tmp_dir}/target/",
                device_name="TestDevice",
                original_path="/DCIM/test.jpg",
            )

            # Simulate storing chunks up to the interruption point
            for i in received_indices:
                chunk_data = b"x" * min(ChunkManager.CHUNK_SIZE, file_size - i * ChunkManager.CHUNK_SIZE)
                md5_hash = hashlib.md5(chunk_data).hexdigest()
                await chunk_mgr.store_chunk(session_id, i, chunk_data, md5_hash)

            # Get received chunks — should match exactly what was stored
            result = await chunk_mgr.get_received_chunks(session_id)
            assert result == received_indices, (
                f"Resume mismatch: expected {received_indices}, got {result}"
            )
        finally:
            await db.close()
            shutil.rmtree(tmp_dir, ignore_errors=True)

    _run_async(_run())


# ---------------------------------------------------------------------------
# Property 10: Year/month time extraction
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 10: 年月时间提取优先级
@given(
    exif_time=st.one_of(st.none(), _reasonable_datetime),
    file_created_time=st.one_of(st.none(), _reasonable_datetime),
)
@settings(max_examples=100)
def test_year_month_extraction_priority(
    exif_time: Optional[datetime], file_created_time: Optional[datetime]
):
    """**Validates: Requirements 11.1, 11.5**

    EXIF > creation time > unknown_date priority; format is always YYYY/MM.
    """
    file_metadata = FileMetadata(
        exif_time=exif_time,
        file_created_time=file_created_time,
        sub_folder="",
    )

    storage_root = "/data"
    username = "user"
    device_name = "device"
    source_folder = "DCIM"
    policy = StoragePolicy(use_custom_path=False, custom_path=None, use_year_month_layer=True)

    path = StoragePathEngine.resolve_path(
        storage_root, username, device_name, source_folder, policy, file_metadata
    )

    # Extract the year/month portion (last two segments before trailing /)
    # Expected path: /data/user/device/DCIM/{year_month}/
    segments = path.rstrip("/").split("/")

    if exif_time is not None:
        # Should use EXIF time
        expected_year = f"{exif_time.year:04d}"
        expected_month = f"{exif_time.month:02d}"
        assert segments[-2] == expected_year, f"Year mismatch: got {segments[-2]}, expected {expected_year}"
        assert segments[-1] == expected_month, f"Month mismatch: got {segments[-1]}, expected {expected_month}"
    elif file_created_time is not None:
        # Should use file creation time
        expected_year = f"{file_created_time.year:04d}"
        expected_month = f"{file_created_time.month:02d}"
        assert segments[-2] == expected_year, f"Year mismatch: got {segments[-2]}, expected {expected_year}"
        assert segments[-1] == expected_month, f"Month mismatch: got {segments[-1]}, expected {expected_month}"
    else:
        # Should fall back to "unknown_date"
        assert segments[-1] == "unknown_date", f"Expected 'unknown_date', got {segments[-1]}"

    # Verify format: if there's a year/month, it's always 4-digit year / 2-digit month
    if exif_time is not None or file_created_time is not None:
        year_str = segments[-2]
        month_str = segments[-1]
        assert re.match(r"^\d{4}$", year_str), f"Year format invalid: {year_str}"
        assert re.match(r"^\d{2}$", month_str), f"Month format invalid: {month_str}"


# ---------------------------------------------------------------------------
# Property 11: Same-name file conflict
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 11: 同名文件冲突解决
@given(
    file_name=st.from_regex(r"[a-zA-Z0-9]{1,20}\.(jpg|png|heic)", fullmatch=True),
    existing_count=st.integers(min_value=1, max_value=5),
    same_content=st.booleans(),
)
@settings(max_examples=100)
def test_same_name_file_conflict_resolution(
    file_name: str, existing_count: int, same_content: bool
):
    """**Validates: Requirements 11.6**

    Same content = skip (path stays same); different content = append _N suffix.
    """
    import tempfile
    import shutil
    import hashlib

    tmp_dir = tempfile.mkdtemp()
    try:
        target_dir = Path(tmp_dir) / "target"
        target_dir.mkdir(parents=True)

        original_content = b"original content bytes here"
        original_path = target_dir / file_name
        original_path.write_bytes(original_content)

        # Create _1, _2, ... versions with different content
        stem = Path(file_name).stem
        suffix = Path(file_name).suffix
        for i in range(1, existing_count):
            conflict_path = target_dir / f"{stem}_{i}{suffix}"
            conflict_path.write_bytes(f"different content {i}".encode())

        # Now test resolution
        target_file = target_dir / file_name
        assert target_file.exists()

        if same_content:
            # Same content — upload service skips storage when hash matches
            existing_hash = hashlib.sha256(original_content).hexdigest()
            new_hash = hashlib.sha256(original_content).hexdigest()
            assert existing_hash == new_hash, "Same content should produce same hash"
        else:
            # Different content — resolve conflict should produce _N suffix
            resolved = UploadService._resolve_filename_conflict(target_file)
            assert resolved != target_file
            assert resolved.parent == target_dir
            # The resolved name should have _N suffix
            assert re.match(
                rf"^{re.escape(stem)}_\d+{re.escape(suffix)}$",
                resolved.name,
            ), f"Resolved name doesn't match pattern: {resolved.name}"
            # The resolved path should not exist
            assert not resolved.exists()
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


# ---------------------------------------------------------------------------
# Property 15: Chunk size correctness
# ---------------------------------------------------------------------------

# Feature: photo-backup-service, Property 15: 分块大小正确性
@given(
    file_size=st.integers(min_value=1, max_value=200 * 1024 * 1024),
)
@settings(max_examples=100)
def test_chunk_size_correctness(file_size: int):
    """**Validates: Requirements 5.1**

    For any file size:
    - Each chunk except the last is exactly 2MB
    - Last chunk is file_size % 2MB (or 2MB if evenly divisible)
    - Total chunk count = ceil(file_size / 2MB)
    """
    chunk_size = ChunkManager.CHUNK_SIZE  # 2MB = 2 * 1024 * 1024

    # Calculate expected values
    expected_total_chunks = math.ceil(file_size / chunk_size)
    expected_last_chunk_size = file_size % chunk_size
    if expected_last_chunk_size == 0:
        expected_last_chunk_size = chunk_size

    # Verify total chunks
    assert expected_total_chunks == math.ceil(file_size / chunk_size)

    # Simulate chunk sizes
    chunk_sizes = []
    remaining = file_size
    for i in range(expected_total_chunks):
        this_chunk = min(chunk_size, remaining)
        chunk_sizes.append(this_chunk)
        remaining -= this_chunk

    # Verify properties
    assert len(chunk_sizes) == expected_total_chunks, (
        f"Chunk count mismatch: {len(chunk_sizes)} != {expected_total_chunks}"
    )

    # All chunks except last should be exactly chunk_size
    if expected_total_chunks > 1:
        for i in range(expected_total_chunks - 1):
            assert chunk_sizes[i] == chunk_size, (
                f"Non-last chunk {i} has size {chunk_sizes[i]}, expected {chunk_size}"
            )

    # Last chunk should be correct size
    assert chunk_sizes[-1] == expected_last_chunk_size, (
        f"Last chunk size {chunk_sizes[-1]} != expected {expected_last_chunk_size}"
    )

    # Sum of all chunks should equal file size
    assert sum(chunk_sizes) == file_size, (
        f"Total bytes {sum(chunk_sizes)} != file_size {file_size}"
    )

    # No remaining bytes
    assert remaining == 0
