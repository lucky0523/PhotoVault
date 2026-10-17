"""Shared pytest fixtures for PhotoVault server tests."""

import pytest
from httpx import AsyncClient, ASGITransport

from app.main import app
from app.services import path_locks


@pytest.fixture
def async_client():
    """Create an async test client for the FastAPI app."""
    transport = ASGITransport(app=app)
    return AsyncClient(transport=transport, base_url="http://testserver")


@pytest.fixture
def anyio_backend():
    """Use asyncio as the async backend for tests."""
    return "asyncio"


@pytest.fixture(autouse=True)
def _clear_path_locks():
    """Never carry asyncio locks bound to one test's loop into another test."""
    path_locks._entries.clear()
    yield
    path_locks._entries.clear()
