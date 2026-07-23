"""Admin API endpoints.

Endpoints:
- GET    /admin/users                         — List all users (admin only)
- POST   /admin/users                         — Create a new user (admin only)
- DELETE /admin/users/{id}                    — Delete a user (admin only)
- DELETE /admin/users/{id}/purged-records     — Remove purged file records (admin only)
- PUT    /admin/users/{id}/password           — Change user password (admin only)
"""

from __future__ import annotations

import logging
from typing import List

import aiosqlite
from fastapi import APIRouter, Depends, HTTPException, status

from app.core.database import get_db
from app.core.security import require_admin
from app.core.validators import validate_password
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
    password_error = validate_password(body.password)
    if password_error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=password_error,
        )

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
    password_error = validate_password(body.new_password)
    if password_error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=password_error,
        )

    auth_service = AuthService(db)
    changed = await auth_service.change_password(user_id, body.new_password)
    if not changed:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )
    return {"success": True}


@router.delete("/admin/users/{user_id}/purged-records")
async def clear_purged_records(
    user_id: int,
    _admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
) -> dict:
    """Physically remove one user's already-purged file records.

    The operation is intentionally manual and removes only records whose
    ``purged_at`` is set. Associated analysis rows are removed in the same
    transaction, while face cluster metadata is recalculated for consistency.
    """
    cursor = await db.execute("SELECT 1 FROM users WHERE id = ?", (user_id,))
    if await cursor.fetchone() is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )

    cursor = await db.execute(
        """SELECT id FROM file_records
           WHERE user_id = ? AND purged_at IS NOT NULL
           ORDER BY is_reference DESC, id ASC""",
        (user_id,),
    )
    records = await cursor.fetchall()
    if not records:
        return {"success": True, "count": 0}

    # Import lazily so the lightweight admin endpoints do not require optional
    # face-analysis runtime dependencies until this cleanup is invoked.
    from app.services.face_analyzer import FaceAnalyzer

    face_analyzer = FaceAnalyzer()
    deleted_count = 0
    try:
        await db.execute("BEGIN")
        for record in records:
            file_id = record["id"]
            await face_analyzer.clear_file_faces(db, user_id, file_id)
            await db.execute(
                "DELETE FROM photo_gps WHERE file_id = ? AND user_id = ?",
                (file_id, user_id),
            )
            await db.execute(
                "DELETE FROM photo_scenes WHERE file_id = ? AND user_id = ?",
                (file_id, user_id),
            )

        # Reference records are deleted first, which keeps reference_to foreign
        # keys valid while their purged original records are removed afterwards.
        for record in records:
            cursor = await db.execute(
                """DELETE FROM file_records
                   WHERE id = ? AND user_id = ? AND purged_at IS NOT NULL""",
                (record["id"], user_id),
            )
            deleted_count += cursor.rowcount

        await db.commit()
    except Exception:
        await db.rollback()
        logger.exception("Failed to clear purged records for user %d", user_id)
        raise

    logger.info("Cleared %d purged records for user %d", deleted_count, user_id)
    return {"success": True, "count": deleted_count}
