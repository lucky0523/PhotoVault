"""Tests for DeduplicationService and POST /api/v1/backup/check endpoint.

Tests cover:
- DeduplicationService.check() — user isolation, duplicate detection
- DeduplicationService.register_file() — new file registration
- DeduplicationService.create_reference() — reference creation for duplicates
- POST /api/v1/backup/check — API endpoint integration
"""

import os

import pytest
import aiosqlite
from httpx import AsyncClient, ASGITransport

from app.core.config import reset_settings
from app.core.database import init_db
from app.services.auth_service import AuthService
from app.services.deduplication_service import DeduplicationService, FileRecord


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
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-dedup-tests"

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
    """Database with two test users created."""
    auth_service = AuthService(db)
    await auth_service.create_user("alice", "password123", is_admin=False)
    await auth_service.create_user("bob", "password456", is_admin=False)
    return db


@pytest.fixture
async def auth_token_alice(seeded_db):
    """Get an access token for alice."""
    from app.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "password123"},
        )
    return response.json()["access_token"]


@pytest.fixture
async def auth_token_bob(seeded_db):
    """Get an access token for bob."""
    from app.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        response = await client.post(
            "/api/v1/auth/login",
            json={"username": "bob", "password": "password456"},
        )
    return response.json()["access_token"]


# ---------------------------------------------------------------------------
# Unit tests for DeduplicationService
# ---------------------------------------------------------------------------


class TestDeduplicationServiceCheck:
    """Tests for DeduplicationService.check()."""

    @pytest.mark.asyncio
    async def test_check_returns_none_when_no_records(self, seeded_db):
        """check() returns None when no file with the hash exists."""
        service = DeduplicationService(seeded_db)
        result = await service.check(user_id=1, file_hash="abc123hash")
        assert result is None

    @pytest.mark.asyncio
    async def test_check_returns_record_when_exists(self, seeded_db):
        """check() returns the FileRecord when a matching hash exists."""
        service = DeduplicationService(seeded_db)

        # Register a file first
        await service.register_file(
            user_id=1,
            file_hash="sha256_test_hash",
            file_path="/storage/alice/Pixel9Pro/DCIM/photo.jpg",
            original_path="/DCIM/Camera/photo.jpg",
            device_name="Pixel9Pro",
            file_size=1024000,
            file_name="photo.jpg",
            mime_type="image/jpeg",
        )

        # Now check should find it
        result = await service.check(user_id=1, file_hash="sha256_test_hash")
        assert result is not None
        assert isinstance(result, FileRecord)
        assert result.file_hash == "sha256_test_hash"
        assert result.user_id == 1
        assert result.file_name == "photo.jpg"

    @pytest.mark.asyncio
    async def test_check_user_isolation(self, seeded_db):
        """check() only finds records for the specified user (user isolation)."""
        service = DeduplicationService(seeded_db)

        # Register a file for alice (user_id=1)
        await service.register_file(
            user_id=1,
            file_hash="shared_hash_value",
            file_path="/storage/alice/device/photo.jpg",
            original_path="/DCIM/photo.jpg",
            device_name="Pixel9Pro",
            file_size=2048,
            file_name="photo.jpg",
        )

        # Bob (user_id=2) should NOT find alice's file
        result = await service.check(user_id=2, file_hash="shared_hash_value")
        assert result is None

        # Alice (user_id=1) should find her own file
        result = await service.check(user_id=1, file_hash="shared_hash_value")
        assert result is not None
        assert result.user_id == 1

    @pytest.mark.asyncio
    async def test_check_same_hash_different_path_still_found(self, seeded_db):
        """check() finds a record even if the original path differs."""
        service = DeduplicationService(seeded_db)

        await service.register_file(
            user_id=1,
            file_hash="duplicate_hash",
            file_path="/storage/alice/device/DCIM/Camera/img.jpg",
            original_path="/DCIM/Camera/img.jpg",
            device_name="Pixel9Pro",
            file_size=5000,
            file_name="img.jpg",
        )

        # Same hash, different conceptual path — should still be found
        result = await service.check(user_id=1, file_hash="duplicate_hash")
        assert result is not None
        assert result.file_hash == "duplicate_hash"


class TestDeduplicationServiceRegister:
    """Tests for DeduplicationService.register_file()."""

    @pytest.mark.asyncio
    async def test_register_file_creates_record(self, seeded_db):
        """register_file() creates a new file record and returns it."""
        service = DeduplicationService(seeded_db)

        record = await service.register_file(
            user_id=1,
            file_hash="new_file_hash",
            file_path="/storage/alice/Pixel9Pro/DCIM/new.jpg",
            original_path="/DCIM/Camera/new.jpg",
            device_name="Pixel9Pro",
            file_size=3072,
            file_name="new.jpg",
            mime_type="image/jpeg",
            exif_time="2026-03-15T10:30:00",
        )

        assert record.id is not None
        assert record.user_id == 1
        assert record.file_hash == "new_file_hash"
        assert record.file_path == "/storage/alice/Pixel9Pro/DCIM/new.jpg"
        assert record.original_path == "/DCIM/Camera/new.jpg"
        assert record.device_name == "Pixel9Pro"
        assert record.file_size == 3072
        assert record.file_name == "new.jpg"
        assert record.mime_type == "image/jpeg"
        assert record.exif_time == "2026-03-15T10:30:00"
        assert record.is_reference is False
        assert record.reference_to is None

    @pytest.mark.asyncio
    async def test_register_file_without_optional_fields(self, seeded_db):
        """register_file() works without optional mime_type and exif_time."""
        service = DeduplicationService(seeded_db)

        record = await service.register_file(
            user_id=1,
            file_hash="minimal_hash",
            file_path="/storage/alice/device/photo.png",
            original_path="/Pictures/photo.png",
            device_name="iPhone15",
            file_size=1024,
            file_name="photo.png",
        )

        assert record.id is not None
        assert record.mime_type is None
        assert record.exif_time is None

    @pytest.mark.asyncio
    async def test_register_file_unique_constraint(self, seeded_db):
        """register_file() raises error on duplicate (user_id, file_hash, file_path)."""
        service = DeduplicationService(seeded_db)

        await service.register_file(
            user_id=1,
            file_hash="unique_hash",
            file_path="/storage/alice/device/photo.jpg",
            original_path="/DCIM/photo.jpg",
            device_name="Pixel9Pro",
            file_size=1024,
            file_name="photo.jpg",
        )

        # Same user_id + file_hash + file_path should fail
        with pytest.raises(Exception):
            await service.register_file(
                user_id=1,
                file_hash="unique_hash",
                file_path="/storage/alice/device/photo.jpg",
                original_path="/DCIM/photo.jpg",
                device_name="Pixel9Pro",
                file_size=1024,
                file_name="photo.jpg",
            )


class TestDeduplicationServiceCreateReference:
    """Tests for DeduplicationService.create_reference()."""

    @pytest.mark.asyncio
    async def test_create_reference_marks_as_reference(self, seeded_db):
        """create_reference() creates a record with is_reference=True."""
        service = DeduplicationService(seeded_db)

        # First register the original file
        original = await service.register_file(
            user_id=1,
            file_hash="ref_test_hash",
            file_path="/storage/alice/Pixel9Pro/DCIM/Camera/photo.jpg",
            original_path="/DCIM/Camera/photo.jpg",
            device_name="Pixel9Pro",
            file_size=4096,
            file_name="photo.jpg",
        )

        # Create a reference to it
        reference = await service.create_reference(
            user_id=1,
            file_hash="ref_test_hash",
            target_path="/storage/alice/Pixel9Pro/Pictures/photo.jpg",
            source_record_id=original.id,
            original_path="/Pictures/photo.jpg",
            device_name="Pixel9Pro",
            file_size=4096,
            file_name="photo.jpg",
        )

        assert reference.id is not None
        assert reference.id != original.id
        assert reference.is_reference is True
        assert reference.reference_to == original.id
        assert reference.file_hash == "ref_test_hash"
        assert reference.file_path == "/storage/alice/Pixel9Pro/Pictures/photo.jpg"
        assert reference.user_id == 1

    @pytest.mark.asyncio
    async def test_create_reference_preserves_original(self, seeded_db):
        """create_reference() does not modify the original record."""
        service = DeduplicationService(seeded_db)

        original = await service.register_file(
            user_id=1,
            file_hash="preserve_test_hash",
            file_path="/storage/alice/device/original.jpg",
            original_path="/DCIM/original.jpg",
            device_name="Pixel9Pro",
            file_size=2048,
            file_name="original.jpg",
        )

        await service.create_reference(
            user_id=1,
            file_hash="preserve_test_hash",
            target_path="/storage/alice/device/copy.jpg",
            source_record_id=original.id,
            original_path="/Pictures/copy.jpg",
            device_name="Pixel9Pro",
            file_size=2048,
            file_name="copy.jpg",
        )

        # Verify original is unchanged
        result = await service.check(user_id=1, file_hash="preserve_test_hash")
        assert result is not None
        assert result.id == original.id
        assert result.is_reference is False
        assert result.reference_to is None


# ---------------------------------------------------------------------------
# Integration tests for POST /api/v1/backup/check
# ---------------------------------------------------------------------------


class TestCheckDuplicateEndpoint:
    """Tests for the POST /api/v1/backup/check endpoint."""

    @pytest.mark.asyncio
    async def test_check_no_duplicate(self, auth_token_alice):
        """Returns is_duplicate=False when file hash is not found."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/check",
                json={
                    "file_hash": "nonexistent_hash_value",
                    "file_path": "/DCIM/Camera/photo.jpg",
                    "device_name": "Pixel9Pro",
                },
                headers={"Authorization": f"Bearer {auth_token_alice}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_duplicate"] is False
        assert body["file_id"] is None

    @pytest.mark.asyncio
    async def test_check_duplicate_found(self, seeded_db, auth_token_alice):
        """Returns is_duplicate=True when file hash exists for the user."""
        # Register a file directly in the database for alice (user_id=1)
        service = DeduplicationService(seeded_db)
        record = await service.register_file(
            user_id=1,
            file_hash="existing_hash_abc",
            file_path="/storage/alice/Pixel9Pro/DCIM/photo.jpg",
            original_path="/DCIM/Camera/photo.jpg",
            device_name="Pixel9Pro",
            file_size=5000,
            file_name="photo.jpg",
        )

        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/check",
                json={
                    "file_hash": "existing_hash_abc",
                    "file_path": "/DCIM/Camera/photo.jpg",
                    "device_name": "Pixel9Pro",
                },
                headers={"Authorization": f"Bearer {auth_token_alice}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_duplicate"] is True
        assert body["file_id"] == record.id

    @pytest.mark.asyncio
    async def test_check_user_isolation_via_api(
        self, seeded_db, auth_token_alice, auth_token_bob
    ):
        """User B cannot see User A's files through the check endpoint."""
        # Register a file for alice (user_id=1)
        service = DeduplicationService(seeded_db)
        await service.register_file(
            user_id=1,
            file_hash="alice_only_hash",
            file_path="/storage/alice/device/photo.jpg",
            original_path="/DCIM/photo.jpg",
            device_name="Pixel9Pro",
            file_size=1024,
            file_name="photo.jpg",
        )

        from app.main import app

        transport = ASGITransport(app=app)

        # Bob should NOT see alice's file
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/check",
                json={
                    "file_hash": "alice_only_hash",
                    "file_path": "/DCIM/photo.jpg",
                    "device_name": "iPhone15",
                },
                headers={"Authorization": f"Bearer {auth_token_bob}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_duplicate"] is False
        assert body["file_id"] is None

    @pytest.mark.asyncio
    async def test_check_same_hash_different_path_is_duplicate(
        self, seeded_db, auth_token_alice
    ):
        """Same hash but different path still returns is_duplicate=True."""
        service = DeduplicationService(seeded_db)
        record = await service.register_file(
            user_id=1,
            file_hash="path_test_hash",
            file_path="/storage/alice/Pixel9Pro/DCIM/Camera/img.jpg",
            original_path="/DCIM/Camera/img.jpg",
            device_name="Pixel9Pro",
            file_size=2048,
            file_name="img.jpg",
        )

        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            # Check with a different file_path but same hash
            response = await client.post(
                "/api/v1/backup/check",
                json={
                    "file_hash": "path_test_hash",
                    "file_path": "/Pictures/img.jpg",  # different path
                    "device_name": "Pixel9Pro",
                },
                headers={"Authorization": f"Bearer {auth_token_alice}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["is_duplicate"] is True
        assert body["file_id"] == record.id

    @pytest.mark.asyncio
    async def test_check_requires_auth(self, seeded_db):
        """Endpoint requires authentication."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/check",
                json={
                    "file_hash": "some_hash",
                    "file_path": "/DCIM/photo.jpg",
                    "device_name": "Pixel9Pro",
                },
            )

        assert response.status_code in (401, 403)

    @pytest.mark.asyncio
    async def test_check_missing_fields(self, auth_token_alice):
        """Missing required fields returns 422 validation error."""
        from app.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://testserver") as client:
            response = await client.post(
                "/api/v1/backup/check",
                json={"file_hash": "some_hash"},
                headers={"Authorization": f"Bearer {auth_token_alice}"},
            )

        assert response.status_code == 422


# ---------------------------------------------------------------------------
# Re-backup: register_or_reactivate_file
# ---------------------------------------------------------------------------


class TestRegisterOrReactivate:
    """Tests for DeduplicationService.register_or_reactivate_file() (re-backup flow)."""

    @pytest.mark.asyncio
    async def test_reactivates_trashed_record_without_creating_duplicate(self, seeded_db):
        """A re-backup of a trashed file restores the SAME record (no duplicate row)."""
        service = DeduplicationService(seeded_db)

        original = await service.register_file(
            user_id=1,
            file_hash="rebackup_hash",
            file_path="/storage/alice/DCIM/photo.jpg",
            original_path="/DCIM/Camera/photo.jpg",
            device_name="Pixel9Pro",
            file_size=1000,
            file_name="photo.jpg",
        )

        # Trash it on the server.
        await seeded_db.execute(
            "UPDATE file_records SET deleted_at = '2024-01-01T00:00:00' WHERE id = ?",
            (original.id,),
        )
        await seeded_db.commit()
        # While trashed, check() must not see it.
        assert await service.check(user_id=1, file_hash="rebackup_hash") is None

        # Re-backup completes → reactivate.
        restored = await service.register_or_reactivate_file(
            user_id=1,
            file_hash="rebackup_hash",
            file_path="/storage/alice/DCIM/photo.jpg",
            original_path="/DCIM/Camera/photo.jpg",
            device_name="Pixel9Pro",
            file_size=1000,
            file_name="photo.jpg",
        )

        # Same record reused (no duplicate insert).
        assert restored.id == original.id
        cursor = await seeded_db.execute(
            "SELECT COUNT(*) AS n FROM file_records WHERE user_id = 1 AND file_hash = 'rebackup_hash'"
        )
        assert (await cursor.fetchone())["n"] == 1

        # Deletion markers cleared → check() now finds it as active.
        cursor = await seeded_db.execute(
            "SELECT deleted_at, purged_at FROM file_records WHERE id = ?", (original.id,)
        )
        row = await cursor.fetchone()
        assert row["deleted_at"] is None
        assert row["purged_at"] is None
        assert await service.check(user_id=1, file_hash="rebackup_hash") is not None

    @pytest.mark.asyncio
    async def test_reactivates_purged_record(self, seeded_db):
        """A re-backup of a purged file clears purged_at and refreshes the path."""
        service = DeduplicationService(seeded_db)

        original = await service.register_file(
            user_id=1,
            file_hash="purged_hash",
            file_path="/storage/alice/old.jpg",
            original_path="/DCIM/Camera/old.jpg",
            device_name="Pixel9Pro",
            file_size=2000,
            file_name="old.jpg",
        )
        await seeded_db.execute(
            "UPDATE file_records SET purged_at = '2024-02-02T00:00:00' WHERE id = ?",
            (original.id,),
        )
        await seeded_db.commit()

        restored = await service.register_or_reactivate_file(
            user_id=1,
            file_hash="purged_hash",
            file_path="/storage/alice/new.jpg",
            original_path="/DCIM/Camera/old.jpg",
            device_name="Pixel9Pro",
            file_size=2000,
            file_name="old.jpg",
        )

        assert restored.id == original.id
        assert restored.file_path == "/storage/alice/new.jpg"
        cursor = await seeded_db.execute(
            "SELECT purged_at FROM file_records WHERE id = ?", (original.id,)
        )
        assert (await cursor.fetchone())["purged_at"] is None

    @pytest.mark.asyncio
    async def test_inserts_new_record_when_none_exists(self, seeded_db):
        """With no prior record, it behaves like register_file (fresh insert)."""
        service = DeduplicationService(seeded_db)

        record = await service.register_or_reactivate_file(
            user_id=1,
            file_hash="fresh_hash",
            file_path="/storage/alice/fresh.jpg",
            original_path="/DCIM/Camera/fresh.jpg",
            device_name="Pixel9Pro",
            file_size=500,
            file_name="fresh.jpg",
        )

        assert record.id is not None
        assert await service.check(user_id=1, file_hash="fresh_hash") is not None
