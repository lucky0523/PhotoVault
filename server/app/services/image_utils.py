"""Shared image-opening helpers used throughout the server.

Centralises Pillow ``Image.open`` so HEIC/HEIF support is enabled in one place.
Apple devices commonly store photos as ``.heic``; plain Pillow cannot decode
them, so PhotoVault installs ``pillow-heif`` as a core dependency and registers
its opener lazily before the first image is opened.

Registration remains defensive: if a damaged deployment is missing the package,
other image formats keep working and HEIC failures are logged once.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("photovault.image_utils")

# Registration is attempted once, lazily, on first open.
_heif_registered: bool | None = None


def _ensure_heif_registered() -> bool:
    """Register the pillow-heif opener once; return whether HEIC is supported."""
    global _heif_registered
    if _heif_registered is None:
        try:
            import pillow_heif  # type: ignore

            pillow_heif.register_heif_opener()
            _heif_registered = True
            logger.info("pillow-heif registered: HEIC/HEIF decoding enabled")
        except Exception:  # noqa: BLE001 - optional dependency
            _heif_registered = False
            logger.info(
                "pillow-heif not installed; HEIC/HEIF photos will be skipped"
            )
    return _heif_registered


def heic_supported() -> bool:
    """Whether HEIC/HEIF decoding is available (pillow-heif installed)."""
    return _ensure_heif_registered()


def open_image(path: str) -> Any:
    """Open an image with Pillow, enabling HEIC/HEIF support when available.

    Returns a PIL ``Image`` (the caller is responsible for closing / converting).
    Registering the HEIF opener is idempotent and cheap after the first call.
    """
    from PIL import Image

    _ensure_heif_registered()
    return Image.open(path)
