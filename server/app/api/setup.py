"""Setup/initialization API endpoints.

Endpoints:
- GET  /api/v1/setup/status   (check if initialized / what setup still needs)
- POST /api/v1/setup/init     (first-time admin creation, optionally with a work dir)

Two-phase provisioning (fnOS)
-----------------------------
On the fnOS package the photos and the database must live in a directory the
platform has granted the app user an ACL for, and that grant can only be obtained
from inside the running app (``trim.file.sharedAccess``). So the server starts with
**no database**, the wizard collects the administrator account *and* the working
directory, and this module then creates everything inside the chosen directory in
one shot:

    1. verify the directory is usable (exists / creatable / actually writable)
    2. create the database there and its schema
    3. create the administrator account in it
    4. write the working-directory pointer  <- commit point

Nothing is persisted before step 4, so an abandoned or failed wizard leaves the
server exactly as it was and the next start runs the wizard again. That is also why
the credentials arrive together with the directory in a single request instead of
being held server-side between two calls.

Deployments that do not opt in (``require_workdir_setup`` false: Docker, local
development) keep the original single-step behaviour — the database already exists
and ``work_dir`` is rejected.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Optional

import aiosqlite
from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, field_validator

from app.core.config import (
    Settings,
    get_settings,
    needs_provisioning,
    read_previous_workdir_pointer,
    set_workdir,
)
from app.core.database import init_db
from app.services.auth_service import AuthService

logger = logging.getLogger("photovault.api.setup")

router = APIRouter()

#: Directories the working directory may never be placed in. A mistyped path here
#: would scatter tens of gigabytes of photos across the system disk.
_FORBIDDEN_PREFIXES = (
    "/bin",
    "/boot",
    "/dev",
    "/etc",
    "/lib",
    "/lib32",
    "/lib64",
    "/proc",
    "/root",
    "/run",
    "/sbin",
    "/sys",
    "/usr",
    "/var/apps",
)


# ---------------------------------------------------------------------------
# Request/Response models
# ---------------------------------------------------------------------------


class SetupStatusResponse(BaseModel):
    """Response for the setup status check.

    Attributes:
        initialized: Setup is complete; the app is ready to log into.
        needs_work_dir: The wizard must ask for a working directory. False for
            deployments that configure storage through the environment.
    """

    initialized: bool
    needs_work_dir: bool = False


class WorkDirOptionsResponse(BaseModel):
    """Directories the wizard may offer as the working directory.

    Attributes:
        default_path: The platform-managed share, always writable. Offered as the
            fallback so the wizard can always be completed even when the fnOS
            gateway is unreachable.
        authorized_paths: Directories the administrator has granted this app access
            to via ``trim.file.sharedAccess``.
        gateway_available: Whether the authorized list could be read at all.
        gateway_reason: Why it could not, when ``gateway_available`` is false.
        previous_path: The working directory used before an uninstall (or before the
            grant on it was lost). Shown as a recovery hint: re-authorizing this exact
            directory brings the old library back, and since the picker only lists
            authorized directories the administrator would otherwise have no way to
            find it again.
    """

    default_path: str
    authorized_paths: list[str] = []
    gateway_available: bool = False
    gateway_reason: str = ""
    previous_path: str = ""


class SetupInitRequest(BaseModel):
    """Request body for initial admin account creation."""

    username: str
    password: str

    #: Absolute path to the working directory for photos and the database.
    #: Required when the server reports ``needs_work_dir``, rejected otherwise.
    work_dir: Optional[str] = None

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

    @field_validator("work_dir")
    @classmethod
    def work_dir_must_be_sane(cls, v: Optional[str]) -> Optional[str]:
        """Reject paths that cannot possibly be a valid photo location."""
        if v is None:
            return None

        candidate = v.strip()
        if not candidate:
            return None

        if not os.path.isabs(candidate):
            raise ValueError("工作目录必须是以 / 开头的绝对路径")
        if any(ch in candidate for ch in ("\n", "\r", "\t", "\0")):
            raise ValueError("工作目录不能包含控制字符")
        if ".." in Path(candidate).parts:
            raise ValueError("工作目录不能包含 .. 路径段，请填写展开后的完整路径")

        normalised = candidate.rstrip("/") or "/"
        if normalised == "/" or normalised in _FORBIDDEN_PREFIXES:
            raise ValueError(f"'{normalised}' 是系统目录，不能用作工作目录")
        for prefix in _FORBIDDEN_PREFIXES:
            if normalised.startswith(prefix + "/"):
                raise ValueError(f"'{normalised}' 位于系统目录下，不能用作工作目录")

        return normalised


class SetupInitResponse(BaseModel):
    """Response after successful setup."""

    success: bool
    #: The created administrator. Empty when an existing library was adopted.
    username: str = ""
    #: Where photos and the database ended up. Empty when storage came from config.
    work_dir: str = ""
    #: True when the chosen directory already held a library, so no account was
    #: created and the administrator should sign in with their original one.
    adopted: bool = False
    #: Human-readable note for the wizard to display.
    message: str = ""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _count_users(db_path: str) -> int:
    """Return the number of accounts in the database at ``db_path``.

    Returns 0 when the file or the schema is not there yet, so callers can treat
    "no database" and "empty database" alike.
    """
    if not os.path.isfile(db_path):
        return 0

    db = await aiosqlite.connect(db_path)
    try:
        cursor = await db.execute("SELECT COUNT(*) FROM users")
        row = await cursor.fetchone()
        return int(row[0]) if row else 0
    except aiosqlite.Error:
        return 0
    finally:
        await db.close()


async def _is_initialized(db: aiosqlite.Connection) -> bool:
    """Check if the system has been initialized (at least one user exists)."""
    cursor = await db.execute("SELECT COUNT(*) FROM users")
    row = await cursor.fetchone()
    count = row[0] if row else 0
    return count > 0


def _prepare_work_dir(path: str) -> None:
    """Make sure ``path`` exists and the service can actually write into it.

    Raises:
        HTTPException: 400 with an actionable message when the directory cannot be
            used. The message names the authorization step, because on fnOS this
            failing is the normal outcome for a directory the administrator has not
            shared with the app yet.
    """
    target = Path(path)
    parent = target.parent

    # The parent has to exist already. Otherwise mkdir(parents=True) would happily
    # materialise a whole path on the system disk when a volume is not mounted or
    # the path was mistyped, and photos would pile up there.
    if not parent.is_dir():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"上层目录不存在：{parent}。请确认存储空间已挂载、路径拼写正确。"
            ),
        )

    # mkdir on an existing directory is a no-op and tells us nothing about write
    # access, so the probe below is what actually decides.
    try:
        target.mkdir(exist_ok=True)
    except OSError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"无法创建目录 {target}：{exc.strerror or exc}。"
                "请先在飞牛文件管理器中把该目录共享给 PhotoVault，再重试。"
            ),
        ) from exc

    # Real write probe rather than os.access: the shared folders use Windows ACLs,
    # under which the POSIX permission bits give false negatives.
    probe = target / f".pv_setup_probe.{os.getpid()}"
    try:
        probe.touch()
    except OSError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"目录 {target} 不可写：{exc.strerror or exc}。"
                "飞牛只会自动为应用自己申请的共享文件夹授予写权限，"
                "请在上一步用「选择目录」完成授权后再提交。"
            ),
        ) from exc
    finally:
        try:
            probe.unlink()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/setup/status", response_model=SetupStatusResponse)
async def get_setup_status() -> SetupStatusResponse:
    """Report whether setup is complete and what it still needs.

    Deliberately does not take a database dependency: before provisioning there is
    no database, and connecting would create an empty file in the pre-provisioning
    location. The user count is read straight from the configured path instead.

    This endpoint does NOT require authentication.
    """
    settings = get_settings()

    if needs_provisioning(settings):
        return SetupStatusResponse(initialized=False, needs_work_dir=True)

    user_count = await _count_users(settings.database_path)
    return SetupStatusResponse(
        initialized=user_count > 0,
        needs_work_dir=False,
    )


@router.get("/setup/work-dir-options", response_model=WorkDirOptionsResponse)
async def get_work_dir_options() -> WorkDirOptionsResponse:
    """List the directories the wizard can offer as the working directory.

    Exists because the wizard has to show *already authorized* directories, and the
    regular ``/fnos/shared-folders`` endpoint requires an administrator — which does
    not exist yet at this point. This one is therefore unauthenticated, and kept
    narrow to match:

    - It is only served while provisioning is still pending; afterwards it 403s, so
      it is not a standing unauthenticated way to enumerate directories.
    - It returns nothing that is not already implied by the setup flow: before
      provisioning, ``/setup/init`` is open to anyone who can reach the port anyway
      (that is inherent to first-run setup), so exposing the paths the fnOS
      administrator deliberately granted this app adds no meaningful attack surface.
    """
    settings = get_settings()

    if not needs_provisioning(settings):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="System already initialized",
        )

    response = WorkDirOptionsResponse(
        default_path=settings.storage_root,
        previous_path=read_previous_workdir_pointer(settings),
    )

    # Best-effort: a missing gateway must not block setup, because default_path is
    # always a usable answer. The reason is reported so the administrator can tell
    # "no directories authorized yet" apart from "cannot talk to fnOS".
    try:
        from app.core.fnos import (
            FnosGatewayError,
            FnosUnavailableError,
            call_gateway,
        )

        data = await call_gateway("trim.file.getSharedAccessibleFolders")
        raw = data.get("paths")
        paths = [p for p in raw if isinstance(p, str)] if isinstance(raw, list) else []
        response.authorized_paths = [
            p for p in paths if p and p.rstrip("/") != settings.storage_root.rstrip("/")
        ]
        response.gateway_available = True
    except FnosUnavailableError as exc:
        response.gateway_reason = str(exc)
    except FnosGatewayError as exc:
        response.gateway_reason = exc.msg
        logger.warning("Could not list authorized folders during setup: %s", exc.msg)
    except Exception as exc:  # pragma: no cover - defensive
        response.gateway_reason = str(exc)
        logger.warning("Unexpected error listing authorized folders", exc_info=True)

    return response


@router.post("/setup/init", response_model=SetupInitResponse)
async def setup_init(body: SetupInitRequest) -> SetupInitResponse:
    """Complete first-time setup.

    Creates the administrator account, and — when the deployment defers storage to
    the wizard — the working directory and the database inside it. Everything
    happens in one request so that an abandoned wizard leaves nothing behind.

    Only works while the system has no users; afterwards it returns 403.
    """
    settings = get_settings()
    provisioning = needs_provisioning(settings)

    if provisioning:
        return await _init_with_work_dir(body, settings)

    return await _init_existing_database(body, settings)


async def _init_with_work_dir(
    body: SetupInitRequest, settings: Settings
) -> SetupInitResponse:
    """Provision a working directory, then create the database and admin in it."""
    if not body.work_dir:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="请先选择并授权一个工作目录，用于存放照片和数据库。",
        )

    work_dir = body.work_dir
    _prepare_work_dir(work_dir)

    db_path = str(Path(work_dir) / "photovault.db")

    # Reinstall recovery: a database already sitting in the chosen directory means
    # the administrator is pointing at an existing library. Adopt it instead of
    # writing a second account into it — their original credentials still apply.
    if await _count_users(db_path) > 0:
        # Apply idempotent schema migrations before adopting an existing library.
        # This creates server_metadata/instance_id immediately, so the first login
        # after a reinstall does not have to wait for a process restart.
        await init_db(db_path)
        # Adopting is a success, not an error: provisioning is now complete and the
        # library is usable. Reported with adopted=True so the wizard can send the
        # administrator to the login page instead of claiming an account was made.
        set_workdir(work_dir, settings)
        logger.info("Adopted existing PhotoVault library at %s", work_dir)
        return SetupInitResponse(
            success=True,
            work_dir=work_dir,
            adopted=True,
            message=(
                "该目录中已存在 PhotoVault 数据，已直接沿用。"
                "请使用原有的管理员账号登录，本次填写的账号未被创建。"
            ),
        )

    await init_db(db_path)

    db = await aiosqlite.connect(db_path)
    db.row_factory = aiosqlite.Row
    try:
        await db.execute("PRAGMA foreign_keys=ON;")
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
    finally:
        await db.close()

    # Commit point: from here on the server uses the new location. Written last so
    # that a failure above leaves no pointer and the wizard simply runs again.
    set_workdir(work_dir, settings)

    logger.info(
        "Setup complete: admin %s created, working directory %s",
        user_info.username,
        work_dir,
    )
    return SetupInitResponse(
        success=True, username=user_info.username, work_dir=work_dir
    )


async def _init_existing_database(
    body: SetupInitRequest, settings: Settings
) -> SetupInitResponse:
    """Original single-step path: the database already exists where config says."""
    db = await aiosqlite.connect(settings.database_path)
    db.row_factory = aiosqlite.Row
    try:
        await db.execute("PRAGMA foreign_keys=ON;")

        # Checked before the work_dir rejection below so that a repeat call on a
        # live system always answers 403, regardless of what else the body carries.
        if await _is_initialized(db):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="System already initialized",
            )

        if body.work_dir:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    "本部署的存储位置由配置决定（PHOTOVAULT_MEDIA_ROOT / "
                    "PHOTOVAULT_DATABASE_URL），不能在初始化时指定工作目录。"
                ),
            )

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
    finally:
        await db.close()

    logger.info("Initial admin account created: %s", user_info.username)
    return SetupInitResponse(success=True, username=user_info.username)
