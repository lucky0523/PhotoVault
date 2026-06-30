"""Authentication service.

Handles user authentication, JWT token management, and user CRUD operations.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone, timedelta
from typing import List

import bcrypt
import jwt
import aiosqlite

from app.core.config import get_settings
from app.models.auth import TokenPair, UserInfo

logger = logging.getLogger("photovault.auth")

# bcrypt cost factor
_BCRYPT_ROUNDS = 12


class AuthService:
    """Service for user authentication and management.

    Provides login, token verification/refresh, and user CRUD operations.
    Uses bcrypt for password hashing and PyJWT for token management.
    """

    def __init__(self, db: aiosqlite.Connection) -> None:
        self._db = db
        self._settings = get_settings()

    # ------------------------------------------------------------------
    # Authentication
    # ------------------------------------------------------------------

    async def login(self, username: str, password: str) -> TokenPair:
        """Authenticate user and return access + refresh tokens.

        Args:
            username: The user's username.
            password: The user's plaintext password.

        Returns:
            TokenPair with access_token, refresh_token, and expires_in.

        Raises:
            ValueError: If credentials are invalid (generic message).
        """
        cursor = await self._db.execute(
            "SELECT id, username, password_hash, is_admin FROM users WHERE username = ?",
            (username,),
        )
        row = await cursor.fetchone()

        if row is None:
            # User not found — still raise generic error
            raise ValueError("Invalid credentials")

        user_id = row[0] if isinstance(row, tuple) else row["id"]
        db_username = row[1] if isinstance(row, tuple) else row["username"]
        password_hash = row[2] if isinstance(row, tuple) else row["password_hash"]
        is_admin = row[3] if isinstance(row, tuple) else row["is_admin"]

        if not _verify_password(password, password_hash):
            raise ValueError("Invalid credentials")

        return self._generate_token_pair(user_id, db_username, bool(is_admin))

    async def verify_token(self, token: str) -> UserInfo:
        """Verify a JWT access token and return user info.

        Args:
            token: The JWT access token string.

        Returns:
            UserInfo extracted from the token payload.

        Raises:
            ValueError: If the token is invalid, expired, or not an access token.
        """
        try:
            payload = jwt.decode(
                token,
                self._settings.jwt_secret_key,
                algorithms=["HS256"],
            )
        except jwt.ExpiredSignatureError:
            raise ValueError("Token has expired")
        except jwt.InvalidTokenError:
            raise ValueError("Invalid token")

        if payload.get("type") != "access":
            raise ValueError("Invalid token type")

        return UserInfo(
            id=payload["user_id"],
            username=payload["username"],
            is_admin=payload["is_admin"],
            created_at=payload.get("created_at", ""),
        )

    async def refresh_token(self, refresh_token: str) -> TokenPair:
        """Generate new token pair from a valid refresh token.

        Args:
            refresh_token: A valid JWT refresh token.

        Returns:
            A new TokenPair.

        Raises:
            ValueError: If the refresh token is invalid or expired.
        """
        try:
            payload = jwt.decode(
                refresh_token,
                self._settings.jwt_secret_key,
                algorithms=["HS256"],
            )
        except jwt.ExpiredSignatureError:
            raise ValueError("Refresh token has expired")
        except jwt.InvalidTokenError:
            raise ValueError("Invalid refresh token")

        if payload.get("type") != "refresh":
            raise ValueError("Invalid token type")

        user_id = payload["user_id"]
        username = payload["username"]
        is_admin = payload["is_admin"]

        # Verify user still exists in database
        cursor = await self._db.execute(
            "SELECT id FROM users WHERE id = ?", (user_id,)
        )
        row = await cursor.fetchone()
        if row is None:
            raise ValueError("User no longer exists")

        return self._generate_token_pair(user_id, username, is_admin)

    # ------------------------------------------------------------------
    # User management
    # ------------------------------------------------------------------

    async def create_user(
        self, username: str, password: str, is_admin: bool = False
    ) -> UserInfo:
        """Create a new user. Check max_users limit.

        Args:
            username: Desired username.
            password: Plaintext password to hash.
            is_admin: Whether the user should have admin privileges.

        Returns:
            UserInfo for the newly created user.

        Raises:
            ValueError: If max users reached or username already exists.
        """
        # Check max users limit
        count = await self.get_user_count()
        if count >= self._settings.max_users:
            raise ValueError(
                f"Maximum number of users ({self._settings.max_users}) reached"
            )

        password_hash = _hash_password(password)

        try:
            cursor = await self._db.execute(
                "INSERT INTO users (username, password_hash, is_admin) VALUES (?, ?, ?)",
                (username, password_hash, is_admin),
            )
            await self._db.commit()
            user_id = cursor.lastrowid
        except aiosqlite.IntegrityError:
            raise ValueError(f"Username '{username}' already exists")

        # Fetch the created_at timestamp
        cursor = await self._db.execute(
            "SELECT created_at FROM users WHERE id = ?", (user_id,)
        )
        row = await cursor.fetchone()
        created_at = row[0] if isinstance(row, tuple) else row["created_at"]

        return UserInfo(
            id=user_id,
            username=username,
            is_admin=is_admin,
            created_at=str(created_at),
        )

    async def delete_user(self, user_id: int) -> bool:
        """Delete a user by ID.

        Args:
            user_id: The ID of the user to delete.

        Returns:
            True if the user was deleted, False if not found.
        """
        cursor = await self._db.execute(
            "DELETE FROM users WHERE id = ?", (user_id,)
        )
        await self._db.commit()
        return cursor.rowcount > 0

    async def change_password(self, user_id: int, new_password: str) -> bool:
        """Change a user's password.

        Args:
            user_id: The ID of the user.
            new_password: The new plaintext password to hash and store.

        Returns:
            True if the password was changed, False if user not found.
        """
        password_hash = _hash_password(new_password)
        cursor = await self._db.execute(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            (password_hash, user_id),
        )
        await self._db.commit()
        return cursor.rowcount > 0

    # ------------------------------------------------------------------
    # Query helpers
    # ------------------------------------------------------------------

    async def get_user_count(self) -> int:
        """Get current number of users."""
        cursor = await self._db.execute("SELECT COUNT(*) FROM users")
        row = await cursor.fetchone()
        return row[0] if row else 0

    async def list_users(self) -> List[UserInfo]:
        """List all users (for admin)."""
        cursor = await self._db.execute(
            "SELECT id, username, is_admin, created_at FROM users ORDER BY id"
        )
        rows = await cursor.fetchall()
        return [
            UserInfo(
                id=row[0] if isinstance(row, tuple) else row["id"],
                username=row[1] if isinstance(row, tuple) else row["username"],
                is_admin=bool(row[2] if isinstance(row, tuple) else row["is_admin"]),
                created_at=str(row[3] if isinstance(row, tuple) else row["created_at"]),
            )
            for row in rows
        ]

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _generate_token_pair(
        self, user_id: int, username: str, is_admin: bool
    ) -> TokenPair:
        """Generate an access + refresh token pair."""
        now = datetime.now(timezone.utc)

        access_expires = timedelta(hours=self._settings.access_token_expire_hours)
        refresh_expires = timedelta(days=self._settings.refresh_token_expire_days)

        access_payload = {
            "user_id": user_id,
            "username": username,
            "is_admin": is_admin,
            "exp": now + access_expires,
            "iat": now,
            "type": "access",
        }

        refresh_payload = {
            "user_id": user_id,
            "username": username,
            "is_admin": is_admin,
            "exp": now + refresh_expires,
            "iat": now,
            "type": "refresh",
        }

        access_token = jwt.encode(
            access_payload, self._settings.jwt_secret_key, algorithm="HS256"
        )
        refresh_token = jwt.encode(
            refresh_payload, self._settings.jwt_secret_key, algorithm="HS256"
        )

        return TokenPair(
            access_token=access_token,
            refresh_token=refresh_token,
            expires_in=int(access_expires.total_seconds()),
        )


# ---------------------------------------------------------------------------
# Module-level password utilities
# ---------------------------------------------------------------------------


def _hash_password(password: str) -> str:
    """Hash a password using bcrypt with cost factor 12."""
    salt = bcrypt.gensalt(rounds=_BCRYPT_ROUNDS)
    hashed = bcrypt.hashpw(password.encode("utf-8"), salt)
    return hashed.decode("utf-8")


def _verify_password(password: str, password_hash: str) -> bool:
    """Verify a password against a bcrypt hash."""
    return bcrypt.checkpw(
        password.encode("utf-8"), password_hash.encode("utf-8")
    )
