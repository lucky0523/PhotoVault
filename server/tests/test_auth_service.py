"""Tests for AuthService.

Covers login, token verification, token refresh, user CRUD, and edge cases.
"""

import pytest
import jwt
import aiosqlite

from app.core.config import get_settings, reset_settings
from app.core.database import init_db
from app.services.auth_service import AuthService, _hash_password, _verify_password
from app.models.auth import TokenPair, UserInfo


@pytest.fixture(autouse=True)
def _reset_settings():
    """Reset settings singleton before each test."""
    reset_settings()
    yield
    reset_settings()


@pytest.fixture
async def db(tmp_path):
    """Create an in-memory-like temp database with schema initialized."""
    import os

    os.environ["PHOTOVAULT_STORAGE_ROOT"] = str(tmp_path / "storage")
    os.environ["PHOTOVAULT_DATABASE_URL"] = str(tmp_path / "test.db")
    os.environ["PHOTOVAULT_JWT_SECRET_KEY"] = "test-secret-key-for-testing"

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

    # Clean up env vars
    os.environ.pop("PHOTOVAULT_STORAGE_ROOT", None)
    os.environ.pop("PHOTOVAULT_DATABASE_URL", None)
    os.environ.pop("PHOTOVAULT_JWT_SECRET_KEY", None)
    reset_settings()


@pytest.fixture
async def auth_service(db):
    """Create an AuthService instance with the test database."""
    return AuthService(db)


# ---------------------------------------------------------------------------
# Password hashing tests
# ---------------------------------------------------------------------------


class TestPasswordHashing:
    """Tests for password hashing utilities."""

    def test_hash_password_produces_bcrypt_hash(self):
        hashed = _hash_password("mypassword")
        assert hashed.startswith("$2b$12$")

    def test_verify_password_correct(self):
        hashed = _hash_password("secret123")
        assert _verify_password("secret123", hashed) is True

    def test_verify_password_incorrect(self):
        hashed = _hash_password("secret123")
        assert _verify_password("wrongpassword", hashed) is False

    def test_hash_password_different_each_time(self):
        h1 = _hash_password("same")
        h2 = _hash_password("same")
        # Different salts produce different hashes
        assert h1 != h2
        # But both verify correctly
        assert _verify_password("same", h1)
        assert _verify_password("same", h2)


# ---------------------------------------------------------------------------
# User creation tests
# ---------------------------------------------------------------------------


class TestCreateUser:
    """Tests for AuthService.create_user."""

    @pytest.mark.asyncio
    async def test_create_user_success(self, auth_service):
        user = await auth_service.create_user("alice", "password123")
        assert user.username == "alice"
        assert user.is_admin is False
        assert user.id > 0
        assert user.created_at != ""

    @pytest.mark.asyncio
    async def test_create_admin_user(self, auth_service):
        user = await auth_service.create_user("admin", "adminpass", is_admin=True)
        assert user.username == "admin"
        assert user.is_admin is True

    @pytest.mark.asyncio
    async def test_create_duplicate_username_fails(self, auth_service):
        await auth_service.create_user("bob", "pass1")
        with pytest.raises(ValueError, match="already exists"):
            await auth_service.create_user("bob", "pass2")

    @pytest.mark.asyncio
    async def test_create_user_max_limit(self, auth_service):
        """Creating more than max_users should fail."""
        settings = get_settings()
        # Create max_users users
        for i in range(settings.max_users):
            await auth_service.create_user(f"user{i}", f"pass{i}")

        # Next one should fail
        with pytest.raises(ValueError, match="Maximum number of users"):
            await auth_service.create_user("onemore", "pass")


# ---------------------------------------------------------------------------
# Login tests
# ---------------------------------------------------------------------------


class TestLogin:
    """Tests for AuthService.login."""

    @pytest.mark.asyncio
    async def test_login_success(self, auth_service):
        await auth_service.create_user("alice", "mypassword")
        token_pair = await auth_service.login("alice", "mypassword")

        assert isinstance(token_pair, TokenPair)
        assert token_pair.access_token != ""
        assert token_pair.refresh_token != ""
        assert token_pair.expires_in == 24 * 3600  # 24 hours in seconds

    @pytest.mark.asyncio
    async def test_login_wrong_password(self, auth_service):
        await auth_service.create_user("alice", "mypassword")
        with pytest.raises(ValueError, match="Invalid credentials"):
            await auth_service.login("alice", "wrongpassword")

    @pytest.mark.asyncio
    async def test_login_nonexistent_user(self, auth_service):
        with pytest.raises(ValueError, match="Invalid credentials"):
            await auth_service.login("nobody", "anypassword")

    @pytest.mark.asyncio
    async def test_login_generic_error_message(self, auth_service):
        """Login failure should not reveal whether username or password is wrong."""
        await auth_service.create_user("alice", "mypassword")

        # Wrong password
        with pytest.raises(ValueError) as exc_info:
            await auth_service.login("alice", "wrong")
        assert "Invalid credentials" in str(exc_info.value)

        # Wrong username
        with pytest.raises(ValueError) as exc_info:
            await auth_service.login("nonexistent", "mypassword")
        assert "Invalid credentials" in str(exc_info.value)


# ---------------------------------------------------------------------------
# Token verification tests
# ---------------------------------------------------------------------------


class TestVerifyToken:
    """Tests for AuthService.verify_token."""

    @pytest.mark.asyncio
    async def test_verify_valid_access_token(self, auth_service):
        await auth_service.create_user("alice", "pass123", is_admin=True)
        token_pair = await auth_service.login("alice", "pass123")

        user_info = await auth_service.verify_token(token_pair.access_token)
        assert user_info.username == "alice"
        assert user_info.is_admin is True
        assert user_info.id > 0

    @pytest.mark.asyncio
    async def test_verify_invalid_token(self, auth_service):
        with pytest.raises(ValueError, match="Invalid token"):
            await auth_service.verify_token("not.a.valid.token")

    @pytest.mark.asyncio
    async def test_verify_refresh_token_as_access_fails(self, auth_service):
        """Refresh tokens should not be accepted as access tokens."""
        await auth_service.create_user("alice", "pass123")
        token_pair = await auth_service.login("alice", "pass123")

        with pytest.raises(ValueError, match="Invalid token type"):
            await auth_service.verify_token(token_pair.refresh_token)

    @pytest.mark.asyncio
    async def test_verify_expired_token(self, auth_service):
        """Expired tokens should be rejected."""
        from datetime import datetime, timezone, timedelta

        settings = get_settings()
        payload = {
            "user_id": 1,
            "username": "alice",
            "is_admin": False,
            "exp": datetime.now(timezone.utc) - timedelta(hours=1),
            "iat": datetime.now(timezone.utc) - timedelta(hours=25),
            "type": "access",
        }
        expired_token = jwt.encode(payload, settings.jwt_secret_key, algorithm="HS256")

        with pytest.raises(ValueError, match="expired"):
            await auth_service.verify_token(expired_token)


# ---------------------------------------------------------------------------
# Token refresh tests
# ---------------------------------------------------------------------------


class TestRefreshToken:
    """Tests for AuthService.refresh_token."""

    @pytest.mark.asyncio
    async def test_refresh_token_success(self, auth_service):
        await auth_service.create_user("alice", "pass123")
        original = await auth_service.login("alice", "pass123")

        new_pair = await auth_service.refresh_token(original.refresh_token)
        assert isinstance(new_pair, TokenPair)
        assert new_pair.access_token != ""
        assert new_pair.refresh_token != ""
        assert new_pair.expires_in == 24 * 3600

        # Verify the new access token is valid
        user_info = await auth_service.verify_token(new_pair.access_token)
        assert user_info.username == "alice"

    @pytest.mark.asyncio
    async def test_refresh_with_access_token_fails(self, auth_service):
        """Access tokens should not be accepted as refresh tokens."""
        await auth_service.create_user("alice", "pass123")
        token_pair = await auth_service.login("alice", "pass123")

        with pytest.raises(ValueError, match="Invalid token type"):
            await auth_service.refresh_token(token_pair.access_token)

    @pytest.mark.asyncio
    async def test_refresh_invalid_token(self, auth_service):
        with pytest.raises(ValueError, match="Invalid refresh token"):
            await auth_service.refresh_token("garbage.token.here")

    @pytest.mark.asyncio
    async def test_refresh_deleted_user_fails(self, auth_service):
        """Refreshing token for a deleted user should fail."""
        user = await auth_service.create_user("alice", "pass123")
        token_pair = await auth_service.login("alice", "pass123")

        # Delete the user
        await auth_service.delete_user(user.id)

        with pytest.raises(ValueError, match="User no longer exists"):
            await auth_service.refresh_token(token_pair.refresh_token)


# ---------------------------------------------------------------------------
# Delete user tests
# ---------------------------------------------------------------------------


class TestDeleteUser:
    """Tests for AuthService.delete_user."""

    @pytest.mark.asyncio
    async def test_delete_existing_user(self, auth_service):
        user = await auth_service.create_user("alice", "pass123")
        result = await auth_service.delete_user(user.id)
        assert result is True

        # Verify user count decreased
        count = await auth_service.get_user_count()
        assert count == 0

    @pytest.mark.asyncio
    async def test_delete_nonexistent_user(self, auth_service):
        result = await auth_service.delete_user(9999)
        assert result is False


# ---------------------------------------------------------------------------
# Change password tests
# ---------------------------------------------------------------------------


class TestChangePassword:
    """Tests for AuthService.change_password."""

    @pytest.mark.asyncio
    async def test_change_password_success(self, auth_service):
        user = await auth_service.create_user("alice", "oldpass")
        result = await auth_service.change_password(user.id, "newpass")
        assert result is True

        # Old password should no longer work
        with pytest.raises(ValueError, match="Invalid credentials"):
            await auth_service.login("alice", "oldpass")

        # New password should work
        token_pair = await auth_service.login("alice", "newpass")
        assert token_pair.access_token != ""

    @pytest.mark.asyncio
    async def test_change_password_nonexistent_user(self, auth_service):
        result = await auth_service.change_password(9999, "newpass")
        assert result is False


# ---------------------------------------------------------------------------
# User listing tests
# ---------------------------------------------------------------------------


class TestListUsers:
    """Tests for AuthService.list_users and get_user_count."""

    @pytest.mark.asyncio
    async def test_list_users_empty(self, auth_service):
        users = await auth_service.list_users()
        assert users == []

    @pytest.mark.asyncio
    async def test_list_users_multiple(self, auth_service):
        await auth_service.create_user("alice", "pass1")
        await auth_service.create_user("bob", "pass2", is_admin=True)

        users = await auth_service.list_users()
        assert len(users) == 2
        assert users[0].username == "alice"
        assert users[0].is_admin is False
        assert users[1].username == "bob"
        assert users[1].is_admin is True

    @pytest.mark.asyncio
    async def test_get_user_count(self, auth_service):
        assert await auth_service.get_user_count() == 0
        await auth_service.create_user("alice", "pass1")
        assert await auth_service.get_user_count() == 1
        await auth_service.create_user("bob", "pass2")
        assert await auth_service.get_user_count() == 2


# ---------------------------------------------------------------------------
# JWT payload structure tests
# ---------------------------------------------------------------------------


class TestJWTPayload:
    """Tests verifying JWT payload structure matches design requirements."""

    @pytest.mark.asyncio
    async def test_access_token_payload_structure(self, auth_service):
        await auth_service.create_user("alice", "pass123", is_admin=True)
        token_pair = await auth_service.login("alice", "pass123")

        settings = get_settings()
        payload = jwt.decode(
            token_pair.access_token, settings.jwt_secret_key, algorithms=["HS256"]
        )

        assert "user_id" in payload
        assert "username" in payload
        assert "is_admin" in payload
        assert "exp" in payload
        assert "iat" in payload
        assert "type" in payload
        assert payload["type"] == "access"
        assert payload["username"] == "alice"
        assert payload["is_admin"] is True

    @pytest.mark.asyncio
    async def test_refresh_token_payload_structure(self, auth_service):
        await auth_service.create_user("alice", "pass123")
        token_pair = await auth_service.login("alice", "pass123")

        settings = get_settings()
        payload = jwt.decode(
            token_pair.refresh_token, settings.jwt_secret_key, algorithms=["HS256"]
        )

        assert payload["type"] == "refresh"
        assert "user_id" in payload
        assert "username" in payload
        assert "is_admin" in payload
        assert "exp" in payload
        assert "iat" in payload

    @pytest.mark.asyncio
    async def test_token_expiry_durations(self, auth_service):
        """Access token should expire in 24h, refresh in 7d."""
        await auth_service.create_user("alice", "pass123")
        token_pair = await auth_service.login("alice", "pass123")

        settings = get_settings()

        access_payload = jwt.decode(
            token_pair.access_token, settings.jwt_secret_key, algorithms=["HS256"]
        )
        refresh_payload = jwt.decode(
            token_pair.refresh_token, settings.jwt_secret_key, algorithms=["HS256"]
        )

        # Access token: 24 hours
        access_duration = access_payload["exp"] - access_payload["iat"]
        assert access_duration == 24 * 3600

        # Refresh token: 7 days
        refresh_duration = refresh_payload["exp"] - refresh_payload["iat"]
        assert refresh_duration == 7 * 24 * 3600
