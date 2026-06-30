"""Integration tests for authentication API endpoints.

Tests:
- POST /api/v1/auth/login
- POST /api/v1/auth/refresh
- GET  /api/v1/connection/test
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
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-api-tests"

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
    await auth_service.create_user("testuser", "testpass123", is_admin=False)
    return db


@pytest.fixture
def async_client():
    """Create an async test client for the FastAPI app."""
    from app.main import app

    transport = ASGITransport(app=app)
    return AsyncClient(transport=transport, base_url="http://testserver")


# ---------------------------------------------------------------------------
# GET /api/v1/connection/test
# ---------------------------------------------------------------------------


class TestConnectionTest:
    """Tests for the connection test endpoint."""

    @pytest.mark.asyncio
    async def test_connection_test_returns_ok(self, seeded_db, async_client):
        """Connection test endpoint returns status ok and version."""
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "ok"
        assert body["version"] == "0.1.0"

    @pytest.mark.asyncio
    async def test_connection_test_no_auth_required(self, seeded_db, async_client):
        """Connection test endpoint does not require authentication."""
        async with async_client as client:
            # No Authorization header
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200


# ---------------------------------------------------------------------------
# POST /api/v1/auth/login
# ---------------------------------------------------------------------------


class TestLoginEndpoint:
    """Tests for the login endpoint."""

    @pytest.mark.asyncio
    async def test_login_success(self, seeded_db, async_client):
        """Successful login returns access_token, refresh_token, and expires_in."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/auth/login",
                json={"username": "testuser", "password": "testpass123"},
            )

        assert response.status_code == 200
        body = response.json()
        assert "access_token" in body
        assert "refresh_token" in body
        assert "expires_in" in body
        assert body["access_token"] != ""
        assert body["refresh_token"] != ""
        assert body["expires_in"] == 24 * 3600

    @pytest.mark.asyncio
    async def test_login_wrong_password(self, seeded_db, async_client):
        """Login with wrong password returns 401."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/auth/login",
                json={"username": "testuser", "password": "wrongpassword"},
            )

        assert response.status_code == 401
        body = response.json()
        assert "detail" in body

    @pytest.mark.asyncio
    async def test_login_nonexistent_user(self, seeded_db, async_client):
        """Login with nonexistent user returns 401."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/auth/login",
                json={"username": "nobody", "password": "anypassword"},
            )

        assert response.status_code == 401

    @pytest.mark.asyncio
    async def test_login_generic_error_message(self, seeded_db, async_client):
        """Login failure does not reveal whether username or password is wrong."""
        async with async_client as client:
            # Wrong password
            resp1 = await client.post(
                "/api/v1/auth/login",
                json={"username": "testuser", "password": "wrong"},
            )
            # Wrong username
            resp2 = await client.post(
                "/api/v1/auth/login",
                json={"username": "nonexistent", "password": "testpass123"},
            )

        # Both should return the same generic error
        assert resp1.status_code == 401
        assert resp2.status_code == 401
        assert resp1.json()["detail"] == resp2.json()["detail"]

    @pytest.mark.asyncio
    async def test_login_missing_fields(self, seeded_db, async_client):
        """Login with missing fields returns 422 validation error."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/auth/login",
                json={"username": "testuser"},
            )

        assert response.status_code == 422


# ---------------------------------------------------------------------------
# POST /api/v1/auth/refresh
# ---------------------------------------------------------------------------


class TestRefreshEndpoint:
    """Tests for the token refresh endpoint."""

    @pytest.mark.asyncio
    async def test_refresh_success(self, seeded_db, async_client):
        """Valid refresh token returns new token pair."""
        async with async_client as client:
            # First login to get tokens
            login_resp = await client.post(
                "/api/v1/auth/login",
                json={"username": "testuser", "password": "testpass123"},
            )
            refresh_token = login_resp.json()["refresh_token"]

            # Refresh
            response = await client.post(
                "/api/v1/auth/refresh",
                json={"refresh_token": refresh_token},
            )

        assert response.status_code == 200
        body = response.json()
        assert "access_token" in body
        assert "refresh_token" in body
        assert "expires_in" in body
        assert body["access_token"] != ""
        assert body["refresh_token"] != ""

    @pytest.mark.asyncio
    async def test_refresh_invalid_token(self, async_client, seeded_db):
        """Invalid refresh token returns 401."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/auth/refresh",
                json={"refresh_token": "invalid.token.here"},
            )

        assert response.status_code == 401

    @pytest.mark.asyncio
    async def test_refresh_with_access_token_fails(self, seeded_db, async_client):
        """Using an access token as refresh token returns 401."""
        async with async_client as client:
            login_resp = await client.post(
                "/api/v1/auth/login",
                json={"username": "testuser", "password": "testpass123"},
            )
            access_token = login_resp.json()["access_token"]

            response = await client.post(
                "/api/v1/auth/refresh",
                json={"refresh_token": access_token},
            )

        assert response.status_code == 401

    @pytest.mark.asyncio
    async def test_refresh_missing_field(self, seeded_db, async_client):
        """Refresh with missing refresh_token field returns 422."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/auth/refresh",
                json={},
            )

        assert response.status_code == 422
