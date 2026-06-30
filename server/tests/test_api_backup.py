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
