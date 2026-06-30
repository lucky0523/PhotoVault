"""Storage-related data models.

Pydantic models for storage policy configuration, file metadata,
and path validation results used by the StoragePathEngine.
"""

from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel


class StoragePolicy(BaseModel):
    """Storage policy configuration for a source folder.

    Controls how files are organized on the NAS:
    - use_custom_path: Whether to use a user-specified custom path instead of
      the default {root}/{user}/{device}/ structure.
    - custom_path: The custom path to use when use_custom_path is True.
    - use_year_month_layer: Whether to add year/month subdirectories based on
      photo capture time.
    """

    use_custom_path: bool = False
    custom_path: Optional[str] = None
    use_year_month_layer: bool = False


class FileMetadata(BaseModel):
    """Metadata about a file being backed up.

    Used by the StoragePathEngine to determine year/month directory placement.

    Attributes:
        exif_time: EXIF capture time (highest priority for year/month).
        file_created_time: File creation time (fallback for year/month).
        sub_folder: Relative sub-folder path within the source folder.
    """

    exif_time: Optional[datetime] = None
    file_created_time: Optional[datetime] = None
    sub_folder: str = ""


class PathValidationResult(BaseModel):
    """Result of path validation.

    Attributes:
        is_valid: Whether the path passes all validation checks.
        error_message: Description of the validation error (empty if valid).
    """

    is_valid: bool
    error_message: str = ""
