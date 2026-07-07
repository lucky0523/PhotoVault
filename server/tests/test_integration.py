"""Integration tests for PhotoVault end-to-end user flows.

Tests:
- Complete upload flow (login → init → chunks → complete → verify)
- Resume upload after interruption
- Authentication flow (login, token usage, refresh, invalid tokens)
- Multi-user concurrent upload isolation
"""

from __future__ import annotations

import asyncio
import hashlib
import math
import os

import pytest
import aiosqlite
from httpx import AsyncClient, ASGITransport

from app.core.config import reset_settings
from app.core.database import init_db
from app.services.auth_service import AuthService
from app.services.chunk_manager import ChunkManager


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
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-integration"

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
async def seeded_db(db):
    """Database with test users already created."""
    auth_service = AuthService(db)
    await auth_service.create_user("alice", "password123", is_admin=False)
    await auth_service.create_user("bob", "bobpass456", is_admin=False)
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


def _generate_test_file(size: int) -> bytes:
    """Generate deterministic test file data of a given size."""
    # Use a repeating pattern to get deterministic content
    pattern = b"PhotoVault-test-data-chunk-" + b"X" * 100
    repetitions = (size // len(pattern)) + 1
    return (pattern * repetitions)[:size]


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
    assert response.status_code == 200, f"Login failed: {response.text}"
    return response.json()


async def _auth_headers(client: AsyncClient, username: str, password: str) -> dict:
    """Login and return authorization headers."""
    tokens = await _login(client, username, password)
    return {"Authorization": f"Bearer {tokens['access_token']}"}


# ---------------------------------------------------------------------------
# Test 1: Complete Upload Flow
# ---------------------------------------------------------------------------


class TestFullUploadFlow:
    """Test complete upload flow: login → init → chunks → complete → verify."""

    async def test_full_upload_flow(self, seeded_db, client: AsyncClient):
        """End-to-end upload: create file, upload all chunks, verify completion."""
        # Generate a 5MB test file
        file_size = 5 * 1024 * 1024  # 5MB
        file_data = _generate_test_file(file_size)
        file_hash = _compute_sha256(file_data)
        file_name = "test_photo.jpg"

        # Step 1: Login
        headers = await _auth_headers(client, "alice", "password123")

        # Step 2: Initialize upload
        init_response = await client.post(
            "/api/v1/backup/init",
            json={
                "file_hash": file_hash,
                "file_name": file_name,
                "file_size": file_size,
                "file_path": "/DCIM/Camera/test_photo.jpg",
                "device_name": "Pixel9Pro",
                "source_folder": "/DCIM/Camera",
                "storage_policy": {
                    "use_custom_path": False,
                    "custom_path": None,
                    "use_year_month_layer": False,
                },
            },
            headers=headers,
        )
        assert init_response.status_code == 200
        init_body = init_response.json()
        assert init_body["is_duplicate"] is False
        session_id = init_body["session_id"]
        total_chunks = init_body["total_chunks"]
        chunk_size = init_body["chunk_size"]
        assert session_id != ""
        assert total_chunks == math.ceil(file_size / chunk_size)

        # Step 3: Upload all chunks
        chunks = _split_into_chunks(file_data, chunk_size)
        assert len(chunks) == total_chunks

        for i, chunk_data in enumerate(chunks):
            md5 = _compute_md5(chunk_data)
            chunk_response = await client.post(
                "/api/v1/backup/chunk",
                data={
                    "session_id": session_id,
                    "chunk_index": str(i),
                    "checksum": md5,
                },
                files={"file": ("chunk", chunk_data, "application/octet-stream")},
                headers=headers,
            )
            assert chunk_response.status_code == 200
            chunk_body = chunk_response.json()
            assert chunk_body["chunk_index"] == i
            assert chunk_body["received"] is True

        # Step 4: Complete upload
        complete_response = await client.post(
            "/api/v1/backup/complete",
            json={"session_id": session_id},
            headers=headers,
        )
        assert complete_response.status_code == 200
        complete_body = complete_response.json()
        assert complete_body["success"] is True
        assert complete_body["stored_path"] != ""
        assert complete_body["file_id"] is not None

        # Step 5: Verify file exists on disk
        stored_path = complete_body["stored_path"]
        assert os.path.isfile(stored_path)
        # Verify content integrity
        with open(stored_path, "rb") as f:
            stored_data = f.read()
        assert _compute_sha256(stored_data) == file_hash

        # Step 6: Verify duplicate check now returns is_duplicate=True
        dup_response = await client.post(
            "/api/v1/backup/check",
            json={
                "file_hash": file_hash,
                "file_path": "/DCIM/Camera/test_photo.jpg",
                "device_name": "Pixel9Pro",
            },
            headers=headers,
        )
        assert dup_response.status_code == 200
        dup_body = dup_response.json()
        assert dup_body["is_duplicate"] is True
        assert dup_body["file_id"] is not None


# ---------------------------------------------------------------------------
# Test 2: Resume Upload After Interruption
# ---------------------------------------------------------------------------


class TestResumeUploadFlow:
    """Test resume after interruption: upload partial → resume → complete."""

    async def test_resume_upload_flow(self, seeded_db, client: AsyncClient):
        """Upload 2 of 3 chunks, call resume, upload remaining, complete."""
        # Create a file that splits into exactly 3 chunks
        chunk_size = ChunkManager.CHUNK_SIZE  # 2MB
        file_size = chunk_size * 3 - 100  # slightly less than 3 full chunks → 3 chunks
        # Actually, let's make it exactly 3 chunks
        file_size = chunk_size * 2 + chunk_size // 2  # 2.5 chunks → ceil = 3 chunks
        file_data = _generate_test_file(file_size)
        file_hash = _compute_sha256(file_data)
        file_name = "resume_test.jpg"

        total_expected_chunks = math.ceil(file_size / chunk_size)
        assert total_expected_chunks == 3

        # Login
        headers = await _auth_headers(client, "alice", "password123")

        # Initialize upload
        init_response = await client.post(
            "/api/v1/backup/init",
            json={
                "file_hash": file_hash,
                "file_name": file_name,
                "file_size": file_size,
                "file_path": "/DCIM/Camera/resume_test.jpg",
                "device_name": "Pixel9Pro",
                "source_folder": "/DCIM/Camera",
                "storage_policy": {
                    "use_custom_path": False,
                    "custom_path": None,
                    "use_year_month_layer": False,
                },
            },
            headers=headers,
        )
        assert init_response.status_code == 200
        init_body = init_response.json()
        session_id = init_body["session_id"]
        assert init_body["total_chunks"] == 3

        # Upload only chunks 0 and 1 (simulate interruption before chunk 2)
        chunks = _split_into_chunks(file_data, chunk_size)
        for i in range(2):  # Only upload chunks 0 and 1
            md5 = _compute_md5(chunks[i])
            chunk_response = await client.post(
                "/api/v1/backup/chunk",
                data={
                    "session_id": session_id,
                    "chunk_index": str(i),
                    "checksum": md5,
                },
                files={"file": ("chunk", chunks[i], "application/octet-stream")},
                headers=headers,
            )
            assert chunk_response.status_code == 200

        # --- Simulate interruption ---
        # Client reconnects and calls resume endpoint
        resume_response = await client.get(
            f"/api/v1/backup/resume/{session_id}",
            headers=headers,
        )
        assert resume_response.status_code == 200
        resume_body = resume_response.json()
        assert resume_body["session_id"] == session_id
        assert resume_body["received_chunks"] == [0, 1]
        assert resume_body["total_chunks"] == 3
        assert resume_body["file_hash"] == file_hash

        # Upload remaining chunk (chunk 2)
        md5 = _compute_md5(chunks[2])
        chunk_response = await client.post(
            "/api/v1/backup/chunk",
            data={
                "session_id": session_id,
                "chunk_index": "2",
                "checksum": md5,
            },
            files={"file": ("chunk", chunks[2], "application/octet-stream")},
            headers=headers,
        )
        assert chunk_response.status_code == 200

        # Complete upload
        complete_response = await client.post(
            "/api/v1/backup/complete",
            json={"session_id": session_id},
            headers=headers,
        )
        assert complete_response.status_code == 200
        complete_body = complete_response.json()
        assert complete_body["success"] is True
        assert complete_body["stored_path"] != ""

        # Verify stored file integrity
        stored_path = complete_body["stored_path"]
        assert os.path.isfile(stored_path)
        with open(stored_path, "rb") as f:
            stored_data = f.read()
        assert _compute_sha256(stored_data) == file_hash


# ---------------------------------------------------------------------------
# Test 3: Authentication Flow
# ---------------------------------------------------------------------------


class TestAuthFlow:
    """Test authentication: login, token usage, refresh, invalid tokens."""

    async def test_auth_flow(self, seeded_db, client: AsyncClient):
        """Full auth lifecycle: login → use token → refresh → invalid token → 401."""
        # Step 1: Login with valid credentials → get access/refresh tokens
        login_response = await client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "password123"},
        )
        assert login_response.status_code == 200
        tokens = login_response.json()
        assert "access_token" in tokens
        assert "refresh_token" in tokens
        assert "expires_in" in tokens
        assert tokens["expires_in"] > 0

        access_token = tokens["access_token"]
        refresh_token = tokens["refresh_token"]

        # Step 2: Use access token for protected endpoint → success
        headers = {"Authorization": f"Bearer {access_token}"}
        protected_response = await client.get(
            "/api/v1/files/browse",
            headers=headers,
        )
        assert protected_response.status_code == 200

        # Step 3: Use refresh token → get new tokens
        refresh_response = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": refresh_token},
        )
        assert refresh_response.status_code == 200
        new_tokens = refresh_response.json()
        assert "access_token" in new_tokens
        assert "refresh_token" in new_tokens
        new_access_token = new_tokens["access_token"]

        # New token also works
        new_headers = {"Authorization": f"Bearer {new_access_token}"}
        protected_response2 = await client.get(
            "/api/v1/files/browse",
            headers=new_headers,
        )
        assert protected_response2.status_code == 200

        # Step 4: Use invalid/garbage token → get 401
        invalid_headers = {"Authorization": "Bearer invalid-garbage-token-xyz"}
        invalid_response = await client.get(
            "/api/v1/files/browse",
            headers=invalid_headers,
        )
        assert invalid_response.status_code == 401

        # Step 5: Use expired/fabricated refresh token → get 401
        expired_refresh_response = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": "not-a-real-refresh-token"},
        )
        assert expired_refresh_response.status_code == 401

    async def test_login_invalid_credentials(self, seeded_db, client: AsyncClient):
        """Login with wrong password returns 401."""
        response = await client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "wrongpassword"},
        )
        assert response.status_code == 401

    async def test_login_nonexistent_user(self, seeded_db, client: AsyncClient):
        """Login with non-existent user returns 401."""
        response = await client.post(
            "/api/v1/auth/login",
            json={"username": "nonexistent", "password": "password123"},
        )
        assert response.status_code == 401

    async def test_no_auth_header_returns_401_or_403(self, seeded_db, client: AsyncClient):
        """Protected endpoint without auth header returns 401 or 403."""
        response = await client.get("/api/v1/files/browse")
        assert response.status_code in (401, 403)


# ---------------------------------------------------------------------------
# Test 4: Multi-User Concurrent Upload Isolation
# ---------------------------------------------------------------------------


class TestMultiUserIsolation:
    """Test multi-user concurrent uploads and file isolation."""

    async def test_multi_user_isolation(self, seeded_db, client: AsyncClient):
        """Two users upload concurrently; each can only see their own files."""
        # Prepare file data for each user
        file_data_alice = _generate_test_file(ChunkManager.CHUNK_SIZE + 100)  # 1 chunk + a bit
        file_hash_alice = _compute_sha256(file_data_alice)

        file_data_bob = _generate_test_file(ChunkManager.CHUNK_SIZE + 200)  # different content
        file_hash_bob = _compute_sha256(file_data_bob)

        # Login both users
        headers_alice = await _auth_headers(client, "alice", "password123")
        headers_bob = await _auth_headers(client, "bob", "bobpass456")

        # Define upload coroutine
        async def upload_file(
            headers: dict, file_data: bytes, file_hash: str, file_name: str
        ) -> dict:
            """Upload a file and return the complete response."""
            chunk_size = ChunkManager.CHUNK_SIZE
            file_size = len(file_data)

            # Init upload
            init_resp = await client.post(
                "/api/v1/backup/init",
                json={
                    "file_hash": file_hash,
                    "file_name": file_name,
                    "file_size": file_size,
                    "file_path": f"/DCIM/Camera/{file_name}",
                    "device_name": "TestDevice",
                    "source_folder": "/DCIM/Camera",
                    "storage_policy": {
                        "use_custom_path": False,
                        "custom_path": None,
                        "use_year_month_layer": False,
                    },
                },
                headers=headers,
            )
            assert init_resp.status_code == 200
            init_body = init_resp.json()
            session_id = init_body["session_id"]
            total_chunks = init_body["total_chunks"]

            # Upload all chunks
            chunks = _split_into_chunks(file_data, chunk_size)
            for i, chunk in enumerate(chunks):
                md5 = _compute_md5(chunk)
                resp = await client.post(
                    "/api/v1/backup/chunk",
                    data={
                        "session_id": session_id,
                        "chunk_index": str(i),
                        "checksum": md5,
                    },
                    files={"file": ("chunk", chunk, "application/octet-stream")},
                    headers=headers,
                )
                assert resp.status_code == 200

            # Complete upload
            complete_resp = await client.post(
                "/api/v1/backup/complete",
                json={"session_id": session_id},
                headers=headers,
            )
            assert complete_resp.status_code == 200
            return complete_resp.json()

        # Upload concurrently using asyncio.gather
        result_alice, result_bob = await asyncio.gather(
            upload_file(headers_alice, file_data_alice, file_hash_alice, "alice_photo.jpg"),
            upload_file(headers_bob, file_data_bob, file_hash_bob, "bob_photo.jpg"),
        )

        assert result_alice["success"] is True
        assert result_bob["success"] is True

        # Verify isolation: Alice can see her files but not Bob's
        browse_alice = await client.get(
            "/api/v1/files/browse",
            params={"path": ""},
            headers=headers_alice,
        )
        assert browse_alice.status_code == 200
        alice_browse_data = browse_alice.json()

        browse_bob = await client.get(
            "/api/v1/files/browse",
            params={"path": ""},
            headers=headers_bob,
        )
        assert browse_bob.status_code == 200
        bob_browse_data = browse_bob.json()

        # Get all file names visible to each user (recursively browse)
        alice_files = await self._get_all_files(client, headers_alice)
        bob_files = await self._get_all_files(client, headers_bob)

        # Alice should have alice_photo.jpg but not bob_photo.jpg
        alice_file_names = [f["file_name"] for f in alice_files]
        assert "alice_photo.jpg" in alice_file_names
        assert "bob_photo.jpg" not in alice_file_names

        # Bob should have bob_photo.jpg but not alice_photo.jpg
        bob_file_names = [f["file_name"] for f in bob_files]
        assert "bob_photo.jpg" in bob_file_names
        assert "alice_photo.jpg" not in bob_file_names

    async def _get_all_files(self, client: AsyncClient, headers: dict) -> list[dict]:
        """Recursively browse and collect all files for a user."""
        all_files = []
        dirs_to_visit = [""]

        while dirs_to_visit:
            path = dirs_to_visit.pop()
            response = await client.get(
                "/api/v1/files/browse",
                params={"path": path},
                headers=headers,
            )
            if response.status_code != 200:
                continue
            data = response.json()
            all_files.extend(data.get("files", []))
            for d in data.get("directories", []):
                dirs_to_visit.append(d["path"])

        return all_files


# ---------------------------------------------------------------------------
# Test: Re-upload a purged file restores it (re-backup end-to-end)
# ---------------------------------------------------------------------------


class TestRebackupPurgedFile:
    """A purged file re-uploaded from the phone must be restored to active."""

    async def _upload(self, client, headers, file_data, file_hash, file_name):
        init = await client.post(
            "/api/v1/backup/init",
            json={
                "file_hash": file_hash,
                "file_name": file_name,
                "file_size": len(file_data),
                "file_path": f"/DCIM/Camera/{file_name}",
                "device_name": "Pixel9Pro",
                "source_folder": "/DCIM/Camera",
                "storage_policy": {
                    "use_custom_path": False,
                    "custom_path": None,
                    "use_year_month_layer": False,
                },
            },
            headers=headers,
        )
        assert init.status_code == 200, init.text
        body = init.json()
        # A purged file is not an active duplicate → must allow a fresh session.
        assert body["is_duplicate"] is False, body
        session_id = body["session_id"]
        chunk_size = body["chunk_size"]

        for i, chunk in enumerate(_split_into_chunks(file_data, chunk_size)):
            resp = await client.post(
                "/api/v1/backup/chunk",
                data={"session_id": session_id, "chunk_index": str(i), "checksum": _compute_md5(chunk)},
                files={"file": ("chunk", chunk, "application/octet-stream")},
                headers=headers,
            )
            assert resp.status_code == 200, resp.text

        complete = await client.post(
            "/api/v1/backup/complete",
            json={"session_id": session_id},
            headers=headers,
        )
        assert complete.status_code == 200, complete.text
        return complete.json()

    async def test_reupload_after_purge_restores_record_and_file(self, seeded_db, client: AsyncClient):
        from app.core.config import get_settings
        from app.services.file_browse_service import FileBrowseService

        file_data = _generate_test_file(3 * 1024 * 1024)
        file_hash = _compute_sha256(file_data)
        file_name = "MVIMG_20260707_005345.jpg"

        async with client:
            headers = await _auth_headers(client, "alice", "password123")

            # 1) Initial backup.
            first = await self._upload(client, headers, file_data, file_hash, file_name)
            file_id = first["file_id"]
            assert os.path.isfile(first["stored_path"])

            settings = get_settings()
            service = FileBrowseService(seeded_db, settings.storage_root, settings.trash_retention_days)

            # 2) Trash then purge on the server (physical file deleted, record kept).
            ok, msg = await service.delete_file(user_id=1, file_id=file_id)
            assert ok, msg
            ok, msg = await service.purge_file(user_id=1, file_id=file_id)
            assert ok, msg

            cursor = await seeded_db.execute(
                "SELECT file_path, deleted_at, purged_at FROM file_records WHERE id = ?", (file_id,)
            )
            purged = await cursor.fetchone()
            assert purged["purged_at"] is not None
            assert not os.path.isfile(purged["file_path"])  # physical file gone

            # 3) Re-upload the same file from the phone (re-backup).
            again = await self._upload(client, headers, file_data, file_hash, file_name)

            # 4) The record must be restored to active (no duplicate row), with the
            #    physical file back at a normal (non-.trash) path.
            cursor = await seeded_db.execute(
                "SELECT id, file_path, deleted_at, purged_at FROM file_records WHERE user_id = 1 AND file_hash = ?",
                (file_hash,),
            )
            rows = await cursor.fetchall()
            assert len(rows) == 1, f"expected a single record, got {len(rows)}"
            rec = rows[0]
            assert rec["deleted_at"] is None, "deleted_at must be cleared on re-backup"
            assert rec["purged_at"] is None, "purged_at must be cleared on re-backup"
            assert ".trash" not in rec["file_path"], f"file_path must point to a live location: {rec['file_path']}"
            assert os.path.isfile(rec["file_path"]), "physical file must exist after re-backup"

            # 5) A subsequent duplicate check now reports active.
            dup = await client.post(
                "/api/v1/backup/check",
                json={"file_hash": file_hash, "file_path": f"/DCIM/Camera/{file_name}", "device_name": "Pixel9Pro"},
                headers=headers,
            )
            assert dup.status_code == 200
            assert dup.json()["is_duplicate"] is True
            assert dup.json()["status"] == "active"


