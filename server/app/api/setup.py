"""Setup/initialization API endpoints.

Endpoints:
- GET  /api/v1/setup/status   (check if initialized)
- POST /api/v1/setup/init     (first-time admin creation)
"""

from __future__ import annotations

import logging

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, field_validator

from app.core.database import get_db
from app.services.auth_service import AuthService

logger = logging.getLogger("photovault.api.setup")

router = APIRouter()


# ---------------------------------------------------------------------------
# Request/Response models
# ---------------------------------------------------------------------------


class SetupStatusResponse(BaseModel):
    """Response for the setup status check."""

    initialized: bool


class SetupInitRequest(BaseModel):
    """Request body for initial admin account creation."""

    username: str
    password: str

    @field_validator("username")
    @classmethod
    def username_must_not_be_empty(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("Username must not be empty")
        return v.strip()

    @field_validator("password")
    @classmethod
    def password_must_be_long_enough(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("Password must be at least 8 characters")
        return v


class SetupInitResponse(BaseModel):
    """Response after successful admin creation."""

    success: bool
    username: str


# ---------------------------------------------------------------------------
# Helper: check initialization status
# ---------------------------------------------------------------------------


async def _is_initialized(db: aiosqlite.Connection) -> bool:
    """Check if the system has been initialized (at least one user exists)."""
    cursor = await db.execute("SELECT COUNT(*) FROM users")
    row = await cursor.fetchone()
    count = row[0] if row else 0
    return count > 0


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/setup/status", response_model=SetupStatusResponse)
async def get_setup_status(
    db: aiosqlite.Connection = Depends(get_db),
) -> SetupStatusResponse:
    """Check whether the system has been initialized.

    Returns {"initialized": true} if at least one user exists,
    otherwise {"initialized": false}.

    This endpoint does NOT require authentication.
    """
    initialized = await _is_initialized(db)
    return SetupStatusResponse(initialized=initialized)


@router.post("/setup/init", response_model=SetupInitResponse)
async def setup_init(
    body: SetupInitRequest,
    db: aiosqlite.Connection = Depends(get_db),
) -> SetupInitResponse:
    """Create the initial admin account (first-time setup).

    Only works when the system has no users. Once an admin is created,
    this endpoint will reject further calls with 403.

    Request body:
        - username: non-empty string
        - password: at least 8 characters

    Returns:
        - success: true
        - username: the created admin username
    """
    # Check if already initialized
    if await _is_initialized(db):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="System already initialized",
        )

    # Create admin user
    auth_service = AuthService(db)
    try:
        user_info = await auth_service.create_user(
            username=body.username,
            password=body.password,
            is_admin=True,
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )

    logger.info("Initial admin account created: %s", user_info.username)
    return SetupInitResponse(success=True, username=user_info.username)
