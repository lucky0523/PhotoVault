"""Authentication API endpoints.

Endpoints:
- POST /auth/login
- POST /auth/register
- POST /auth/refresh
- GET  /auth/registration-status
- GET  /connection/test
"""

from __future__ import annotations

import logging

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, status

from app.core.config import get_settings
from app.core.database import get_db
from app.core.validators import validate_password
from app.models.auth import LoginRequest, RefreshRequest, RegisterRequest, TokenPair
from app.services.auth_service import AuthService

logger = logging.getLogger("photovault.api.auth")

router = APIRouter()


@router.post("/auth/login", response_model=TokenPair)
async def login(
    body: LoginRequest,
    db: aiosqlite.Connection = Depends(get_db),
) -> TokenPair:
    """Authenticate user and return access + refresh tokens.

    Returns 401 with a generic error message on failure.
    """
    auth_service = AuthService(db)
    try:
        token_pair = await auth_service.login(body.username, body.password)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
        )
    return token_pair


@router.post("/auth/register", response_model=TokenPair, status_code=status.HTTP_201_CREATED)
async def register(
    body: RegisterRequest,
    db: aiosqlite.Connection = Depends(get_db),
) -> TokenPair:
    """Register a new user account (public, no auth required).

    Only works when allow_registration is enabled in server config.
    Creates a non-admin user and returns tokens for immediate login.

    Returns:
        - 201 with token pair on success
        - 403 if registration is disabled
        - 400 if username taken, max users reached, or validation fails
    """
    settings = get_settings()
    if not settings.allow_registration:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="用户注册功能未开启",
        )

    # Validate input
    if not body.username or not body.username.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="用户名不能为空",
        )
    password_error = validate_password(body.password)
    if password_error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=password_error,
        )

    auth_service = AuthService(db)
    try:
        await auth_service.create_user(
            username=body.username.strip(),
            password=body.password,
            is_admin=False,
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )

    # Auto-login: generate tokens for the new user
    token_pair = await auth_service.login(body.username.strip(), body.password)
    return token_pair


@router.get("/auth/registration-status")
async def registration_status() -> dict:
    """Check whether public registration is enabled.

    No authentication required. Used by the frontend to show/hide
    the registration link on the login page.
    """
    settings = get_settings()
    return {"allow_registration": settings.allow_registration}


@router.post("/auth/refresh", response_model=TokenPair)
async def refresh(
    body: RefreshRequest,
    db: aiosqlite.Connection = Depends(get_db),
) -> TokenPair:
    """Generate new token pair from a valid refresh token.

    Returns 401 if the refresh token is invalid or expired.
    """
    auth_service = AuthService(db)
    try:
        token_pair = await auth_service.refresh_token(body.refresh_token)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired refresh token",
        )
    return token_pair


@router.get("/connection/test")
async def connection_test() -> dict:
    """Connection test endpoint (no auth required).

    Used by clients to verify server reachability.
    """
    return {"status": "ok", "version": "0.1.0"}
