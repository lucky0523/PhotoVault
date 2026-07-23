"""Application configuration.

Manages server settings loaded from config.yaml or environment variables:
- Server host/port
- Storage root path
- Auth token expiry
- Chunk size
- Session expiry

Configuration priority (highest to lowest):
1. Environment variables (prefixed with PHOTOVAULT_)
2. config.yaml file (if present)
3. Default values
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

import yaml
from pydantic import field_validator, model_validator
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource


def _load_yaml_config() -> dict[str, Any]:
    """Load configuration from config.yaml if it exists.

    Searches for config.yaml in the following locations (first found wins):
    1. PHOTOVAULT_CONFIG_PATH environment variable
    2. Current working directory
    3. Server root directory (parent of app/)
    """
    # Check explicit path from env var
    config_path_env = os.environ.get("PHOTOVAULT_CONFIG_PATH")
    if config_path_env:
        path = Path(config_path_env)
        if path.is_file():
            with open(path) as f:
                return yaml.safe_load(f) or {}
        return {}

    # Check common locations
    candidates = [
        Path.cwd() / "config.yaml",
        Path(__file__).resolve().parent.parent.parent / "config.yaml",
    ]
    for candidate in candidates:
        if candidate.is_file():
            with open(candidate) as f:
                return yaml.safe_load(f) or {}

    return {}


def _flatten_yaml(data: dict[str, Any]) -> dict[str, Any]:
    """Flatten nested YAML config into flat key-value pairs matching Settings fields."""
    flat: dict[str, Any] = {}
    if not data:
        return flat

    server = data.get("server", {})
    if server:
        if "host" in server:
            flat["server_host"] = server["host"]
        if "port" in server:
            flat["server_port"] = server["port"]

    storage = data.get("storage", {})
    if storage:
        if "root" in storage:
            flat["storage_root"] = storage["root"]

    auth = data.get("auth", {})
    if auth:
        if "access_token_expire_hours" in auth:
            flat["access_token_expire_hours"] = auth["access_token_expire_hours"]
        if "refresh_token_expire_days" in auth:
            flat["refresh_token_expire_days"] = auth["refresh_token_expire_days"]
        if "max_users" in auth:
            flat["max_users"] = auth["max_users"]
        if "jwt_secret_key" in auth:
            flat["jwt_secret_key"] = auth["jwt_secret_key"]
        if "allow_registration" in auth:
            flat["allow_registration"] = auth["allow_registration"]

    backup = data.get("backup", {})
    if backup:
        if "chunk_size_mb" in backup:
            flat["chunk_size_mb"] = backup["chunk_size_mb"]
        if "session_expire_days" in backup:
            flat["session_expire_days"] = backup["session_expire_days"]

    trash = data.get("trash", {})
    if trash:
        if "retention_days" in trash:
            flat["trash_retention_days"] = trash["retention_days"]

    logging_cfg = data.get("logging", {})
    if logging_cfg:
        if "level" in logging_cfg:
            flat["log_level"] = logging_cfg["level"]
        if "dir" in logging_cfg:
            flat["log_dir"] = logging_cfg["dir"]

    analysis = data.get("analysis", {})
    if analysis:
        if "enable_place" in analysis:
            flat["enable_place"] = analysis["enable_place"]
        if "enable_scene" in analysis:
            flat["enable_scene"] = analysis["enable_scene"]
        if "enable_face" in analysis:
            flat["enable_face"] = analysis["enable_face"]
        if "models_root" in analysis:
            flat["models_root"] = analysis["models_root"]
        if "scene_min_confidence" in analysis:
            flat["scene_min_confidence"] = analysis["scene_min_confidence"]
        if "face_det_min_score" in analysis:
            flat["face_det_min_score"] = analysis["face_det_min_score"]
        if "face_cluster_similarity" in analysis:
            flat["face_cluster_similarity"] = analysis["face_cluster_similarity"]

    # database_url at top level
    if "database_url" in data:
        flat["database_url"] = data["database_url"]

    return flat


class YamlSettingsSource(PydanticBaseSettingsSource):
    """Custom settings source that reads from a YAML config file."""

    def __init__(self, settings_cls: type[BaseSettings]):
        super().__init__(settings_cls)
        self._yaml_data = _flatten_yaml(_load_yaml_config())

    def get_field_value(
        self, field: Any, field_name: str
    ) -> tuple[Any, str, bool]:
        val = self._yaml_data.get(field_name)
        return val, field_name, val is not None

    def __call__(self) -> dict[str, Any]:
        return {k: v for k, v in self._yaml_data.items() if v is not None}


class Settings(BaseSettings):
    """PhotoVault application settings.

    Values can be set via:
    - Environment variables prefixed with PHOTOVAULT_ (e.g. PHOTOVAULT_STORAGE_ROOT)
    - A config.yaml file
    - Defaults defined here
    """

    # Server
    server_host: str = "127.0.0.1"
    server_port: int = 8000

    # Storage
    storage_root: str = "/data/photovault"

    # Auth
    access_token_expire_hours: int = 24
    refresh_token_expire_days: int = 7
    max_users: int = 20
    jwt_secret_key: str = "change-me-in-production"
    allow_registration: bool = False

    # Backup
    chunk_size_mb: int = 2
    session_expire_days: int = 7

    # Trash
    trash_retention_days: int = 30

    # Logging
    log_level: str = "INFO"
    log_dir: str = ""

    # Database
    database_url: str = ""

    # Analysis (people / places / scenes)
    enable_place: bool = True
    enable_scene: bool = False
    enable_face: bool = False
    models_root: str = ""  # default: f"{storage_root}/.models"
    scene_min_confidence: float = 0.3
    face_det_min_score: float = 0.5
    face_cluster_similarity: float = 0.5

    model_config = {
        "env_prefix": "PHOTOVAULT_",
        "env_file": ".env",
        "env_file_encoding": "utf-8",
        "extra": "ignore",
    }

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        """Customize settings source priority.

        Priority (highest to lowest):
        1. init_settings (explicit constructor args)
        2. env_settings (environment variables)
        3. dotenv_settings (.env file)
        4. yaml_settings (config.yaml)
        5. file_secret_settings
        """
        return (
            init_settings,
            env_settings,
            dotenv_settings,
            YamlSettingsSource(settings_cls),
            file_secret_settings,
        )

    @field_validator("storage_root")
    @classmethod
    def storage_root_must_be_absolute(cls, v: str) -> str:
        """Validate that storage_root is an absolute path."""
        if not os.path.isabs(v):
            raise ValueError(f"storage_root must be an absolute path, got: {v!r}")
        return v

    @field_validator("chunk_size_mb")
    @classmethod
    def chunk_size_must_be_positive(cls, v: int) -> int:
        """Validate that chunk_size_mb is greater than zero."""
        if v <= 0:
            raise ValueError(f"chunk_size_mb must be > 0, got: {v}")
        return v

    @field_validator("access_token_expire_hours")
    @classmethod
    def access_token_expire_must_be_positive(cls, v: int) -> int:
        """Validate that access_token_expire_hours is greater than zero."""
        if v <= 0:
            raise ValueError(f"access_token_expire_hours must be > 0, got: {v}")
        return v

    @field_validator("refresh_token_expire_days")
    @classmethod
    def refresh_token_expire_must_be_positive(cls, v: int) -> int:
        """Validate that refresh_token_expire_days is greater than zero."""
        if v <= 0:
            raise ValueError(f"refresh_token_expire_days must be > 0, got: {v}")
        return v

    @field_validator("max_users")
    @classmethod
    def max_users_must_be_positive(cls, v: int) -> int:
        """Validate that max_users is greater than zero."""
        if v <= 0:
            raise ValueError(f"max_users must be > 0, got: {v}")
        return v

    @field_validator("server_port")
    @classmethod
    def server_port_must_be_valid(cls, v: int) -> int:
        """Validate that server_port is in valid range."""
        if not (1 <= v <= 65535):
            raise ValueError(f"server_port must be between 1 and 65535, got: {v}")
        return v

    @field_validator("session_expire_days")
    @classmethod
    def session_expire_must_be_positive(cls, v: int) -> int:
        """Validate that session_expire_days is greater than zero."""
        if v <= 0:
            raise ValueError(f"session_expire_days must be > 0, got: {v}")
        return v

    @field_validator("trash_retention_days")
    @classmethod
    def trash_retention_must_be_positive(cls, v: int) -> int:
        """Validate that trash_retention_days is greater than zero."""
        if v <= 0:
            raise ValueError(f"trash_retention_days must be > 0, got: {v}")
        return v

    @model_validator(mode="after")
    def set_database_url_default(self) -> "Settings":
        """Set database_url and log_dir defaults based on storage_root if not explicitly provided."""
        if not self.database_url:
            self.database_url = f"{self.storage_root}/photovault.db"
        if not self.log_dir:
            self.log_dir = f"{self.storage_root}/logs"
        if not self.models_root:
            self.models_root = f"{self.storage_root}/.models"
        # Runtime UI toggles (persisted to a small JSON file) win for the three
        # analysis flags, so the manage page can enable/disable dimensions
        # without editing config.yaml or restarting.
        self._apply_analysis_overrides()
        return self

    def _apply_analysis_overrides(self) -> None:
        """Load persisted analysis-flag overrides (if any) over env/yaml values."""
        path = analysis_flags_path(self.storage_root)
        try:
            if path.is_file():
                data = json.loads(path.read_text(encoding="utf-8"))
                for key in ("enable_place", "enable_scene", "enable_face"):
                    if isinstance(data.get(key), bool):
                        object.__setattr__(self, key, data[key])
        except Exception:  # pragma: no cover - defensive: never fail startup
            logging.getLogger("photovault.config").warning(
                "Could not read analysis flag overrides at %s", path, exc_info=True
            )

    @property
    def chunk_size_bytes(self) -> int:
        """Return chunk size in bytes."""
        return self.chunk_size_mb * 1024 * 1024


def load_settings(**overrides: Any) -> Settings:
    """Create a Settings instance.

    Priority: init overrides > env vars > .env file > yaml config > defaults.
    """
    return Settings(**overrides)


# Module-level singleton
_settings: Settings | None = None


def get_settings() -> Settings:
    """Get the application settings singleton.

    Creates the settings instance on first call, then returns the cached instance.
    """
    global _settings
    if _settings is None:
        _settings = load_settings()
    return _settings


def reset_settings() -> None:
    """Reset the settings singleton (useful for testing)."""
    global _settings
    _settings = None


# ---------------------------------------------------------------------------
# Runtime analysis feature-flag toggles (persisted, no restart required)
# ---------------------------------------------------------------------------


def analysis_flags_path(storage_root: str) -> Path:
    """Path to the JSON file persisting the analysis feature-flag overrides."""
    return Path(storage_root) / ".analysis_flags.json"


def get_analysis_flags() -> dict[str, bool]:
    """Return the current analysis feature flags from the live settings."""
    s = get_settings()
    return {
        "enable_place": bool(s.enable_place),
        "enable_scene": bool(s.enable_scene),
        "enable_face": bool(s.enable_face),
    }


def set_analysis_flags(
    *,
    enable_place: bool,
    enable_scene: bool,
    enable_face: bool,
) -> dict[str, bool]:
    """Update the analysis feature flags at runtime and persist them.

    Mutates the live settings singleton (so the background worker, which holds a
    reference to it, sees the change immediately with no restart) and writes the
    values to ``{storage_root}/.analysis_flags.json`` so they survive restarts.
    """
    s = get_settings()
    object.__setattr__(s, "enable_place", bool(enable_place))
    object.__setattr__(s, "enable_scene", bool(enable_scene))
    object.__setattr__(s, "enable_face", bool(enable_face))

    flags = {
        "enable_place": bool(enable_place),
        "enable_scene": bool(enable_scene),
        "enable_face": bool(enable_face),
    }
    path = analysis_flags_path(s.storage_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(flags, indent=2), encoding="utf-8")
    return flags
