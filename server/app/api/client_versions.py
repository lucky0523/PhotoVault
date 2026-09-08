"""Administration and public download APIs for Android client releases."""

from __future__ import annotations

from typing import Annotated

import aiosqlite
from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse

from app.core.config import Settings, get_settings
from app.core.database import get_db
from app.core.security import require_admin
from app.models.auth import UserInfo
from app.models.client_version import ClientVersionInfo
from app.services.client_version_service import ClientVersionService

router = APIRouter()


@router.get("/admin/client-versions", response_model=list[ClientVersionInfo])
async def list_client_versions(
    _admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> list[dict]:
    return await ClientVersionService(db, settings).list_android_versions()


@router.post(
    "/admin/client-versions",
    response_model=ClientVersionInfo,
    status_code=status.HTTP_201_CREATED,
)
async def upload_client_version(
    file: Annotated[UploadFile, File(description="PhotoVault Android APK")],
    release_notes: Annotated[str | None, Form()] = None,
    admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> dict:
    try:
        return await ClientVersionService(db, settings).upload_android(
            file,
            release_notes=release_notes,
            uploaded_by=admin.id,
        )
    except ValueError as exc:
        detail = str(exc)
        code = status.HTTP_409_CONFLICT if "已存在" in detail else status.HTTP_400_BAD_REQUEST
        raise HTTPException(status_code=code, detail=detail) from exc


@router.delete("/admin/client-versions/{version_id}")
async def delete_client_version(
    version_id: int,
    _admin: UserInfo = Depends(require_admin),
    db: aiosqlite.Connection = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> dict:
    deleted = await ClientVersionService(db, settings).delete_android(version_id)
    if not deleted:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="客户端版本不存在")
    return {"success": True}


@router.get("/clients/android/latest", response_model=ClientVersionInfo)
async def get_latest_android_client(
    db: aiosqlite.Connection = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> dict:
    latest = await ClientVersionService(db, settings).get_latest_android()
    if latest is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="暂无可下载的 Android 客户端")
    return latest[0]


@router.get("/clients/android/latest/download")
async def download_latest_android_client(
    db: aiosqlite.Connection = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> FileResponse:
    latest = await ClientVersionService(db, settings).get_latest_android()
    if latest is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="暂无可下载的 Android 客户端")
    info, path = latest
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename=path.name,
        headers={
            "ETag": f'"{info["sha256"]}"',
            "Cache-Control": "no-cache",
            "X-Content-Type-Options": "nosniff",
        },
    )
