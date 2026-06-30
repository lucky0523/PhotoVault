"""Security utilities.

JWT token creation/verification, password hashing (bcrypt), and
FastAPI dependency injection for route protection.

Provides:
- ``get_current_user`` — FastAPI dependency that extracts and validates the
  current user from a JWT Bearer token.
- ``get_current_user_or_query_token`` — Same as get_current_user but also
  accepts token from query parameter for image/thumbnail URLs.
- ``require_admin`` — FastAPI dependency that requires the current user to
  be an admin.
"""

from __future__ import annotations

import logging
from typing import Optional

import aiosqlite
from fastapi import Depends, HTTPException, Query, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from app.core.database import get_db
from app.models.auth import UserInfo
from app.services.auth_service import AuthService

logger = logging.getLogger("photovault.security")

security_scheme = HTTPBearer(auto_error=False)


async def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security_scheme),
    db: aiosqlite.Connection = Depends(get_db),
) -> UserInfo:
    """FastAPI dependency that extracts and validates the current user from JWT.

    Extracts the Bearer token from the Authorization header, verifies it
    using AuthService.verify_token(), and returns the authenticated UserInfo.

    Args:
        credentials: The HTTP Bearer credentials extracted by FastAPI.
        db: Database connection from the get_db dependency.

    Returns:
        UserInfo for the authenticated user.

    Raises:
        HTTPException: 401 if the token is missing, invalid, or expired.
    """
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token = credentials.credentials
    auth_service = AuthService(db)

    try:
        user_info = await auth_service.verify_token(token)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(e),
            headers={"WWW-Authenticate": "Bearer"},
        )

    return user_info


async def get_current_user_or_query_token(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security_scheme),
    token: Optional[str] = Query(default=None, description="JWT token for image URLs"),
    db: aiosqlite.Connection = Depends(get_db),
) -> UserInfo:
    """FastAPI dependency that extracts and validates the current user from JWT.

    Supports token from either:
    - Authorization header (Bearer token)
    - Query parameter ?token= (for image/thumbnail URLs where headers can't be set)

    Args:
        credentials: The HTTP Bearer credentials extracted by FastAPI.
        token: Optional JWT token from query parameter.
        db: Database connection from the get_db dependency.

    Returns:
        UserInfo for the authenticated user.

    Raises:
        HTTPException: 401 if the token is missing, invalid, or expired.
    """
    token_value = None
    if credentials:
        token_value = credentials.credentials
    elif token:
        token_value = token

    if not token_value:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
            headers={"WWW-Authenticate": "Bearer"},
        )

    auth_service = AuthService(db)

    try:
        user_info = await auth_service.verify_token(token_value)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(e),
            headers={"WWW-Authenticate": "Bearer"},
        )

    return user_info


async def require_admin(
    current_user: UserInfo = Depends(get_current_user),
) -> UserInfo:
    """FastAPI dependency that requires the current user to be an admin.

    Depends on ``get_current_user`` to first authenticate the user, then
    checks if the user has admin privileges.

    Args:
        current_user: The authenticated user from get_current_user.

    Returns:
        UserInfo if the user is an admin.

    Raises:
        HTTPException: 403 if the user is not an admin.
    """
    if not current_user.is_admin:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin privileges required",
        )
    return current_user
