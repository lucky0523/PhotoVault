"""Tests for security dependencies (get_current_user, require_admin).

Uses a dedicated FastAPI test app with test routes that exercise the
dependency injection chain.
"""

import os

import pytest
import aiosqlite
from fastapi import Depends, FastAPI
from httpx import AsyncClient, ASGITransport

from app.core.config import reset_settings
from app.core.database import init_db, get_db
from app.core.security import get_current_user, require_admin
from app.models.auth import UserInfo
from app.services.auth_service import AuthService


# ---------------------------------------------------------------------------
# Test app with protected routes
# ---------------------------------------------------------------------------


def _create_test_app(db_path: str) -> FastAPI:
    """Create a minimal FastAPI app with test routes using security deps."""
    test_app = FastAPI()

    async def _override_get_db():
        db = await aiosqlite.connect(db_path)
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        try:
            yield db
        finally:
            await db.close()

    test_app.dependency_overrides[get_db] = _override_get_db

    @test_app.get("/protected")
    async def protected_route(user: UserInfo = Depends(get_current_user)):
        return {"user_id": user.id, "username": user.username, "is_admin": user.is_admin}

    @test_app.get("/admin-only")
    async def admin_route(user: UserInfo = Depends(require_admin)):
        return {"user_id": user.id, "username": user.username, "is_admin": user.is_admin}

    return test_app


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_settings_fixture():
    """Reset settings singleton before each test."""
    reset_settings()
    yield
    reset_settings()


@pytest.fixture
async def db_path(tmp_path):
    """Set up environment and initialize a test database."""
    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-security-tests"

    reset_settings()

    path = str(tmp_path / "test.db")
    await init_db(path)

    yield path

    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    reset_settings()


@pytest.fixture
async def test_client(db_path):
    """Create an async test client with the test app."""
    app = _create_test_app(db_path)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        yield client


@pytest.fixture
async def regular_user_token(db_path):
    """Create a regular user and return their access token."""
    async with aiosqlite.connect(db_path) as db:
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        auth_service = AuthService(db)
        await auth_service.create_user("regularuser", "password123", is_admin=False)
        token_pair = await auth_service.login("regularuser", "password123")
    return token_pair.access_token


@pytest.fixture
async def admin_user_token(db_path):
    """Create an admin user and return their access token."""
    async with aiosqlite.connect(db_path) as db:
        await db.execute("PRAGMA foreign_keys=ON;")
        db.row_factory = aiosqlite.Row
        auth_service = AuthService(db)
        await auth_service.create_user("adminuser", "adminpass", is_admin=True)
        token_pair = await auth_service.login("adminuser", "adminpass")
    return token_pair.access_token


# ---------------------------------------------------------------------------
# get_current_user tests
# ---------------------------------------------------------------------------


class TestGetCurrentUser:
    """Tests for the get_current_user dependency."""

    async def test_valid_token_returns_user_info(self, test_client, regular_user_token):
        """A valid Bearer token should return user info."""
        resp = await test_client.get(
            "/protected",
            headers={"Authorization": f"Bearer {regular_user_token}"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["username"] == "regularuser"
        assert data["is_admin"] is False

    async def test_missing_auth_header_returns_401(self, test_client):
        """No Authorization header should return 401 (or 403 from HTTPBearer)."""
        resp = await test_client.get("/protected")
        assert resp.status_code in (401, 403)

    async def test_invalid_token_returns_401(self, test_client):
        """An invalid token should return 401."""
        resp = await test_client.get(
            "/protected",
            headers={"Authorization": "Bearer invalid.token.here"},
        )
        assert resp.status_code == 401

    async def test_expired_token_returns_401(self, test_client, db_path):
        """An expired token should return 401."""
        import jwt as pyjwt
        from datetime import datetime, timezone, timedelta
        from app.core.config import get_settings

        settings = get_settings()
        payload = {
            "user_id": 1,
            "username": "regularuser",
            "is_admin": False,
            "exp": datetime.now(timezone.utc) - timedelta(hours=1),
            "iat": datetime.now(timezone.utc) - timedelta(hours=25),
            "type": "access",
        }
        expired_token = pyjwt.encode(payload, settings.jwt_secret_key, algorithm="HS256")

        resp = await test_client.get(
            "/protected",
            headers={"Authorization": f"Bearer {expired_token}"},
        )
        assert resp.status_code == 401
        assert "expired" in resp.json()["detail"].lower()

    async def test_admin_user_returns_admin_flag(self, test_client, admin_user_token):
        """An admin user's token should return is_admin=True."""
        resp = await test_client.get(
            "/protected",
            headers={"Authorization": f"Bearer {admin_user_token}"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["username"] == "adminuser"
        assert data["is_admin"] is True


# ---------------------------------------------------------------------------
# require_admin tests
# ---------------------------------------------------------------------------


class TestRequireAdmin:
    """Tests for the require_admin dependency."""

    async def test_admin_user_can_access(self, test_client, admin_user_token):
        """An admin user should be able to access admin-only routes."""
        resp = await test_client.get(
            "/admin-only",
            headers={"Authorization": f"Bearer {admin_user_token}"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["username"] == "adminuser"
        assert data["is_admin"] is True

    async def test_regular_user_gets_403(self, test_client, regular_user_token):
        """A non-admin user should get 403 on admin-only routes."""
        resp = await test_client.get(
            "/admin-only",
            headers={"Authorization": f"Bearer {regular_user_token}"},
        )
        assert resp.status_code == 403
        assert "admin" in resp.json()["detail"].lower()

    async def test_no_token_returns_401_or_403(self, test_client):
        """No token should return 401/403 (auth fails before admin check)."""
        resp = await test_client.get("/admin-only")
        assert resp.status_code in (401, 403)

    async def test_invalid_token_returns_401(self, test_client):
        """Invalid token should return 401 (auth fails before admin check)."""
        resp = await test_client.get(
            "/admin-only",
            headers={"Authorization": "Bearer bad.token.value"},
        )
        assert resp.status_code == 401
