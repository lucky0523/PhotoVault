"""Integration tests for authentication API endpoints.

Tests:
- POST /api/v1/auth/login
- POST /api/v1/auth/refresh
- GET  /api/v1/connection/test
"""

import os
import re

import pytest
import aiosqlite
from httpx import AsyncClient, ASGITransport

import app.core.database as database_module
from app import __version__
from app.core.config import reset_settings
from app.core.database import (
    INSTANCE_ID_KEY,
    cached_instance_fingerprint,
    init_db,
    instance_fingerprint,
)
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
        # 比对唯一真源，而不是写死一个字面量：否则每次改版本号这条都会挂。
        assert body["version"] == __version__

    @pytest.mark.asyncio
    async def test_connection_test_no_auth_required(self, seeded_db, async_client):
        """Connection test endpoint does not require authentication."""
        async with async_client as client:
            # No Authorization header
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_connection_test_includes_app(self, seeded_db, async_client):
        """Response carries app=photovault; status/version are unchanged."""
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200
        body = response.json()
        assert body["app"] == "photovault"
        assert body["status"] == "ok"
        assert body["version"] == __version__
        assert isinstance(body["version"], str)

    @pytest.mark.asyncio
    async def test_connection_test_fingerprint_matches_instance_id(
        self, seeded_db, async_client
    ):
        """instance_fingerprint is 32 lowercase hex and derived from the login instance_id."""
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")
            login = await client.post(
                "/api/v1/auth/login",
                json={"username": "testuser", "password": "testpass123"},
            )

        assert response.status_code == 200
        assert login.status_code == 200
        fp = response.json()["instance_fingerprint"]
        assert re.fullmatch(r"[0-9a-f]{32}", fp)
        assert fp == instance_fingerprint(login.json()["instance_id"])

    @pytest.mark.asyncio
    async def test_connection_test_exact_keys(self, seeded_db, async_client):
        """Response keys are exactly status, version, app, instance_fingerprint."""
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert set(response.json().keys()) == {
            "status",
            "version",
            "app",
            "instance_fingerprint",
        }

    @pytest.mark.asyncio
    async def test_connection_test_with_fingerprint_no_auth(self, seeded_db, async_client):
        """The extended response is still served without Authorization."""
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200
        assert "Authorization" not in response.request.headers
        assert "instance_fingerprint" in response.json()

    @pytest.mark.asyncio
    async def test_connection_test_without_cached_fingerprint(
        self, seeded_db, async_client, monkeypatch
    ):
        """When no fingerprint is cached the field is omitted, still 200."""
        monkeypatch.setattr(database_module, "_instance_fingerprint", None)
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200
        assert set(response.json().keys()) == {"status", "version", "app"}

    @pytest.mark.asyncio
    async def test_connection_test_does_not_query_instance_id(
        self, seeded_db, async_client, monkeypatch
    ):
        """Handler never calls get_instance_id, so a DB failure there is irrelevant."""

        async def _boom(*args, **kwargs):
            raise RuntimeError("database unavailable")

        monkeypatch.setattr(database_module, "get_instance_id", _boom)
        async with async_client as client:
            response = await client.get("/api/v1/connection/test")

        assert response.status_code == 200
        assert re.fullmatch(r"[0-9a-f]{32}", response.json()["instance_fingerprint"])


class TestInstanceFingerprint:
    """Tests for the instance fingerprint helper and init_db cache."""

    def test_fingerprint_vectors(self):
        """Fixed test vectors shared with clients (design.md §3.1)."""
        assert (
            instance_fingerprint("00000000-0000-0000-0000-000000000000")
            == "0aa7ca809fe5773d5f9134544860ff3c"
        )
        assert (
            instance_fingerprint("3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d")
            == "08cf6b7fb5c87de79237818508128056"
        )

    @pytest.mark.asyncio
    async def test_init_db_caches_fingerprint(self, tmp_path):
        """init_db caches the fingerprint of the stored instance_id."""
        db_path = str(tmp_path / "fp.db")
        await init_db(db_path)

        async with aiosqlite.connect(db_path) as conn:
            cursor = await conn.execute(
                "SELECT value FROM server_metadata WHERE key = ?",
                (INSTANCE_ID_KEY,),
            )
            row = await cursor.fetchone()

        assert row is not None
        assert cached_instance_fingerprint() == instance_fingerprint(row[0])


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
        assert body["instance_id"]
        assert body["user_id"] > 0

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
        assert body["instance_id"] == login_resp.json()["instance_id"]
        assert body["user_id"] == login_resp.json()["user_id"]

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
