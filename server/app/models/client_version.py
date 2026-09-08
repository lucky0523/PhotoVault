"""Models returned by the Android client distribution API."""

from pydantic import BaseModel


class ClientVersionInfo(BaseModel):
    """Metadata for one uploaded Android client."""

    id: int
    platform: str
    package_name: str
    version_name: str
    version_code: int
    original_filename: str
    file_size: int
    sha256: str
    release_notes: str | None = None
    created_at: str
    is_latest: bool = False
    download_url: str | None = None
