"""Admin API endpoints.

Endpoints:
- GET    /admin/users         — List all users (admin only)
- POST   /admin/users         — Create a new user (admin only)
- DELETE /admin/users/{id}    — Delete a user (admin only)
- PUT    /admin/users/{id}/password — Change user password (admin only)
"""

from __future__ import annotations

import logging
from typing import List

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, status

from app.core.database import get_db
from app.core.security import require_admin
from app.models.auth import (
    ChangePasswordRequest,
    CreateUserRequest,
    UserInfo,
)
from app.services.auth_service import AuthService

logger = logging.getLogger("photovault.api.admin")

router = APIRouter()


@router.get("/admin/users", response_model=List[UserInfo])
async def list_users(
    _admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
) -> List[UserInfo]:
    """List all users. Requires admin privileges."""
    auth_service = AuthService(db)
    return await auth_service.list_users()


@router.post("/admin/users", response_model=UserInfo, status_code=status.HTTP_201_CREATED)
async def create_user(
    body: CreateUserRequest,
    _admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
) -> UserInfo:
    """Create a new user. Requires admin privileges.

    Returns 400 if max users reached or username already exists.
    """
    auth_service = AuthService(db)
    try:
        user = await auth_service.create_user(
            username=body.username,
            password=body.password,
            is_admin=body.is_admin,
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )
    return user


@router.delete("/admin/users/{user_id}")
async def delete_user(
    user_id: int,
    _admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
) -> dict:
    """Delete a user by ID. Requires admin privileges.

    Returns 404 if user not found.
    """
    auth_service = AuthService(db)
    deleted = await auth_service.delete_user(user_id)
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )
    return {"success": True}


@router.put("/admin/users/{user_id}/password")
async def change_password(
    user_id: int,
    body: ChangePasswordRequest,
    _admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
) -> dict:
    """Change a user's password. Requires admin privileges.

    Returns 404 if user not found.
    """
    auth_service = AuthService(db)
    changed = await auth_service.change_password(user_id, body.new_password)
    if not changed:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )
    return {"success": True}
