"""Integration tests for backup API endpoints.

Tests:
- POST /api/v1/backup/validate-path
"""

import os

import pytest
import aiosqlite
from httpx import AsyncClient, ASGITransport

from app.core.config import reset_settings
from app.core.database import init_db
from app.services.auth_service import AuthService


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
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-backup-tests"

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
    """Database with a test user already created."""
    auth_service = AuthService(db)
    await auth_service.create_user("alice", "password123", is_admin=False)
    return db


@pytest.fixture
async def auth_token(seeded_db):
    """Get an access token for the test user."""
    from app.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "password123"},
        )
    return response.json()["access_token"]


# ---------------------------------------------------------------------------
# POST /api/v1/backup/validate-path
# ---------------------------------------------------------------------------


class TestValidatePathEndpoint:
    """Tests for the validate-path endpoint."""

    @pytest.mark.asyncio
    async def test_validate_path_default_policy(self, auth_token):
        """Default policy (no custom path, no year/month) returns valid path."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": "/DCIM/Camera",
                    "device_name": "Pixel9Pro",
                    "storage_policy": {
                        "use_custom_path": False,
                        "custom_path": None,
                        "use_year_month_layer": False,
                    },
                },
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_valid"] is True
        assert "alice" in body["resolved_path"]
        assert "Pixel9Pro" in body["resolved_path"]
        assert "DCIM/Camera" in body["resolved_path"]
        assert body["error_message"] == ""

    @pytest.mark.asyncio
    async def test_validate_path_custom_path(self, auth_token):
        """Custom path policy returns valid path with custom base."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": "/DCIM/Camera",
                    "device_name": "Pixel9Pro",
                    "storage_policy": {
                        "use_custom_path": True,
                        "custom_path": "/data/alice/travel",
                        "use_year_month_layer": False,
                    },
                },
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_valid"] is True
        assert "/data/alice/travel" in body["resolved_path"]
        assert "DCIM/Camera" in body["resolved_path"]

    @pytest.mark.asyncio
    async def test_validate_path_year_month_layer(self, auth_token):
        """Year/month layer policy returns path with unknown_date (no metadata)."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": "/DCIM/Camera",
                    "device_name": "Pixel9Pro",
                    "storage_policy": {
                        "use_custom_path": False,
                        "custom_path": None,
                        "use_year_month_layer": True,
                    },
                },
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_valid"] is True
        # With empty FileMetadata, year/month falls back to unknown_date
        assert "unknown_date" in body["resolved_path"]

    @pytest.mark.asyncio
    async def test_validate_path_custom_with_year_month(self, auth_token):
        """Custom path + year/month layer combination works."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": "/DCIM/Camera",
                    "device_name": "Pixel9Pro",
                    "storage_policy": {
                        "use_custom_path": True,
                        "custom_path": "/data/alice/travel",
                        "use_year_month_layer": True,
                    },
                },
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_valid"] is True
        assert "/data/alice/travel" in body["resolved_path"]
        assert "unknown_date" in body["resolved_path"]

    @pytest.mark.asyncio
    async def test_validate_path_device_name_sanitized(self, auth_token):
        """Device name with special characters is sanitized in the path."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": "/DCIM/Camera",
                    "device_name": "My iPhone 15 Pro!",
                    "storage_policy": {
                        "use_custom_path": False,
                        "custom_path": None,
                        "use_year_month_layer": False,
                    },
                },
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_valid"] is True
        # Special chars replaced with underscore
        assert "My_iPhone_15_Pro" in body["resolved_path"]

    @pytest.mark.asyncio
    async def test_validate_path_invalid_path_too_long(self, auth_token):
        """Path exceeding max length returns invalid result."""
        from app.main import app

        # Create a source folder with a very long name to exceed 4096 chars
        long_folder = "/" + "a" * 4000
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": long_folder,
                    "device_name": "Pixel9Pro",
                    "storage_policy": {
                        "use_custom_path": False,
                        "custom_path": None,
                        "use_year_month_layer": False,
                    },
                },
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_valid"] is False
        assert body["error_message"] != ""

    @pytest.mark.asyncio
    async def test_validate_path_requires_auth(self, seeded_db):
        """Endpoint requires authentication."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": "/DCIM/Camera",
                    "device_name": "Pixel9Pro",
                    "storage_policy": {
                        "use_custom_path": False,
                        "custom_path": None,
                        "use_year_month_layer": False,
                    },
                },
            )

        assert response.status_code in (401, 403)

    @pytest.mark.asyncio
    async def test_validate_path_missing_fields(self, auth_token):
        """Missing required fields returns 422 validation error."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={"source_folder": "/DCIM/Camera"},
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 422

    @pytest.mark.asyncio
    async def test_validate_path_folder_name_too_long(self, auth_token):
        """Folder name exceeding 255 chars returns invalid result."""
        from app.main import app

        # Create a source folder with a single segment > 255 chars
        long_segment = "x" * 260
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/validate-path",
                json={
                    "source_folder": f"/{long_segment}",
                    "device_name": "Pixel9Pro",
                    "storage_policy": {
                        "use_custom_path": False,
                        "custom_path": None,
                        "use_year_month_layer": False,
                    },
                },
                headers={"Authorization": f"Bearer {auth_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_valid"] is False
        assert "255" in body["error_message"]


# ---------------------------------------------------------------------------
# File versioning, plan B: history never takes part in duplicate detection
# (acceptance 14, 14a, 14b, 15, 16, 17) and /backup/complete error mapping.
# ---------------------------------------------------------------------------

import hashlib  # noqa: E402
from datetime import datetime  # noqa: E402
from pathlib import Path  # noqa: E402

H1 = b"first version of IMG_0001" * 40
H2 = b"second version of IMG_0001 (vendor trailer)" * 40
ORIGIN = "content://media/external/images/media/1000042"


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class _Phone:
    """Calls the backup API in the order of the 1.5 Android client:
    check -> init -> chunks -> complete."""

    def __init__(self, client, token, *, device="Pixel9Pro", origin=ORIGIN, name="IMG_0001.jpg"):
        self.client = client
        self.headers = {"Authorization": f"Bearer {token}"}
        self.device = device
        self.origin = origin
        self.name = name

    async def check(self, content: bytes) -> dict:
        response = await self.client.post(
            "/api/v1/backup/check",
            json={"file_hash": _sha(content), "file_path": self.origin, "device_name": self.device},
            headers=self.headers,
        )
        assert response.status_code == 200
        return response.json()

    async def init(self, content: bytes) -> dict:
        response = await self.client.post(
            "/api/v1/backup/init",
            json={
                "file_hash": _sha(content),
                "file_name": self.name,
                "file_size": len(content),
                "file_path": self.origin,
                "device_name": self.device,
                "source_folder": "DCIM/Camera",
                "storage_policy": {
                    "use_custom_path": False,
                    "custom_path": None,
                    "use_year_month_layer": False,
                },
            },
            headers=self.headers,
        )
        assert response.status_code == 200
        return response.json()

    async def send_chunks(self, session: dict, content: bytes) -> None:
        size = session["chunk_size"]
        for index in range(session["total_chunks"]):
            data = content[index * size:(index + 1) * size]
            response = await self.client.post(
                "/api/v1/backup/chunk",
                data={
                    "session_id": session["session_id"],
                    "chunk_index": str(index),
                    "checksum": hashlib.md5(data).hexdigest(),
                },
                files={"file": ("chunk", data, "application/octet-stream")},
                headers=self.headers,
            )
            assert response.status_code == 200

    async def complete(self, session_id: str):
        return await self.client.post(
            "/api/v1/backup/complete", json={"session_id": session_id}, headers=self.headers
        )

    async def backup(self, content: bytes) -> dict:
        """The whole client flow; returns the complete response body."""
        check = await self.check(content)
        assert check["is_duplicate"] is False
        session = await self.init(content)
        assert session["is_duplicate"] is False and session["session_id"]
        await self.send_chunks(session, content)
        response = await self.complete(session["session_id"])
        assert response.status_code == 200, response.text
        return response.json()


async def _records(db) -> list:
    cursor = await db.execute("SELECT * FROM file_records ORDER BY id")
    return await cursor.fetchall()


async def _versions(db) -> list:
    cursor = await db.execute("SELECT * FROM file_versions ORDER BY id")
    return await cursor.fetchall()


class TestBackupVersioning:
    @pytest.fixture(autouse=True)
    def _presence_fds(self):
        from app.services import version_service as vs

        yield
        for fd in vs._presence_fds.values():
            try:
                os.close(fd)
            except OSError:
                pass
        vs._presence_fds.clear()

    @pytest.fixture
    async def phone(self, auth_token):
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            yield _Phone(client, auth_token)

    @pytest.fixture
    async def replaced_once(self, phone, seeded_db):
        """R uploaded as H1, then replaced by H2: H1 only exists as a version."""
        first = await phone.backup(H1)
        second = await phone.backup(H2)
        assert second["replaced"] is True and second["file_id"] == first["file_id"]
        versions = await _versions(seeded_db)
        assert [v["file_hash"] for v in versions] == [_sha(H1)]
        return first

    async def test_hash_only_in_versions_is_not_found(self, phone, replaced_once):
        """Acceptance 14."""
        check = await phone.check(H1)
        assert check == {
            "is_duplicate": False, "file_id": None, "status": "not_found", "expires_at": None
        }
        session = await phone.init(H1)
        assert session["is_duplicate"] is False and session["session_id"]
        assert session["total_chunks"] >= 1

    async def test_restore_to_old_content_replaces_again(self, phone, seeded_db, replaced_once):
        """Acceptance 14a and 17: same origin, 1.5 client order, replaced=true."""
        old_version = dict((await _versions(seeded_db))[0])
        body = await phone.backup(H1)
        assert body["replaced"] is True
        assert body["file_id"] == replaced_once["file_id"]
        assert body["stored_path"] == replaced_once["stored_path"]
        records = await _records(seeded_db)
        assert len(records) == 1
        assert (records[0]["file_hash"], records[0]["file_size"]) == (_sha(H1), len(H1))
        final_path = Path(records[0]["file_path"])
        assert final_path.read_bytes() == H1
        assert not final_path.with_name("IMG_0001_1.jpg").exists()
        versions = await _versions(seeded_db)
        assert len(versions) == 2
        assert dict(versions[0]) == old_version
        assert versions[1]["file_hash"] == _sha(H2) and versions[1]["file_size"] == len(H2)
        replaced_at = datetime.fromisoformat(versions[1]["replaced_at"])
        expires_at = datetime.fromisoformat(versions[1]["expires_at"])
        assert (expires_at - replaced_at).days == 30
        assert Path(versions[1]["stored_path"]).read_bytes() == H2

    @pytest.mark.parametrize("source", ["other_device", "other_origin"])
    async def test_other_source_is_a_new_file(self, phone, seeded_db, replaced_once, source):
        """Acceptance 14b and 17: different device / original_path."""
        before_record = dict((await _records(seeded_db))[0])
        before_versions = [dict(v) for v in await _versions(seeded_db)]
        if source == "other_device":
            phone.device = "OtherPhone"
        else:
            phone.origin = "content://media/external/images/media/2000001"
        body = await phone.backup(H1)
        assert body["replaced"] is False and body["file_id"] != replaced_once["file_id"]
        records = await _records(seeded_db)
        assert len(records) == 2
        assert dict(records[0]) == before_record
        assert [dict(v) for v in await _versions(seeded_db)] == before_versions
        if source == "other_device":
            assert "/OtherPhone/" in body["stored_path"]
            assert body["stored_path"].endswith("/IMG_0001.jpg")
        else:
            assert body["stored_path"].endswith("/IMG_0001_1.jpg")
        assert Path(body["stored_path"]).read_bytes() == H1

    async def test_active_and_versioned_hash_is_active(self, phone, seeded_db, replaced_once):
        """Acceptance 15: H1 is the current content and also a version."""
        await phone.backup(H1)
        assert _sha(H1) in {v["file_hash"] for v in await _versions(seeded_db)}
        check = await phone.check(H1)
        assert check == {
            "is_duplicate": True, "file_id": replaced_once["file_id"],
            "status": "active", "expires_at": None,
        }
        session = await phone.init(H1)
        assert session["is_duplicate"] is True and session["session_id"] == ""
        assert session["file_id"] == replaced_once["file_id"]

    async def test_versions_of_other_users_do_not_matter(self, phone, seeded_db, monkeypatch):
        """Acceptance 16: check/init are identical with or without these rows."""
        monkeypatch.delenv("PHOTOVAULT_MAX_USERS", raising=False)
        reset_settings()
        bob = await AuthService(seeded_db).create_user("bob", "password456", is_admin=False)
        bob_id = bob.id if hasattr(bob, "id") else bob["id"]
        mine = await phone.backup(H2)
        before = (await phone.check(H1), await phone.check(H2))

        stored = Path(os.environ["PHOTOVAULT_STORAGE_ROOT"]) / ".versions" / "99" / "x.jpg"
        cursor = await seeded_db.execute(
            """INSERT INTO file_records (user_id, file_hash, file_path, original_path,
               device_name, file_size, file_name)
               VALUES (?, ?, '/elsewhere/bob.jpg', 'content://b', 'BobPhone', 1, 'bob.jpg')""",
            (bob_id, _sha(b"bob current")),
        )
        for content in (H1, H2):
            await seeded_db.execute(
                """INSERT INTO file_versions (file_record_id, user_id, file_hash, file_size,
                   stored_path, replaced_at, expires_at)
                   VALUES (?, ?, ?, 1, ?, '2026-10-17T00:00:00+00:00',
                           '2099-11-16T00:00:00+00:00')""",
                (cursor.lastrowid, bob_id, _sha(content), f"{stored}.{len(content)}"),
            )
        await seeded_db.commit()

        assert (await phone.check(H1), await phone.check(H2)) == before
        assert before[1]["file_id"] == mine["file_id"]
        session = await phone.init(H1)
        assert session["is_duplicate"] is False and session["session_id"]
        session = await phone.init(H2)
        assert session["is_duplicate"] is True and session["file_id"] == mine["file_id"]

    @pytest.mark.parametrize(
        "error_code, error, status",
        [
            ("busy", "busy", 409),
            ("conflict", "conflict", 409),
            ("io", "I/O error during replacement", 500),
            ("db", "commit outcome unknown", 500),
            ("io", "I/O error: file not found", 500),
            (None, "Upload session not found or expired", 404),
            (None, "File integrity verification failed", 422),
            (None, "Not all chunks received: 0/1", 400),
        ],
    )
    async def test_complete_error_mapping(self, phone, monkeypatch, error_code, error, status):
        from app.services.upload_service import CompleteUploadResult, UploadService

        async def failing(self, session_id, user_id):
            return CompleteUploadResult(success=False, error=error, error_code=error_code)

        monkeypatch.setattr(UploadService, "complete_upload", failing)
        response = await phone.complete("any-session")
        assert response.status_code == status
        assert response.json()["detail"] == error

    async def test_busy_keeps_session_for_a_retry(self, phone, seeded_db, replaced_once, monkeypatch):
        """409 leaves the session and chunks; a later complete succeeds."""
        from app.services import version_service as vs

        session = await phone.init(H1)
        await phone.send_chunks(session, H1)
        real = vs.ensure_server_presence
        monkeypatch.setattr(vs, "ensure_server_presence", lambda media_root: False)
        response = await phone.complete(session["session_id"])
        assert response.status_code == 409
        monkeypatch.setattr(vs, "ensure_server_presence", real)
        response = await phone.complete(session["session_id"])
        assert response.status_code == 200 and response.json()["replaced"] is True

    async def test_replacement_does_not_reactivate_trashed_row_with_new_hash(
        self, phone, seeded_db, replaced_once
    ):
        """Only `not replaced and not is_duplicate` calls reactivate_record."""
        records = await _records(seeded_db)
        await seeded_db.execute(
            """INSERT INTO file_records (user_id, file_hash, file_path, original_path,
               device_name, file_size, file_name, deleted_at)
               VALUES (1, ?, '/elsewhere/.trash/x.jpg', 'content://x', 'Pixel9Pro', 1,
                       'x.jpg', '2026-10-01T00:00:00+00:00')""",
            (_sha(H1),),
        )
        await seeded_db.commit()
        check = await phone.check(H1)
        assert check["status"] == "trashed"
        session = await phone.init(H1)
        await phone.send_chunks(session, H1)
        response = await phone.complete(session["session_id"])
        assert response.json()["replaced"] is True
        cursor = await seeded_db.execute(
            "SELECT deleted_at FROM file_records WHERE file_path = '/elsewhere/.trash/x.jpg'"
        )
        assert (await cursor.fetchone())["deleted_at"] is not None
        assert records[0]["id"] == response.json()["file_id"]
