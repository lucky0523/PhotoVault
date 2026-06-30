"""Performance tests for PhotoVault concurrent upload scenarios.

Tests:
- 5 users concurrently uploading large files (configurable size)
- Measures wall-clock time, per-file average, and throughput
- Verifies file integrity (SHA-256) after upload

Environment variables:
- PERF_TEST_FILE_SIZE_MB: File size in MB per user (default: 10 for CI, 100 for manual)
"""

from __future__ import annotations

import asyncio
import hashlib
import math
import os
import time

import pytest
import aiosqlite
from httpx import AsyncClient, ASGITransport

from app.core.config import reset_settings
from app.core.database import init_db
from app.services.auth_service import AuthService
from app.services.chunk_manager import ChunkManager


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# Default 10MB for CI; set PERF_TEST_FILE_SIZE_MB=100 for manual performance runs
FILE_SIZE_MB = int(os.environ.get("PERF_TEST_FILE_SIZE_MB", "10"))
FILE_SIZE_BYTES = FILE_SIZE_MB * 1024 * 1024
NUM_USERS = 5


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_settings():
    """Reset settings singleton before each test."""
    reset_settings()
    yield
    reset_settings()


@pytest.fixture
async def db(tmp_path):
    """Create a temp database with schema initialized and configure env."""
    storage_root = str(tmp_path / "storage")
    os.makedirs(storage_root, exist_ok=True)

    os.environ["PHOTOVAULT_STORAGE_ROOT"] = storage_root
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-performance"

    reset_settings()

    db_path = str(tmp_path / "test.db")
    await init_db(db_path)

    conn = await aiosqlite.connect(db_path)
    await conn.execute("PRAGMA foreign_keys=ON;")
    conn.row_factory = aiosqlite.Row
    try:
        yield conn
    finally:
        await conn.close()

    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    reset_settings()


@pytest.fixture
async def perf_db(db):
    """Database with 5 test users created for performance testing."""
    auth_service = AuthService(db)
    for i in range(NUM_USERS):
        await auth_service.create_user(f"perfuser{i}", f"perfpass{i}", is_admin=False)
    return db


@pytest.fixture
def client():
    """Create an async test client for the FastAPI app."""
    from app.main import app

    transport = ASGITransport(app=app)
    return AsyncClient(transport=transport, base_url="http://testserver")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _generate_test_file(size: int, seed: int = 0) -> bytes:
    """Generate a test file of given size with a unique seed for variety.

    Uses os.urandom for realistic data patterns. The seed is used to make
    each user's file different by prepending a seed-based header.
    """
    # Create a unique header to ensure different hashes per user
    header = f"PerfTest-User{seed}-Size{size}-".encode()
    remaining = size - len(header)
    if remaining <= 0:
        return header[:size]
    # Use a deterministic repeating pattern for speed (urandom is slow for large sizes)
    pattern = bytes(range(256)) * 512  # 128KB pattern block
    repetitions = (remaining // len(pattern)) + 1
    body = (pattern * repetitions)[:remaining]
    return header + body


def _compute_sha256(data: bytes) -> str:
    """Compute SHA-256 hex digest of data."""
    return hashlib.sha256(data).hexdigest()


def _compute_md5(data: bytes) -> str:
    """Compute MD5 hex digest of data."""
    return hashlib.md5(data).hexdigest()


def _split_into_chunks(data: bytes, chunk_size: int = ChunkManager.CHUNK_SIZE) -> list[bytes]:
    """Split data into chunks of chunk_size bytes."""
    chunks = []
    for i in range(0, len(data), chunk_size):
        chunks.append(data[i : i + chunk_size])
    return chunks


async def _login(client: AsyncClient, username: str, password: str) -> dict:
    """Login and return the token response dict."""
    response = await client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": password},
    )
    assert response.status_code == 200, f"Login failed for {username}: {response.text}"
    return response.json()


async def _auth_headers(client: AsyncClient, username: str, password: str) -> dict:
    """Login and return authorization headers."""
    tokens = await _login(client, username, password)
    return {"Authorization": f"Bearer {tokens['access_token']}"}


async def _upload_file(
    client: AsyncClient,
    headers: dict,
    file_data: bytes,
    file_hash: str,
    file_name: str,
    user_label: str,
) -> dict:
    """Upload a complete file (init → chunks → complete) and return result with timing.

    Returns dict with keys: success, stored_path, file_id, duration_seconds.
    """
    chunk_size = ChunkManager.CHUNK_SIZE
    file_size = len(file_data)

    start_time = time.perf_counter()

    # Init upload
    init_resp = await client.post(
        "/api/v1/backup/init",
        json={
            "file_hash": file_hash,
            "file_name": file_name,
            "file_size": file_size,
            "file_path": f"/DCIM/Camera/{file_name}",
            "device_name": "PerfTestDevice",
            "source_folder": "/DCIM/Camera",
            "storage_policy": {
                "use_custom_path": False,
                "custom_path": None,
                "use_year_month_layer": False,
            },
        },
        headers=headers,
    )
    assert init_resp.status_code == 200, f"Init failed for {user_label}: {init_resp.text}"
    init_body = init_resp.json()
    session_id = init_body["session_id"]
    total_chunks = init_body["total_chunks"]

    # Upload all chunks
    chunks = _split_into_chunks(file_data, chunk_size)
    assert len(chunks) == total_chunks

    for i, chunk_data in enumerate(chunks):
        md5 = _compute_md5(chunk_data)
        resp = await client.post(
            "/api/v1/backup/chunk",
            data={
                "session_id": session_id,
                "chunk_index": str(i),
                "checksum": md5,
            },
            files={"file": ("chunk", chunk_data, "application/octet-stream")},
            headers=headers,
        )
        assert resp.status_code == 200, (
            f"Chunk {i} failed for {user_label}: {resp.text}"
        )

    # Complete upload
    complete_resp = await client.post(
        "/api/v1/backup/complete",
        json={"session_id": session_id},
        headers=headers,
    )
    assert complete_resp.status_code == 200, (
        f"Complete failed for {user_label}: {complete_resp.text}"
    )

    duration = time.perf_counter() - start_time
    complete_body = complete_resp.json()
    complete_body["duration_seconds"] = duration
    return complete_body


# ---------------------------------------------------------------------------
# Performance Test
# ---------------------------------------------------------------------------


@pytest.mark.slow
@pytest.mark.performance
class TestConcurrentUploadPerformance:
    """Performance test: 5 users concurrently uploading large files."""

    async def test_concurrent_upload_5_users(self, perf_db, client: AsyncClient):
        """Simulate 5 users concurrently uploading files.

        Measures:
        - Total wall-clock time
        - Average per-file upload time
        - Total throughput (MB/s)

        Verifies:
        - All uploads succeed (success=True)
        - File integrity via SHA-256 match
        """
        # --- Setup: Generate file data and authenticate users ---
        users = []
        for i in range(NUM_USERS):
            username = f"perfuser{i}"
            password = f"perfpass{i}"
            headers = await _auth_headers(client, username, password)

            file_data = _generate_test_file(FILE_SIZE_BYTES, seed=i)
            file_hash = _compute_sha256(file_data)
            file_name = f"perf_upload_user{i}.jpg"

            users.append({
                "username": username,
                "headers": headers,
                "file_data": file_data,
                "file_hash": file_hash,
                "file_name": file_name,
            })

        # --- Execute: All 5 users upload concurrently ---
        wall_clock_start = time.perf_counter()

        results = await asyncio.gather(
            *[
                _upload_file(
                    client,
                    user["headers"],
                    user["file_data"],
                    user["file_hash"],
                    user["file_name"],
                    user["username"],
                )
                for user in users
            ]
        )

        wall_clock_total = time.perf_counter() - wall_clock_start

        # --- Verify: All uploads succeeded ---
        for i, result in enumerate(results):
            assert result["success"] is True, (
                f"Upload failed for perfuser{i}: {result}"
            )

        # --- Verify: File integrity (SHA-256 match) ---
        for i, (result, user) in enumerate(zip(results, users)):
            stored_path = result["stored_path"]
            assert os.path.isfile(stored_path), (
                f"Stored file not found for perfuser{i}: {stored_path}"
            )
            with open(stored_path, "rb") as f:
                stored_hash = _compute_sha256(f.read())
            assert stored_hash == user["file_hash"], (
                f"SHA-256 mismatch for perfuser{i}: "
                f"expected {user['file_hash']}, got {stored_hash}"
            )

        # --- Report: Timing metrics ---
        individual_durations = [r["duration_seconds"] for r in results]
        avg_duration = sum(individual_durations) / len(individual_durations)
        total_data_mb = (FILE_SIZE_MB * NUM_USERS)
        throughput_mbs = total_data_mb / wall_clock_total if wall_clock_total > 0 else 0

        print("\n" + "=" * 60)
        print("PERFORMANCE TEST RESULTS")
        print("=" * 60)
        print(f"  File size per user:      {FILE_SIZE_MB} MB")
        print(f"  Number of users:         {NUM_USERS}")
        print(f"  Total data uploaded:     {total_data_mb} MB")
        print(f"  Wall-clock time:         {wall_clock_total:.2f} s")
        print(f"  Avg per-file time:       {avg_duration:.2f} s")
        print(f"  Total throughput:        {throughput_mbs:.2f} MB/s")
        print("-" * 60)
        for i, dur in enumerate(individual_durations):
            print(f"  User {i} upload time:     {dur:.2f} s")
        print("=" * 60)
