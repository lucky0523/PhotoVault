"""Authentication API endpoints.

Endpoints:
- POST /auth/login
- POST /auth/refresh
- GET  /connection/test
"""

from __future__ import annotations

import logging

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, status

from app.core.database import get_db
from app.models.auth import LoginRequest, RefreshRequest, TokenPair
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
