"""Authentication data models.

Pydantic models for authentication-related request/response objects.
"""

from pydantic import BaseModel


class TokenPair(BaseModel):
    """JWT token pair returned after successful authentication."""

    access_token: str
    refresh_token: str
    expires_in: int  # seconds until access_token expires


class UserInfo(BaseModel):
    """Public user information (no sensitive data)."""

    id: int
    username: str
    is_admin: bool
    created_at: str


class LoginRequest(BaseModel):
    """Login request body."""

    username: str
    password: str


class RefreshRequest(BaseModel):
    """Token refresh request body."""

    refresh_token: str


class CreateUserRequest(BaseModel):
    """Admin request to create a new user."""

    username: str
    password: str
    is_admin: bool = False


class RegisterRequest(BaseModel):
    """Public registration request."""

    username: str
    password: str


class ChangePasswordRequest(BaseModel):
    """Admin request to change a user's password."""

    new_password: str
