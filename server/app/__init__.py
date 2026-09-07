# PhotoVault Server Application

# Single source of truth for the server version.
#
# Runtime API and FastAPI metadata read this value. pyproject.toml keeps a
# synchronized copy because package metadata cannot import the application.
__version__ = "1.0"

__all__ = ["__version__"]
