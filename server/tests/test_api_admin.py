"""Integration tests for admin API endpoints.

Tests:
- GET    /api/v1/admin/users
- POST   /api/v1/admin/users
- DELETE /api/v1/admin/users/{user_id}
- PUT    /api/v1/admin/users/{user_id}/password
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
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-admin-tests"

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
async def admin_db(db):
    """Database with an admin user created."""
    auth_service = AuthService(db)
    await auth_service.create_user("admin", "adminpass123", is_admin=True)
    return db


@pytest.fixture
async def admin_token(admin_db):
    """Get an access token for the admin user."""
    auth_service = AuthService(admin_db)
    token_pair = await auth_service.login("admin", "adminpass123")
    return token_pair.access_token


@pytest.fixture
async def regular_user_token(admin_db):
    """Get an access token for a regular (non-admin) user."""
    auth_service = AuthService(admin_db)
    await auth_service.create_user("regular", "regularpass123", is_admin=False)
    token_pair = await auth_service.login("regular", "regularpass123")
    return token_pair.access_token


@pytest.fixture
def async_client():
    """Create an async test client for the FastAPI app."""
    from app.main import app

    transport = ASGITransport(app=app)
    return AsyncClient(transport=transport, base_url="http://testserver")


# ---------------------------------------------------------------------------
# GET /api/v1/admin/users
# ---------------------------------------------------------------------------


class TestListUsers:
    """Tests for the list users endpoint."""

    @pytest.mark.asyncio
    async def test_list_users_as_admin(self, admin_db, admin_token, async_client):
        """Admin can list all users."""
        async with async_client as client:
            response = await client.get(
                "/api/v1/admin/users",
                headers={"Authorization": f"Bearer {admin_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert isinstance(body, list)
        assert len(body) >= 1
        # Check structure of user info
        user = body[0]
        assert "id" in user
        assert "username" in user
        assert "is_admin" in user
        assert "created_at" in user

    @pytest.mark.asyncio
    async def test_list_users_as_regular_user_forbidden(
        self, admin_db, regular_user_token, async_client
    ):
        """Non-admin user gets 403 when trying to list users."""
        async with async_client as client:
            response = await client.get(
                "/api/v1/admin/users",
                headers={"Authorization": f"Bearer {regular_user_token}"},
            )

        assert response.status_code == 403

    @pytest.mark.asyncio
    async def test_list_users_no_auth(self, admin_db, async_client):
        """Unauthenticated request gets 401 (no credentials provided)."""
        async with async_client as client:
            response = await client.get("/api/v1/admin/users")

        assert response.status_code == 401


# ---------------------------------------------------------------------------
# POST /api/v1/admin/users
# ---------------------------------------------------------------------------


class TestCreateUser:
    """Tests for the create user endpoint."""

    @pytest.mark.asyncio
    async def test_create_user_success(self, admin_db, admin_token, async_client):
        """Admin can create a new user."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/admin/users",
                headers={"Authorization": f"Bearer {admin_token}"},
                json={"username": "newuser", "password": "newpass123"},
            )

        assert response.status_code == 201
        body = response.json()
        assert body["username"] == "newuser"
        assert body["is_admin"] is False
        assert "id" in body
        assert "created_at" in body

    @pytest.mark.asyncio
    async def test_create_admin_user(self, admin_db, admin_token, async_client):
        """Admin can create another admin user."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/admin/users",
                headers={"Authorization": f"Bearer {admin_token}"},
                json={
                    "username": "admin2",
                    "password": "admin2pass",
                    "is_admin": True,
                },
            )

        assert response.status_code == 201
        body = response.json()
        assert body["username"] == "admin2"
        assert body["is_admin"] is True

    @pytest.mark.asyncio
    async def test_create_duplicate_user(self, admin_db, admin_token, async_client):
        """Creating a user with an existing username returns 400."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/admin/users",
                headers={"Authorization": f"Bearer {admin_token}"},
                json={"username": "admin", "password": "somepass"},
            )

        assert response.status_code == 400
        assert "already exists" in response.json()["detail"]

    @pytest.mark.asyncio
    async def test_create_user_max_users_reached(
        self, admin_db, admin_token, async_client
    ):
        """Creating a user when max_users is reached returns 400."""
        os.environ["PHOTOVAULT_MAX_USERS"] = "1"
        reset_settings()

        async with async_client as client:
            response = await client.post(
                "/api/v1/admin/users",
                headers={"Authorization": f"Bearer {admin_token}"},
                json={"username": "overflow", "password": "pass123"},
            )

        assert response.status_code == 400
        assert "Maximum" in response.json()["detail"]

        os.environ.pop("PHOTOVAULT_MAX_USERS", None)
        reset_settings()

    @pytest.mark.asyncio
    async def test_create_user_as_regular_user_forbidden(
        self, admin_db, regular_user_token, async_client
    ):
        """Non-admin user gets 403 when trying to create a user."""
        async with async_client as client:
            response = await client.post(
                "/api/v1/admin/users",
                headers={"Authorization": f"Bearer {regular_user_token}"},
                json={"username": "hacker", "password": "pass123"},
            )

        assert response.status_code == 403


# ---------------------------------------------------------------------------
# DELETE /api/v1/admin/users/{user_id}
# ---------------------------------------------------------------------------


class TestDeleteUser:
    """Tests for the delete user endpoint."""

    @pytest.mark.asyncio
    async def test_delete_user_success(self, admin_db, admin_token, async_client):
        """Admin can delete a user."""
        # First create a user to delete
        auth_service = AuthService(admin_db)
        user = await auth_service.create_user("todelete", "pass123", is_admin=False)

        async with async_client as client:
            response = await client.delete(
                f"/api/v1/admin/users/{user.id}",
                headers={"Authorization": f"Bearer {admin_token}"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["success"] is True

    @pytest.mark.asyncio
    async def test_delete_nonexistent_user(self, admin_db, admin_token, async_client):
        """Deleting a nonexistent user returns 404."""
        async with async_client as client:
            response = await client.delete(
                "/api/v1/admin/users/99999",
                headers={"Authorization": f"Bearer {admin_token}"},
            )

        assert response.status_code == 404

    @pytest.mark.asyncio
    async def test_delete_user_as_regular_user_forbidden(
        self, admin_db, regular_user_token, async_client
    ):
        """Non-admin user gets 403 when trying to delete a user."""
        async with async_client as client:
            response = await client.delete(
                "/api/v1/admin/users/1",
                headers={"Authorization": f"Bearer {regular_user_token}"},
            )

        assert response.status_code == 403


# ---------------------------------------------------------------------------
# PUT /api/v1/admin/users/{user_id}/password
# ---------------------------------------------------------------------------


class TestChangePassword:
    """Tests for the change password endpoint."""

    @pytest.mark.asyncio
    async def test_change_password_success(self, admin_db, admin_token, async_client):
        """Admin can change a user's password."""
        # Create a user whose password we'll change
        auth_service = AuthService(admin_db)
        user = await auth_service.create_user("pwuser", "oldpass123", is_admin=False)

        async with async_client as client:
            response = await client.put(
                f"/api/v1/admin/users/{user.id}/password",
                headers={"Authorization": f"Bearer {admin_token}"},
                json={"new_password": "newpass456"},
            )

        assert response.status_code == 200
        body = response.json()
        assert body["success"] is True

        # Verify the new password works
        token_pair = await auth_service.login("pwuser", "newpass456")
        assert token_pair.access_token != ""

    @pytest.mark.asyncio
    async def test_change_password_old_password_invalid(
        self, admin_db, admin_token, async_client
    ):
        """After password change, old password no longer works."""
        auth_service = AuthService(admin_db)
        user = await auth_service.create_user("pwuser2", "oldpass", is_admin=False)

        async with async_client as client:
            await client.put(
                f"/api/v1/admin/users/{user.id}/password",
                headers={"Authorization": f"Bearer {admin_token}"},
                json={"new_password": "brandnew"},
            )

        # Old password should fail
        with pytest.raises(ValueError):
            await auth_service.login("pwuser2", "oldpass")

    @pytest.mark.asyncio
    async def test_change_password_nonexistent_user(
        self, admin_db, admin_token, async_client
    ):
        """Changing password for nonexistent user returns 404."""
        async with async_client as client:
            response = await client.put(
                "/api/v1/admin/users/99999/password",
                headers={"Authorization": f"Bearer {admin_token}"},
                json={"new_password": "newpass"},
            )

        assert response.status_code == 404

    @pytest.mark.asyncio
    async def test_change_password_as_regular_user_forbidden(
        self, admin_db, regular_user_token, async_client
    ):
        """Non-admin user gets 403 when trying to change a password."""
        async with async_client as client:
            response = await client.put(
                "/api/v1/admin/users/1/password",
                headers={"Authorization": f"Bearer {regular_user_token}"},
                json={"new_password": "hacked"},
            )

        assert response.status_code == 403
