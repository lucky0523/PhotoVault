"""
PhotoVault Server - FastAPI Application Entry Point

A photo backup service for NAS deployment, supporting:
- Multi-user authentication (JWT)
- Chunked upload with resume capability
- SHA-256 deduplication
- Flexible storage path policies
- File browsing and thumbnail generation
"""

import asyncio
import time
import logging
import os
import traceback
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exception_handlers import http_exception_handler
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import __version__


# ---------------------------------------------------------------------------
# Logging configuration
# ---------------------------------------------------------------------------

logger = logging.getLogger("photovault")


# ---------------------------------------------------------------------------
# Custom exception classes
# ---------------------------------------------------------------------------


class PhotoVaultException(Exception):
    """Base exception for PhotoVault application errors.

    Attributes:
        status_code: HTTP status code to return.
        detail: Human-readable error message.
        error_code: Machine-readable error code for client handling.
    """

    def __init__(
        self,
        detail: str = "An error occurred",
        status_code: int = 500,
        error_code: str = "INTERNAL_ERROR",
    ) -> None:
        self.detail = detail
        self.status_code = status_code
        self.error_code = error_code
        super().__init__(detail)


class AuthenticationError(PhotoVaultException):
    """Raised when authentication fails."""

    def __init__(self, detail: str = "Authentication failed") -> None:
        super().__init__(detail=detail, status_code=401, error_code="AUTH_ERROR")


class AuthorizationError(PhotoVaultException):
    """Raised when user lacks required permissions."""

    def __init__(self, detail: str = "Permission denied") -> None:
        super().__init__(detail=detail, status_code=403, error_code="FORBIDDEN")


class NotFoundError(PhotoVaultException):
    """Raised when a requested resource is not found."""

    def __init__(self, detail: str = "Resource not found") -> None:
        super().__init__(detail=detail, status_code=404, error_code="NOT_FOUND")


class ValidationError(PhotoVaultException):
    """Raised when request data fails validation."""

    def __init__(self, detail: str = "Validation failed") -> None:
        super().__init__(detail=detail, status_code=400, error_code="VALIDATION_ERROR")


class StorageError(PhotoVaultException):
    """Raised when storage operations fail (disk full, permission denied, etc.)."""

    def __init__(self, detail: str = "Storage operation failed") -> None:
        super().__init__(detail=detail, status_code=507, error_code="STORAGE_ERROR")


# ---------------------------------------------------------------------------
# Request logging helpers
# ---------------------------------------------------------------------------


# Cap on the X-Forwarded-For value copied into a log line. The header is
# attacker-controlled and unbounded in principle; a long chain adds no diagnostic
# value over its first few hops.
_MAX_FORWARDED_LEN = 128


def _client_addr(request: Request) -> str:
    """Describe who made a request, for the access log line.

    Reports the peer address as resolved by the ASGI server. Uvicorn runs
    ``ProxyHeadersMiddleware`` by default and rewrites this from
    ``X-Forwarded-For`` when the connection comes from a trusted proxy
    (``--forwarded-allow-ips``, default ``127.0.0.1``), so for a trusted proxy this
    is already the real client.

    When ``X-Forwarded-For`` is present and differs, the raw header is appended.
    That covers the Docker deployment, where Caddy connects from the container
    network rather than ``127.0.0.1``, so uvicorn does *not* trust it and the peer
    address would otherwise be the proxy on every single request.

    The header is deliberately reproduced verbatim rather than reduced to one hop:
    uvicorn and this function would otherwise pick different hops out of the same
    chain and the line would show two unrelated-looking addresses. It is also
    client-supplied and therefore spoofable, which is why it is labelled ``xff``
    instead of being presented as the client address.

    Args:
        request: The incoming request.

    Returns:
        ``"192.168.1.50"`` when unproxied, or
        ``'172.18.0.3 xff "192.168.1.50"'`` when a forwarding header is present.
        The peer is ``"-"`` if the transport exposes none (some ASGI test
        transports).
    """
    peer = request.client.host if request.client else "-"

    forwarded = request.headers.get("x-forwarded-for", "")
    # Strip control characters so a crafted header cannot forge extra log lines,
    # then collapse whitespace to keep the entry on one line.
    forwarded = " ".join(forwarded.split())
    forwarded = "".join(c for c in forwarded if c.isprintable())

    if not forwarded or forwarded == peer:
        return peer

    if len(forwarded) > _MAX_FORWARDED_LEN:
        forwarded = forwarded[:_MAX_FORWARDED_LEN] + "..."

    return f'{peer} xff "{forwarded}"'


# ---------------------------------------------------------------------------
# Lifespan (startup / shutdown)
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler for startup and shutdown events.

    Startup:
        - Create/validate the configured storage directories
        - Initialize database connection and schema
        - Start background tasks (expired session cleanup, disk monitoring)

    Shutdown:
        - Cancel background tasks
        - Close database connections
    """
    from app.core.config import ensure_runtime_directories
    from app.core.database import startup_db, shutdown_db
    from app.core.logging import setup_logging
    from app.core.runtime import mark_started
    from app.services.background_tasks import start_background_tasks, stop_background_tasks

    # --- Startup ---
    # Runs before setup_logging so that a misconfigured log_dir fails with a
    # message naming the setting, rather than as a bare mkdir traceback.
    ensure_runtime_directories()
    setup_logging()
    mark_started()
    logger.info("PhotoVault server starting up...")
    await startup_db()
    background_tasks = await start_background_tasks()
    yield
    # --- Shutdown ---
    logger.info("PhotoVault server shutting down...")
    await stop_background_tasks(background_tasks)
    await shutdown_db()


# ---------------------------------------------------------------------------
# Application factory
# ---------------------------------------------------------------------------


def create_app() -> FastAPI:
    """Create and configure the FastAPI application instance."""

    application = FastAPI(
        title="PhotoVault",
        description="手机图片备份系统服务端",
        version=__version__,
        lifespan=lifespan,
    )

    # --- CORS Middleware ---
    # Allow all origins for development. Mobile clients (Android/iOS) and
    # the Vue.js web frontend may connect from different origins.
    application.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # --- Exception Handlers ---
    _register_exception_handlers(application)

    # --- Request Logging & Setup Check Middleware ---
    @application.middleware("http")
    async def request_logging_middleware(request: Request, call_next):
        """Log every request with method, path, status code, and duration.

        Also enforces the setup check: when no users exist in the database,
        only /api/v1/setup/* endpoints are accessible. All other API endpoints
        return 503 "System not initialized".

        Catches unhandled exceptions that escape the route handlers,
        ensuring they are converted to a proper JSON error response.
        """
        start_time = time.time()
        path = request.url.path
        # Resolved once up front so all three log sites below agree, and so the
        # value is still available if the request object is consumed downstream.
        client = _client_addr(request)

        # Setup check: block non-setup API endpoints when system is not initialized
        # Only applies to /api/v1/* paths (not static files, health check, etc.)
        # Allow /api/v1/setup/* and /api/v1/health through always
        if (
            path.startswith("/api/v1/")
            and not path.startswith("/api/v1/setup/")
            and path != "/api/v1/health"
        ):
            try:
                import os

                import aiosqlite
                from app.core.config import get_settings, needs_provisioning

                settings = get_settings()

                # Before provisioning there is deliberately no database at all.
                # This has to short-circuit: falling through to aiosqlite.connect
                # would *create* an empty file at the pre-provisioning path (wrong
                # volume), and the follow-up query would then fail on the missing
                # table and be swallowed by the except below — letting every
                # endpoint through on an uninitialised server.
                if needs_provisioning(settings) or not os.path.isfile(
                    settings.database_path
                ):
                    duration_ms = (time.time() - start_time) * 1000
                    logger.info(
                        "%s %s -> 503 (%.1fms) from %s [not provisioned]",
                        request.method,
                        path,
                        duration_ms,
                        client,
                    )
                    return JSONResponse(
                        status_code=503,
                        content={
                            "error": "NOT_INITIALIZED",
                            "detail": "System not initialized, please complete setup",
                        },
                    )

                db = await aiosqlite.connect(settings.database_path)
                try:
                    cursor = await db.execute("SELECT COUNT(*) FROM users")
                    row = await cursor.fetchone()
                    user_count = row[0] if row else 0
                    if user_count == 0:
                        duration_ms = (time.time() - start_time) * 1000
                        logger.info(
                            "%s %s -> 503 (%.1fms) from %s [not initialized]",
                            request.method,
                            path,
                            duration_ms,
                            client,
                        )
                        return JSONResponse(
                            status_code=503,
                            content={
                                "error": "NOT_INITIALIZED",
                                "detail": "System not initialized, please complete setup",
                            },
                        )
                finally:
                    await db.close()
            except Exception as exc:
                # A failed setup check can indicate an unreadable or corrupt
                # database. Fail closed instead of allowing protected business
                # endpoints to run against an unknown state.
                duration_ms = (time.time() - start_time) * 1000
                logger.exception(
                    "Setup gateway check failed for %s %s from %s (%.1fms)",
                    request.method,
                    path,
                    client,
                    duration_ms,
                )
                return JSONResponse(
                    status_code=503,
                    content={
                        "error": "SERVICE_UNAVAILABLE",
                        "detail": "System initialization status is unavailable",
                    },
                )

        try:
            response = await call_next(request)
        except Exception as exc:
            # Catch exceptions that propagate through the middleware stack.
            # Log and return a generic 500 response.
            duration_ms = (time.time() - start_time) * 1000
            logger.error(
                "Unhandled exception on %s %s from %s (%.1fms): %s\n%s",
                request.method,
                request.url.path,
                client,
                duration_ms,
                str(exc),
                traceback.format_exc(),
            )
            return JSONResponse(
                status_code=500,
                content={
                    "error": "INTERNAL_ERROR",
                    "detail": "An unexpected error occurred. Please try again later.",
                },
            )

        duration_ms = (time.time() - start_time) * 1000
        logger.info(
            "%s %s -> %d (%.1fms) from %s",
            request.method,
            request.url.path,
            response.status_code,
            duration_ms,
            client,
        )
        return response

    # --- Routes ---
    _register_routes(application)

    return application


# ---------------------------------------------------------------------------
# Exception handler registration
# ---------------------------------------------------------------------------


def _register_exception_handlers(application: FastAPI) -> None:
    """Register global exception handlers."""

    @application.exception_handler(PhotoVaultException)
    async def photovault_exception_handler(
        request: Request, exc: PhotoVaultException
    ) -> JSONResponse:
        """Handle all PhotoVault custom exceptions with a consistent JSON format."""
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error": exc.error_code,
                "detail": exc.detail,
            },
        )

    @application.exception_handler(Exception)
    async def unhandled_exception_handler(
        request: Request, exc: Exception
    ) -> JSONResponse:
        """Catch-all handler for unhandled exceptions.

        Logs the full traceback and returns a generic error response to avoid
        leaking internal details to clients.
        """
        logger.error(
            "Unhandled exception on %s %s: %s\n%s",
            request.method,
            request.url.path,
            str(exc),
            traceback.format_exc(),
        )
        return JSONResponse(
            status_code=500,
            content={
                "error": "INTERNAL_ERROR",
                "detail": "An unexpected error occurred. Please try again later.",
            },
        )


# ---------------------------------------------------------------------------
# Route registration
# ---------------------------------------------------------------------------


def _register_routes(application: FastAPI) -> None:
    """Register API routes and routers."""

    @application.get("/api/v1/health")
    async def health_check():
        """Health check endpoint with freshly measured storage availability."""
        from app.services.background_tasks import refresh_disk_stats

        # Disk usage is a filesystem call; refresh on every health request so
        # startup never exposes the module's initial all-zero placeholder.
        disk_stats = await asyncio.to_thread(refresh_disk_stats)
        return {
            "status": "ok",
            "storage_available_gb": disk_stats["available_gb"],
        }

    from app.api.setup import router as setup_router
    from app.api.auth import router as auth_router
    from app.api.admin import router as admin_router
    from app.api.backup import router as backup_router
    from app.api.files import router as files_router
    from app.api.server import router as server_router
    from app.api.explore import router as explore_router
    from app.api.client_versions import router as client_versions_router
    from app.api.fnos import router as fnos_router

    application.include_router(setup_router, prefix="/api/v1", tags=["setup"])
    application.include_router(auth_router, prefix="/api/v1", tags=["auth"])
    application.include_router(admin_router, prefix="/api/v1", tags=["admin"])
    application.include_router(backup_router, prefix="/api/v1", tags=["backup"])
    application.include_router(files_router, prefix="/api/v1", tags=["files"])
    application.include_router(server_router, prefix="/api/v1", tags=["server"])
    application.include_router(explore_router, prefix="/api/v1", tags=["explore"])
    application.include_router(
        client_versions_router, prefix="/api/v1", tags=["client versions"]
    )
    # 飞牛（fnOS）开放能力代理。仅管理员可用；不在飞牛运行时中调用会返回 503。
    # 前端只有飞牛构建才会用到它，见 server/app/api/fnos.py 的说明。
    application.include_router(fnos_router, prefix="/api/v1", tags=["fnos"])

    # API routes above are registered before static mounts. Vue's generated
    # assets are served by StaticFiles, which canonicalizes paths and enforces
    # containment. The SPA fallback runs only after normal routing returns 404
    # and asks StaticFiles for the one fixed index path.
    web_dist_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        "web",
        "dist",
    )
    if os.path.isdir(web_dist_dir):
        web_static = StaticFiles(directory=web_dist_dir, html=True)
        assets_dir = os.path.join(web_dist_dir, "assets")
        if os.path.isdir(assets_dir):
            application.mount(
                "/assets",
                StaticFiles(directory=assets_dir),
                name="web-assets",
            )

        @application.get("/icon.png", include_in_schema=False)
        async def web_icon(request: Request):
            return await web_static.get_response("icon.png", request.scope)

        @application.exception_handler(StarletteHTTPException)
        async def spa_history_fallback(
            request: Request, exc: StarletteHTTPException
        ):
            """Serve the fixed SPA shell only for otherwise-unmatched pages.

            An exception handler does not occupy a wildcard route, so routes
            registered later (plugins and tests included) remain reachable. Its
            response also returns through the normal logging and CORS stack.
            """
            path = request.url.path
            if (
                exc.status_code == 404
                and request.method in {"GET", "HEAD"}
                and path != "/api"
                and not path.startswith(("/api/", "/assets/"))
            ):
                return await web_static.get_response("index.html", request.scope)
            return await http_exception_handler(request, exc)


# ---------------------------------------------------------------------------
# Module-level app instance
# ---------------------------------------------------------------------------

app = create_app()
