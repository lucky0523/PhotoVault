"""Android APK validation, persistence and version selection."""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import re
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from uuid import uuid4

import aiosqlite
from fastapi import UploadFile
from apkutils import APK

from app.core.config import Settings

logger = logging.getLogger("photovault.client_versions")

ANDROID_PLATFORM = "android"
APP_ENGLISH_NAME = "PhotoVault"
EXPECTED_ANDROID_PACKAGE = "com.huoyi.photovault"
BUILD_DATE_METADATA_NAME = "com.huoyi.photovault.BUILD_DATE"
ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"
LATEST_DOWNLOAD_URL = "/api/v1/clients/android/latest/download"
MAX_APK_SIZE = 500 * 1024 * 1024
UPLOAD_CHUNK_SIZE = 1024 * 1024


@dataclass(frozen=True)
class ApkMetadata:
    package_name: str
    version_name: str
    version_code: int
    build_date: str


def _has_apk_signature(path: Path, archive: zipfile.ZipFile) -> bool:
    """Check for a v1 certificate entry or a v2+ APK Signing Block."""
    upper_names = {name.upper() for name in archive.namelist()}
    has_v1_certificate = any(
        name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC"))
        for name in upper_names
    )
    if has_v1_certificate:
        return True

    # APK Signature Schemes v2/v3/v4 place this magic immediately before the
    # ZIP central directory.  This verifies the package carries a signature;
    # Android still performs the cryptographic trust check when installing.
    if archive.start_dir < 24:
        return False
    with path.open("rb") as apk_file:
        apk_file.seek(archive.start_dir - 24)
        return apk_file.read(24)[8:] == b"APK Sig Block 42"


def _parse_apk(path: Path) -> ApkMetadata:
    """Read package/version metadata from the binary Android manifest."""
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            if "AndroidManifest.xml" not in names or not any(
                name == "classes.dex" or re.fullmatch(r"classes\d+\.dex", name)
                for name in names
            ):
                raise ValueError("APK 缺少 AndroidManifest.xml 或 classes.dex")
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise ValueError(f"APK 内文件校验失败: {bad_entry}")
            if not _has_apk_signature(path, archive):
                raise ValueError("APK 缺少可识别的签名")

        with APK.from_file(str(path)) as apk:
            manifest_xml = apk.get_manifest()
        root = ET.fromstring(manifest_xml)
        package_name = (root.get("package") or "").strip()
        version_name = (
            root.get(f"{{{ANDROID_XML_NAMESPACE}}}versionName") or ""
        ).strip()
        version_code_raw = (
            root.get(f"{{{ANDROID_XML_NAMESPACE}}}versionCode") or ""
        ).strip()
        version_code = int(version_code_raw, 0)

        # Android's ZIP entry times are deliberately normalized and cannot be
        # treated as compile time. New clients embed an ISO build date in the
        # manifest; old clients fall back to the upload day for compatibility.
        build_date = date.today().strftime("%Y%m%d")
        application = root.find("application")
        if application is not None:
            for item in application.findall("meta-data"):
                if item.get(f"{{{ANDROID_XML_NAMESPACE}}}name") != BUILD_DATE_METADATA_NAME:
                    continue
                raw_build_date = (
                    item.get(f"{{{ANDROID_XML_NAMESPACE}}}value") or ""
                ).strip()
                try:
                    build_date = datetime.strptime(raw_build_date, "%Y-%m-%d").strftime(
                        "%Y%m%d"
                    )
                except ValueError as exc:
                    raise ValueError("APK 中的编译日期格式无效") from exc
                break
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError("无法解析 APK 清单，请确认文件是完整且已签名的 Android APK") from exc

    if not package_name or not version_name or version_code <= 0:
        raise ValueError("APK 清单缺少有效的包名、versionName 或 versionCode")
    return ApkMetadata(package_name, version_name, version_code, build_date)


def _safe_apk_path(settings: Settings, relative_path: str) -> Path:
    root = Path(settings.client_releases_root).resolve()
    candidate = (root / relative_path).resolve()
    if candidate != root and root not in candidate.parents:
        raise ValueError("客户端文件路径超出专用存储目录")
    return candidate


def _row_to_info(row: aiosqlite.Row, *, latest_id: int | None = None) -> dict:
    is_latest = row["id"] == latest_id
    return {
        "id": row["id"],
        "platform": row["platform"],
        "package_name": row["package_name"],
        "version_name": row["version_name"],
        "version_code": row["version_code"],
        "original_filename": row["original_filename"],
        "file_size": row["file_size"],
        "sha256": row["sha256"],
        "release_notes": row["release_notes"],
        "created_at": row["created_at"],
        "is_latest": is_latest,
        "download_url": LATEST_DOWNLOAD_URL if is_latest else None,
    }


class ClientVersionService:
    def __init__(self, db: aiosqlite.Connection, settings: Settings):
        self.db = db
        self.settings = settings

    async def list_android_versions(self) -> list[dict]:
        cursor = await self.db.execute(
            """SELECT * FROM client_versions
               WHERE platform = ? AND package_name = ?
               ORDER BY version_code DESC, id DESC""",
            (ANDROID_PLATFORM, EXPECTED_ANDROID_PACKAGE),
        )
        rows = await cursor.fetchall()
        latest_id = None
        for row in rows:
            try:
                if _safe_apk_path(self.settings, row["relative_apk_path"]).is_file():
                    latest_id = row["id"]
                    break
            except ValueError:
                logger.error("Unsafe path in client version record %s", row["id"])
        return [_row_to_info(row, latest_id=latest_id) for row in rows]

    async def get_latest_android(self) -> tuple[dict, Path] | None:
        cursor = await self.db.execute(
            """SELECT * FROM client_versions
               WHERE platform = ? AND package_name = ?
               ORDER BY version_code DESC, id DESC""",
            (ANDROID_PLATFORM, EXPECTED_ANDROID_PACKAGE),
        )
        rows = await cursor.fetchall()
        for row in rows:
            try:
                path = _safe_apk_path(self.settings, row["relative_apk_path"])
            except ValueError:
                logger.error("Unsafe path in client version record %s", row["id"])
                continue
            if path.is_file():
                return _row_to_info(row, latest_id=row["id"]), path
            logger.error("Client APK is missing for version record %s: %s", row["id"], path)
        return None

    async def upload_android(
        self,
        upload: UploadFile,
        *,
        release_notes: str | None,
        uploaded_by: int,
    ) -> dict:
        filename = Path(upload.filename or "").name
        if not filename.lower().endswith(".apk"):
            raise ValueError("仅支持上传 .apk 文件")

        android_root = Path(self.settings.client_releases_root) / ANDROID_PLATFORM
        android_root.mkdir(parents=True, exist_ok=True)
        temp_path = android_root / f".upload-{uuid4().hex}.tmp"
        size = 0
        digest = hashlib.sha256()

        try:
            with temp_path.open("xb") as output:
                while chunk := await upload.read(UPLOAD_CHUNK_SIZE):
                    size += len(chunk)
                    if size > MAX_APK_SIZE:
                        raise ValueError("APK 文件不能超过 500 MB")
                    digest.update(chunk)
                    output.write(chunk)
            if size == 0:
                raise ValueError("APK 文件不能为空")

            notes = (release_notes or "").strip() or None
            if notes and len(notes) > 2000:
                raise ValueError("更新说明不能超过 2000 个字符")

            metadata = await asyncio.to_thread(_parse_apk, temp_path)
            if metadata.package_name != EXPECTED_ANDROID_PACKAGE:
                raise ValueError(
                    f"APK 包名必须是 {EXPECTED_ANDROID_PACKAGE}，实际为 {metadata.package_name}"
                )

            safe_version = re.sub(r"[^A-Za-z0-9._-]+", "-", metadata.version_name).strip("-.")
            safe_version = safe_version or str(metadata.version_code)
            stored_name = (
                f"{APP_ENGLISH_NAME}-v{safe_version}-{metadata.build_date}.apk"
            )
            target_path = android_root / stored_name
            relative_path = str(target_path.relative_to(Path(self.settings.client_releases_root)))

            await self.db.execute("BEGIN IMMEDIATE")
            cursor = await self.db.execute(
                """SELECT id FROM client_versions
                   WHERE platform = ? AND package_name = ? AND version_code = ?""",
                (ANDROID_PLATFORM, metadata.package_name, metadata.version_code),
            )
            if await cursor.fetchone() is not None:
                await self.db.rollback()
                raise ValueError(f"versionCode {metadata.version_code} 已存在")

            # The requested filename omits versionCode. Protect an APK already
            # referenced by another record instead of silently replacing it when
            # two builds share the same versionName and build date.
            cursor = await self.db.execute(
                "SELECT id FROM client_versions WHERE relative_apk_path = ?",
                (relative_path,),
            )
            if await cursor.fetchone() is not None:
                await self.db.rollback()
                raise ValueError(f"客户端文件 {stored_name} 已存在")

            moved = False
            try:
                os.replace(temp_path, target_path)
                moved = True
                cursor = await self.db.execute(
                    """INSERT INTO client_versions (
                           platform, package_name, version_name, version_code,
                           relative_apk_path, original_filename, file_size, sha256,
                           release_notes, uploaded_by
                       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (
                        ANDROID_PLATFORM,
                        metadata.package_name,
                        metadata.version_name,
                        metadata.version_code,
                        relative_path,
                        stored_name,
                        size,
                        digest.hexdigest(),
                        notes,
                        uploaded_by,
                    ),
                )
                version_id = cursor.lastrowid
                await self.db.commit()
            except Exception:
                await self.db.rollback()
                if moved:
                    target_path.unlink(missing_ok=True)
                raise

            cursor = await self.db.execute(
                "SELECT * FROM client_versions WHERE id = ?", (version_id,)
            )
            row = await cursor.fetchone()
            if row is None:
                raise RuntimeError("客户端版本记录创建失败")
            latest = await self.get_latest_android()
            latest_id = latest[0]["id"] if latest else None
            logger.info(
                "Uploaded Android client %s (%s), versionCode=%d, size=%d",
                metadata.version_name,
                metadata.package_name,
                metadata.version_code,
                size,
            )
            return _row_to_info(row, latest_id=latest_id)
        finally:
            temp_path.unlink(missing_ok=True)
            await upload.close()

    async def delete_android(self, version_id: int) -> bool:
        cursor = await self.db.execute(
            """SELECT * FROM client_versions
               WHERE id = ? AND platform = ? AND package_name = ?""",
            (version_id, ANDROID_PLATFORM, EXPECTED_ANDROID_PACKAGE),
        )
        row = await cursor.fetchone()
        if row is None:
            return False

        path = _safe_apk_path(self.settings, row["relative_apk_path"])
        await self.db.execute("BEGIN IMMEDIATE")
        try:
            await self.db.execute("DELETE FROM client_versions WHERE id = ?", (version_id,))
            await self.db.commit()
        except Exception:
            await self.db.rollback()
            raise

        # The database is the source of truth.  Delete only after commit so a
        # failed commit can never leave a visible version pointing at no file.
        # A failed unlink can at worst leave an unreachable orphan for manual
        # cleanup; it does not affect latest-version selection or downloads.
        try:
            path.unlink(missing_ok=True)
        except OSError:
            logger.warning("Could not remove deleted client APK %s", path, exc_info=True)
        return True
