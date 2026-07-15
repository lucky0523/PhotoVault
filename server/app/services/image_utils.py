"""Shared image-opening helpers for the analysis pipeline.

Centralises Pillow ``Image.open`` so HEIC/HEIF support can be enabled in one
place. Apple devices commonly store photos as ``.heic``; plain Pillow cannot
decode them, which means their EXIF/GPS and pixels (for face/scene) are missed.

If the optional ``pillow-heif`` package is installed we register its opener so
``Image.open`` transparently handles ``.heic`` / ``.heif`` files. When it is not
installed everything else keeps working; only HEIC files are skipped (logged
once), consistent with PhotoVault's optional-dependency model.
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
