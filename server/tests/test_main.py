"""Tests for the FastAPI application initialization, CORS, and exception handling."""

import pytest
from httpx import AsyncClient, ASGITransport

from app.main import (
    app,
    PhotoVaultException,
    AuthenticationError,
    AuthorizationError,
    NotFoundError,
    ValidationError,
    StorageError,
)


@pytest.fixture
def async_client():
    """Create an async test client for the FastAPI app."""
    transport = ASGITransport(app=app)
    return AsyncClient(transport=transport, base_url="http://testserver")


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_health_check(async_client):
    """Health check endpoint returns status ok with storage info."""
    async with async_client as client:
        response = await client.get("/api/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "storage_available_gb" in data


# ---------------------------------------------------------------------------
# CORS headers
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_cors_headers_present(async_client):
    """CORS headers are included in responses for cross-origin requests."""
    async with async_client as client:
        response = await client.get(
            "/api/v1/health",
            headers={"Origin": "http://localhost:5173"},
        )
    assert response.status_code == 200
    assert "access-control-allow-origin" in response.headers


@pytest.mark.asyncio
async def test_cors_preflight(async_client):
    """CORS preflight (OPTIONS) request is handled correctly."""
    async with async_client as client:
        response = await client.options(
            "/api/v1/health",
            headers={
                "Origin": "http://localhost:5173",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "Authorization",
            },
        )
    assert response.status_code == 200
    assert "access-control-allow-origin" in response.headers
    assert "access-control-allow-methods" in response.headers


# ---------------------------------------------------------------------------
# Custom exception classes
# ---------------------------------------------------------------------------


class TestCustomExceptions:
    """Test custom exception hierarchy and attributes."""

    def test_base_exception_defaults(self):
        exc = PhotoVaultException()
        assert exc.status_code == 500
        assert exc.error_code == "INTERNAL_ERROR"
        assert exc.detail == "An error occurred"

    def test_base_exception_custom(self):
        exc = PhotoVaultException(
            detail="Custom error", status_code=418, error_code="TEAPOT"
        )
        assert exc.status_code == 418
        assert exc.error_code == "TEAPOT"
        assert exc.detail == "Custom error"

    def test_authentication_error(self):
        exc = AuthenticationError()
        assert exc.status_code == 401
        assert exc.error_code == "AUTH_ERROR"
        assert isinstance(exc, PhotoVaultException)

    def test_authorization_error(self):
        exc = AuthorizationError()
        assert exc.status_code == 403
        assert exc.error_code == "FORBIDDEN"
        assert isinstance(exc, PhotoVaultException)

    def test_not_found_error(self):
        exc = NotFoundError()
        assert exc.status_code == 404
        assert exc.error_code == "NOT_FOUND"
        assert isinstance(exc, PhotoVaultException)

    def test_validation_error(self):
        exc = ValidationError()
        assert exc.status_code == 400
        assert exc.error_code == "VALIDATION_ERROR"
        assert isinstance(exc, PhotoVaultException)

    def test_storage_error(self):
        exc = StorageError("Disk full")
        assert exc.status_code == 507
        assert exc.error_code == "STORAGE_ERROR"
        assert exc.detail == "Disk full"
        assert isinstance(exc, PhotoVaultException)


# ---------------------------------------------------------------------------
# Exception handler responses
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_custom_exception_handler(async_client):
    """PhotoVaultException subclasses return proper JSON error responses."""
    from fastapi import Request

    # Add a temporary route that raises a custom exception
    @app.get("/test/auth-error")
    async def raise_auth_error():
        raise AuthenticationError("Invalid token")

    async with async_client as client:
        response = await client.get("/test/auth-error")

    assert response.status_code == 401
    body = response.json()
    assert body["error"] == "AUTH_ERROR"
    assert body["detail"] == "Invalid token"


@pytest.mark.asyncio
async def test_unhandled_exception_handler(async_client):
    """Unhandled exceptions return a generic 500 error without leaking details."""

    @app.get("/test/unhandled-error")
    async def raise_unhandled():
        raise RuntimeError("Something went very wrong internally")

    async with async_client as client:
        response = await client.get("/test/unhandled-error")

    assert response.status_code == 500
    body = response.json()
    assert body["error"] == "INTERNAL_ERROR"
    # Should NOT leak the internal error message
    assert "Something went very wrong" not in body["detail"]
    assert "unexpected error" in body["detail"].lower()


# ---------------------------------------------------------------------------
# App metadata
# ---------------------------------------------------------------------------


def test_app_metadata():
    """App is configured with correct title and version."""
    assert app.title == "PhotoVault"
    assert app.version == "0.1.0"
