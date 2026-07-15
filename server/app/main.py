"""
PhotoVault Server - FastAPI Application Entry Point

A photo backup service for NAS deployment, supporting:
- Multi-user authentication (JWT)
- Chunked upload with resume capability
- SHA-256 deduplication
- Flexible storage path policies
- File browsing and thumbnail generation
"""

import time
import logging
import traceback
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse


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
# Lifespan (startup / shutdown)
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler for startup and shutdown events.

    Startup:
        - Initialize database connection and schema
        - Validate storage root directory
        - Start background tasks (expired session cleanup, disk monitoring)

    Shutdown:
        - Cancel background tasks
        - Close database connections
    """
    from app.core.database import startup_db, shutdown_db
    from app.core.logging import setup_logging
    from app.services.background_tasks import start_background_tasks, stop_background_tasks

    # --- Startup ---
    setup_logging()
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
        version="0.1.0",
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

        # Setup check: block non-setup API endpoints when system is not initialized
        # Only applies to /api/v1/* paths (not static files, health check, etc.)
        # Allow /api/v1/setup/* and /api/v1/health through always
        if (
            path.startswith("/api/v1/")
            and not path.startswith("/api/v1/setup/")
            and path != "/api/v1/health"
        ):
            try:
                import aiosqlite
                from app.core.config import get_settings

                settings = get_settings()
                db_path = settings.database_url
                if db_path.startswith("sqlite+aiosqlite://"):
                    db_path = db_path[len("sqlite+aiosqlite://"):]
                elif db_path.startswith("sqlite://"):
                    db_path = db_path[len("sqlite://"):]
                db = await aiosqlite.connect(db_path)
                try:
                    cursor = await db.execute("SELECT COUNT(*) FROM users")
                    row = await cursor.fetchone()
                    user_count = row[0] if row else 0
                    if user_count == 0:
                        duration_ms = (time.time() - start_time) * 1000
                        logger.info(
                            "%s %s -> 503 (%.1fms) [not initialized]",
                            request.method,
                            path,
                            duration_ms,
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
            except Exception:
                # If we can't check, let the request through
                pass

        try:
            response = await call_next(request)
        except Exception as exc:
            # Catch exceptions that propagate through the middleware stack.
            # Log and return a generic 500 response.
            duration_ms = (time.time() - start_time) * 1000
            logger.error(
                "Unhandled exception on %s %s (%.1fms): %s\n%s",
                request.method,
                request.url.path,
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
            "%s %s -> %d (%.1fms)",
            request.method,
            request.url.path,
            response.status_code,
            duration_ms,
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
        """Health check endpoint with storage availability info."""
        from app.services.background_tasks import get_disk_stats

        disk_stats = get_disk_stats()
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

    application.include_router(setup_router, prefix="/api/v1", tags=["setup"])
    application.include_router(auth_router, prefix="/api/v1", tags=["auth"])
    application.include_router(admin_router, prefix="/api/v1", tags=["admin"])
    application.include_router(backup_router, prefix="/api/v1", tags=["backup"])
    application.include_router(files_router, prefix="/api/v1", tags=["files"])
    application.include_router(server_router, prefix="/api/v1", tags=["server"])
    application.include_router(explore_router, prefix="/api/v1", tags=["explore"])

    # Catch-all route for SPA routing
    # This handles direct access to Vue Router routes like /photos, /timeline, etc.
    # Must be registered AFTER all API routes so API routes take priority
    @application.get("/{full_path:path}")
    async def catch_all(full_path: str):
        """Catch-all route for Single Page Application (SPA) routing.

        Returns index.html for any non-API, non-static path so Vue Router can handle the routing.
        This allows direct access to routes like /photos, /timeline, etc.
        """
        import os
        
        _web_dist_dir = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "web",
            "dist",
        )
        
        # Skip API paths
        if full_path.startswith("api/") or full_path.startswith("api\\"):
            raise HTTPException(status_code=404, detail="Not Found")
        
        # Check if it's a static file that exists
        static_file_path = os.path.join(_web_dist_dir, full_path)
        if os.path.isfile(static_file_path):
            # Return the static file
            from fastapi.responses import FileResponse
            return FileResponse(static_file_path)

        # Otherwise, return index.html for SPA routing
        index_path = os.path.join(_web_dist_dir, "index.html")
        if os.path.exists(index_path):
            with open(index_path, "r", encoding="utf-8") as f:
                content = f.read()
            from fastapi.responses import HTMLResponse
            return HTMLResponse(content=content)
        else:
            raise HTTPException(status_code=404, detail="Frontend not found")


# ---------------------------------------------------------------------------
# Module-level app instance
# ---------------------------------------------------------------------------

app = create_app()


# ---------------------------------------------------------------------------
# Static file serving (production mode)
# ---------------------------------------------------------------------------
# Mount Vue.js build output if web/dist exists.
# This must be AFTER app creation so API routes take priority over static files.

import os

_web_dist_dir = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "web",
    "dist",
)

if os.path.isdir(_web_dist_dir):
    from fastapi.staticfiles import StaticFiles

    app.mount("/", StaticFiles(directory=_web_dist_dir, html=True), name="web")
