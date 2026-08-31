# PhotoVault Server Application

# Single source of truth for the server version.
#
# Previously "0.1.0" was duplicated as a literal in app/main.py (FastAPI
# version), app/api/auth.py (/connection/test) and pyproject.toml, so bumping
# the version meant remembering three places. main.py and auth.py now read it
# from here; pyproject.toml still carries its own copy because packaging
# metadata cannot import the package.
__version__ = "0.1.0"

__all__ = ["__version__"]
