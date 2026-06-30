"""Tests for setup/initialization API endpoints.

Tests:
- GET  /api/v1/setup/status
- POST /api/v1/setup/init
- Setup check middleware (503 when not initialized)
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
    """Create a temp database with schema initialized (no users)."""
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-setup-tests"

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
async def initialized_db(db):
    """Database with an admin user already created (system initialized)."""
    auth_service = AuthService(db)
    await auth_service.create_user("admin", "adminpass123", is_admin=True)
    return db


@pytest.fixture
def async_client():
    """Create an async test client for the FastAPI app."""
    from app.main import app

    transport = ASGITransport(app=app)
    return AsyncClient(transport=transport, base_url="http://testserver")


# ---------------------------------------------------------------------------
# GET /api/v1/setup/status
# ---------------------------------------------------------------------------


class TestSetupStatus:
    """Tests for the setup status endpoint."""

    @pytest.mark.asyncio
    async def test_status_not_initialized(self, db, async_client):
        """Returns initialized=false when no users exist."""
        async with async_client as client:
            response = await client.get("/api/v1/setup/status")

        assert response.status_code == 200
        body = response.json()
        assert body["initialized"] is False

    @pytest.mark.asyncio
    async def test_status_initialized(self, initialized_db, async_client):
        """Returns initialized=true when users exist."""
        async with async_client as client:
            response = await client.get("/api/v1/setup/status")

        assert response.status_code == 200
        body = response.json()
        assert body["initialized"] is True

    @pytest.mark.asyncio
    async def test_status_no_auth_required(self, db, async_client):
        """Setup status endpoint does not require authentication."""
        async with async_client as client:
            response = await client.get("/api/v1/setup/status")

        assert response.status_code == 200


# ---------------------------------------------------------------------------
# POST /api/v1/setup/init
# ---------------------------------------------------------------------------


class TestSetupInit:
    """Tests for the initial admin account creation endpoint."""

    @pytest.mark.asyncio
    async def test_init_success(self, db, async_client):
        """Creating initial admin succeeds with valid input."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/setup/init",
                json={"username": "myadmin", "password": "securepass123"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["success"] is True
        assert body["username"] == "myadmin"

    @pytest.mark.asyncio
    async def test_init_creates_admin_user(self, db, async_client):
        """Init endpoint creates a user with is_admin=True."""
        async with async_client as client:
            await client.post(
                "/api/v1/setup/init",
                json={"username": "myadmin", "password": "securepass123"},
            )

        # Verify user in DB is admin
        cursor = await db.execute(
            "SELECT is_admin FROM users WHERE username = ?", ("myadmin",)
        )
        row = await cursor.fetchone()
        assert row is not None
        assert row[0] == 1  # is_admin = True

    @pytest.mark.asyncio
    async def test_init_already_initialized_returns_403(
        self, initialized_db, async_client
    ):
        """Returns 403 when system is already initialized."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/setup/init",
                json={"username": "another", "password": "longpassword"},
            )

        assert response.status_code == 403
        body = response.json()
        assert "already initialized" in body["detail"].lower()

    @pytest.mark.asyncio
    async def test_init_empty_username_rejected(self, db, async_client):
        """Empty username returns 422 validation error."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/setup/init",
                json={"username": "   ", "password": "securepass123"},
            )

        assert response.status_code == 422

    @pytest.mark.asyncio
    async def test_init_short_password_rejected(self, db, async_client):
        """Password shorter than 8 characters returns 422."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/setup/init",
                json={"username": "admin", "password": "short"},
            )

        assert response.status_code == 422

    @pytest.mark.asyncio
    async def test_init_password_exactly_8_chars(self, db, async_client):
        """Password with exactly 8 characters succeeds."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/setup/init",
                json={"username": "admin", "password": "12345678"},
            )

        assert response.status_code == 200
        assert response.json()["success"] is True

    @pytest.mark.asyncio
    async def test_init_second_call_fails(self, db, async_client):
        """Second init call after first success returns 403."""
        async with async_client as client:
            # First call succeeds
            resp1 = await client.post(
                "/api/v1/setup/init",
                json={"username": "admin", "password": "securepass123"},
            )
            assert resp1.status_code == 200

            # Second call fails
            resp2 = await client.post(
                "/api/v1/setup/init",
                json={"username": "admin2", "password": "securepass456"},
            )
            assert resp2.status_code == 403


# ---------------------------------------------------------------------------
# Setup check middleware
# ---------------------------------------------------------------------------


class TestSetupMiddleware:
    """Tests for the setup check middleware (503 when not initialized)."""

    @pytest.mark.asyncio
    async def test_non_setup_api_returns_503_when_not_initialized(
        self, db, async_client
    ):
        """Non-setup API endpoints return 503 when system is not initialized."""
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 503
        body = response.json()
        assert "not initialized" in body["detail"].lower()

    @pytest.mark.asyncio
    async def test_setup_endpoints_work_when_not_initialized(
        self, db, async_client
    ):
        """Setup endpoints work normally when system is not initialized."""
        async with async_client as client:
            response = await client.get("/api/v1/setup/status")

        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_health_check_works_when_not_initialized(
        self, db, async_client
    ):
        """Health check endpoint is exempt from the setup check."""
        async with async_client as client:
            response = await client.get("/api/v1/health")

        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_non_setup_api_works_after_initialization(
        self, initialized_db, async_client
    ):
        """Non-setup API endpoints work after system is initialized."""
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "ok"

    @pytest.mark.asyncio
    async def test_auth_endpoint_returns_503_when_not_initialized(
        self, db, async_client
    ):
        """Auth login endpoint returns 503 when not initialized."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/auth/login",
                json={"username": "any", "password": "anypass123"},
            )

        assert response.status_code == 503
