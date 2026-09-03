"""Application configuration.

Manages server settings loaded from config.yaml or environment variables:
- Server host/port
- Storage paths
- Auth token expiry
- Chunk size
- Session expiry

Configuration priority (highest to lowest):
1. Environment variables (prefixed with PHOTOVAULT_)
2. config.yaml file (if present)
3. Default values

Storage layout
--------------
Four locations are configured independently, each with its own setting and
environment variable:

===================  ==========================  ==============================
Setting              Environment variable        Default when unset
===================  ==========================  ==============================
``media_root``       ``PHOTOVAULT_MEDIA_ROOT``   ``{storage_root}``
``database_url``     ``PHOTOVAULT_DATABASE_URL`` ``{storage_root}/photovault.db``
``log_dir``          ``PHOTOVAULT_LOG_DIR``      ``{storage_root}/logs``
``models_root``      ``PHOTOVAULT_MODELS_ROOT``  ``{storage_root}/.models``
===================  ==========================  ==============================

On the fnOS package a further step sits in front of this table: ``photos`` and the
database live in a *working directory* the administrator picks during the first-run
wizard, because the platform only lets the app write directories it has been
granted an ACL for. That choice is recorded in ``{storage_root}/.workdir`` and
overrides the two derived defaults. See ``needs_provisioning`` / ``set_workdir``.

``storage_root`` is *only* the fallback base used to derive the ones that were
left unset — nothing reads it to locate photos, the database, logs or models.
That means any single location can be moved to a different disk (a small SSD for
the database, the system partition for logs, a large array for photos) without
disturbing the others, and a deployment that sets all four never depends on
``storage_root`` at all.
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

# Accepted SQLite URL prefixes for ``database_url``. The setting doubles as a
# plain filesystem path, so these are stripped before the value is handed to
# aiosqlite (which does not understand SQLAlchemy-style URLs and would happily
# create a file literally named "sqlite+aiosqlite:" instead).
_SQLITE_URL_PREFIXES = ("sqlite+aiosqlite://", "sqlite://")


def sqlite_path_from_url(database_url: str) -> str:
    """Return ``database_url`` as a plain filesystem path.

    Accepts both a bare path and a SQLAlchemy-style SQLite URL, so callers can
    hand the configured value straight to ``aiosqlite.connect``.

    Args:
        database_url: A filesystem path or a ``sqlite://`` / ``sqlite+aiosqlite://`` URL.

    Returns:
        The filesystem path with any SQLite scheme prefix removed.
    """
    for prefix in _SQLITE_URL_PREFIXES:
        if database_url.startswith(prefix):
            return database_url[len(prefix):]
    return database_url


# ---------------------------------------------------------------------------
# Working-directory pointer
# ---------------------------------------------------------------------------
# The working directory holds the photos *and* the database, so its location
# cannot be recorded inside itself. It goes in a one-line file under
# ``storage_root`` instead, which is the one place guaranteed to exist and be
# writable before provisioning (on fnOS that is the declared data-share, the only
# directory the platform auto-grants the app user an ACL for).
#
# This file is also the "provisioning finished" marker: it is written last, after
# the database and the admin account exist, so an interrupted setup leaves no
# pointer and the next start simply runs the wizard again.
#
# packaging/fnos/cmd/uninstall_callback reads it too, to tell the user where the
# photos were left. Keep the format a single bare path on one line.
WORKDIR_POINTER_NAME = ".workdir"

#: Where a no-longer-valid pointer is kept.
#:
#: A pointer is retired rather than deleted in two situations: the package was
#: uninstalled (see cmd/uninstall_callback), or the recorded directory turned out to
#: be unwritable at startup. Both mean the wizard has to run again — on fnOS an
#: uninstall revokes the ``trim.file.sharedAccess`` grant, and the wizard is the only
#: place that can obtain a new one, so continuing to treat the directory as valid
#: would strand the app with no way to recover.
#:
#: The path is preserved because the wizard offers it as "the directory you used
#: last time": its picker only lists *authorized* directories, so after a revoked
#: grant the old library would otherwise be unfindable, and there is no free-text
#: field to type it into.
WORKDIR_PREVIOUS_NAME = ".workdir.previous"


def workdir_pointer_path(storage_root: str) -> Path:
    """Path to the file recording the administrator-chosen working directory."""
    return Path(storage_root) / WORKDIR_POINTER_NAME


def previous_workdir_pointer_path(storage_root: str) -> Path:
    """Path to the retired pointer kept as a recovery hint for the wizard."""
    return Path(storage_root) / WORKDIR_PREVIOUS_NAME


def read_previous_workdir_pointer(storage_root: str) -> str:
    """Return the retired working directory, or ``""``. Never raises."""
    return _read_pointer_file(previous_workdir_pointer_path(storage_root))


def _read_pointer_file(path: Path) -> str:
    """Read a one-line absolute path from ``path``, or return ``""``."""
    try:
        if not path.is_file():
            return ""
        lines = path.read_text(encoding="utf-8").strip().splitlines()
        candidate = lines[0].strip() if lines else ""
    except OSError:
        logging.getLogger("photovault.config").warning(
            "Could not read the working-directory pointer at %s", path, exc_info=True
        )
        return ""

    # Only absolute paths are meaningful; a relative one would resolve against
    # whatever working directory the service happened to start in.
    if not candidate or not os.path.isabs(candidate):
        if candidate:
            logging.getLogger("photovault.config").warning(
                "Ignoring non-absolute working directory %r in %s", candidate, path
            )
        return ""

    return candidate.rstrip("/") or "/"


def read_workdir_pointer(storage_root: str) -> str:
    """Return the usable working directory, or ``""`` when unset or unusable.

    Never raises: this runs during settings construction, and a corrupt pointer
    must degrade to "not provisioned yet" rather than prevent the server from
    starting (which would also make the setup wizard unreachable).

    The recorded directory must still exist to count. A pointer at a missing
    directory — a deleted library, or a volume that is not mounted — reports "not
    provisioned", which is the safe answer: in that state the server creates no
    database, so nothing is written to the wrong place and the untouched library on
    the absent volume can still be re-adopted through the wizard later.

    Only a cheap ``isdir`` check happens here because this is called per request via
    ``needs_provisioning``. Whether the directory is actually *writable* is a real
    write probe, done once at startup by ``verify_workdir_writable``.
    """
    candidate = _read_pointer_file(workdir_pointer_path(storage_root))
    if not candidate:
        return ""

    if not os.path.isdir(candidate):
        logging.getLogger("photovault.config").warning(
            "Recorded working directory %s does not exist; treating the server as "
            "not provisioned so setup can run again",
            candidate,
        )
        return ""

    return candidate


def _load_yaml_config() -> dict[str, Any]:
    """Load configuration from config.yaml if it exists.

    Searches these locations, first found wins (no merging):

    1. ``PHOTOVAULT_CONFIG_PATH`` environment variable. When set, only that path
       is consulted — a missing file does not fall through to the rest.
    2. ``config.yaml`` in the current working directory
    3. ``config/config.yaml`` in the current working directory
    4. ``config.yaml`` in the server root (parent of ``app/``)
    5. ``config/config.yaml`` in the server root

    The ``config/`` variants exist because the Docker image mounts a config
    volume at ``/app/config`` (see docker-compose.yml), which is the directory
    users naturally drop the file into.
    """
    # Check explicit path from env var
    config_path_env = os.environ.get("PHOTOVAULT_CONFIG_PATH")
    if config_path_env:
        path = Path(config_path_env)
        if path.is_file():
            with open(path) as f:
                return yaml.safe_load(f) or {}
        return {}

    # Check common locations. The two bases resolve to the same directory in
    # every shipped deployment (the process is always started from the server
    # root), but they are kept distinct so a server launched from somewhere else
    # still finds a config sitting next to the code.
    server_root = Path(__file__).resolve().parent.parent.parent
    candidates = [
        base / name
        for base in (Path.cwd(), server_root)
        for name in ("config.yaml", "config/config.yaml")
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
        if "media_root" in storage:
            flat["media_root"] = storage["media_root"]

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
    #
    # storage_root is only the fallback base for the four locations below; it is
    # never used directly to place photos, the database, logs or models. Leave
    # one of those unset to have it derived from storage_root, or point it
    # somewhere else to move just that location. See the module docstring.
    storage_root: str = "/data/photovault"
    media_root: str = ""  # default: storage_root

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

    # First-run provisioning
    #
    # When true the server refuses to create its database until an administrator
    # has picked a working directory through the setup wizard. Only the fnOS
    # package turns this on (see packaging/fnos/app/lib/env.sh): there, photos may
    # only live in a directory the platform has granted the app user an ACL for,
    # and that grant can only be obtained from inside the running app. Docker and
    # local development leave it false and keep the original one-step setup.
    require_workdir_setup: bool = False

    # Logging
    log_level: str = "INFO"
    log_dir: str = ""  # default: f"{storage_root}/logs"

    # Database
    database_url: str = ""  # default: f"{storage_root}/photovault.db"

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

    # NOTE: for a given field, pydantic runs "after" validators in the order
    # they are declared, so normalisation has to come before the absolute-path
    # checks below.
    @field_validator("storage_root", "media_root", "log_dir", "models_root")
    @classmethod
    def normalize_directory(cls, v: str) -> str:
        """Strip surrounding whitespace and trailing slashes from a directory.

        These values are joined into paths with f-strings throughout the code
        base, and ``FileBrowseService`` compares them as string prefixes against
        the absolute paths recorded in ``file_records.file_path``. A stray
        trailing slash on an override (``/mnt/photos/``) would produce doubled
        separators and make those prefix comparisons miss, so it is removed once
        here rather than defended against at every use site.
        """
        v = v.strip()
        if not v:
            return v
        # Guard the filesystem root: "/".rstrip("/") is the empty string.
        return v.rstrip("/") or "/"

    @field_validator("storage_root")
    @classmethod
    def storage_root_must_be_absolute(cls, v: str) -> str:
        """Validate that storage_root is an absolute path."""
        if not os.path.isabs(v):
            raise ValueError(f"storage_root must be an absolute path, got: {v!r}")
        return v

    @field_validator("media_root", "log_dir", "models_root")
    @classmethod
    def optional_dir_must_be_absolute(cls, v: str) -> str:
        """Validate an optional directory override.

        Empty means "derive it from ``storage_root``". A non-empty value must be
        absolute: a relative override would resolve against the server's working
        directory, which is different under systemd, Docker and ``./run.sh``, so
        it is rejected at startup instead of silently scattering data.
        """
        if v and not os.path.isabs(v):
            raise ValueError(f"must be an absolute path when set, got: {v!r}")
        return v

    @field_validator("database_url")
    @classmethod
    def database_url_must_be_absolute(cls, v: str) -> str:
        """Validate the database location.

        Empty means "derive it from ``storage_root``". Otherwise the value may be
        a bare path or a SQLite URL, but the underlying file path has to be
        absolute for the same reason as the directories above.
        """
        v = v.strip()
        if not v:
            return v
        path = sqlite_path_from_url(v)
        if path != ":memory:" and not os.path.isabs(path):
            raise ValueError(
                f"database_url must resolve to an absolute SQLite path when set, got: {v!r}"
            )
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
    def apply_path_defaults(self) -> "Settings":
        """Fill in the storage locations that were left unset.

        Each location is an independent setting. ``storage_root`` is consulted
        only for the ones the user did not configure, so setting a single
        environment variable relocates exactly one directory and leaves the rest
        where they were.

        A working directory chosen through the setup wizard takes priority over
        ``storage_root`` for both photos and the database, but still yields to an
        explicit environment variable or yaml value — a deployment that pins those
        deliberately should never be overridden by persisted UI state.
        """
        workdir = read_workdir_pointer(self.storage_root)
        if workdir:
            if not self.media_root:
                self.media_root = workdir
            if not self.database_url:
                self.database_url = f"{workdir}/photovault.db"

        if not self.media_root:
            self.media_root = self.storage_root
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

    @property
    def database_path(self) -> str:
        """Return ``database_url`` as a plain filesystem path.

        ``database_url`` may carry a ``sqlite+aiosqlite://`` prefix (that is how
        docker-compose sets it), while aiosqlite needs a bare path.
        """
        return sqlite_path_from_url(self.database_url)


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
# Directory provisioning
# ---------------------------------------------------------------------------


def ensure_runtime_directories(settings: Settings | None = None) -> None:
    """Create every configured storage location.

    Now that the four locations can each sit on a different volume, a typo or an
    unmounted share should surface at startup naming the setting at fault,
    instead of much later as a failed upload or a confusing "no such file".

    ``models_root`` is treated as best-effort: it is only needed when an analysis
    dimension is enabled, so an unavailable models volume is logged rather than
    allowed to block a server that may not use it.

    Args:
        settings: Settings to provision for. Defaults to the singleton.

    Raises:
        RuntimeError: If a required directory cannot be created.
    """
    s = settings or get_settings()

    required = {
        "storage_root": Path(s.storage_root),
        "media_root": Path(s.media_root),
        "log_dir": Path(s.log_dir),
        "database_url": Path(s.database_path).parent,
    }
    for name, path in required.items():
        try:
            path.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise RuntimeError(
                f"Could not create the directory configured for {name!r}: {path} ({exc})"
            ) from exc

    models_root = Path(s.models_root)
    try:
        models_root.mkdir(parents=True, exist_ok=True)
    except OSError:
        logging.getLogger("photovault.config").warning(
            "Could not create the models directory %s; analysis features will be "
            "unavailable until it becomes writable",
            models_root,
            exc_info=True,
        )


# ---------------------------------------------------------------------------
# First-run provisioning state
# ---------------------------------------------------------------------------


def is_provisioned(settings: Settings | None = None) -> bool:
    """Whether an administrator has already chosen a working directory."""
    s = settings or get_settings()
    return bool(read_workdir_pointer(s.storage_root))


def needs_provisioning(settings: Settings | None = None) -> bool:
    """Whether the server must run the working-directory wizard before doing anything.

    True only for deployments that opted into the flow (``require_workdir_setup``)
    and have not completed it. While true the server deliberately has **no
    database**: creating one under ``storage_root`` would put it in the wrong place
    and later require a migration, which is exactly what this flow avoids.
    """
    s = settings or get_settings()
    return bool(s.require_workdir_setup) and not is_provisioned(s)


def demote_workdir_pointer(settings: Settings | None = None, *, reason: str = "") -> str:
    """Retire the current pointer and send the live settings back to ``storage_root``.

    Used when the recorded directory exists but cannot be written to. The pointer is
    moved to ``.workdir.previous`` rather than deleted so the wizard can offer the
    path back as a recovery hint, and so the cheap ``is_provisioned`` check agrees
    with this decision on every later request without redoing the write probe.

    Returns:
        The retired path, or ``""`` if there was nothing to retire.
    """
    s = settings or get_settings()
    logger = logging.getLogger("photovault.config")

    pointer = workdir_pointer_path(s.storage_root)
    retired = _read_pointer_file(pointer)
    if not retired:
        return ""

    try:
        pointer.replace(previous_workdir_pointer_path(s.storage_root))
    except OSError:
        logger.warning("Could not retire the pointer at %s", pointer, exc_info=True)
        return ""

    # Only reset the values this pointer was responsible for. A deployment that pins
    # them through the environment never had the pointer applied in the first place.
    if s.media_root == retired:
        object.__setattr__(s, "media_root", s.storage_root)
    if s.database_url == f"{retired}/photovault.db":
        object.__setattr__(s, "database_url", f"{s.storage_root}/photovault.db")

    logger.warning(
        "Retired working directory %s%s; setup will run again",
        retired,
        f" ({reason})" if reason else "",
    )
    return retired


def verify_workdir_writable(settings: Settings | None = None) -> bool:
    """Confirm at startup that the recorded working directory can be written to.

    This is the check that catches an uninstall/reinstall cycle. The pointer lives in
    ``storage_root``, which an uninstall deliberately leaves alone (it is the user's
    share), while the fnOS ``trim.file.sharedAccess`` grant on the working directory
    is revoked with the app. The directory therefore still *exists* and the pointer
    still looks valid, but every write fails — and since the wizard is the only place
    that can request a fresh grant, skipping it would leave no way out.

    A real write probe rather than ``os.access``: the shared folders use Windows ACLs,
    under which the POSIX permission bits give false negatives.

    Returns:
        True when provisioning is intact (or there is nothing to check). False when
        the pointer was retired, meaning the caller should expect the setup flow.
    """
    s = settings or get_settings()

    workdir = read_workdir_pointer(s.storage_root)
    if not workdir:
        return True

    probe = Path(workdir) / f".pv_startup_probe.{os.getpid()}"
    try:
        probe.touch()
    except OSError as exc:
        demote_workdir_pointer(s, reason=f"not writable: {exc.strerror or exc}")
        return False
    finally:
        try:
            probe.unlink()
        except OSError:
            pass

    return True


def set_workdir(path: str, settings: Settings | None = None) -> str:
    """Record the chosen working directory and point the live settings at it.

    Writing the pointer is the commit step of provisioning, so callers must
    already have created the database and the administrator account inside
    ``path``. See ``app.api.setup``.

    The live settings singleton is mutated rather than reloaded because every
    consumer reads ``settings.media_root`` / ``settings.database_url`` per request
    (and the background worker holds a reference to this same object), so the new
    location takes effect immediately with no restart. This mirrors how
    ``set_analysis_flags`` applies runtime changes.

    Args:
        path: Absolute path to the working directory.
        settings: Settings to update. Defaults to the singleton.

    Returns:
        The normalised path that was recorded.

    Raises:
        ValueError: If ``path`` is not absolute.
        OSError: If the pointer file cannot be written.
    """
    if not os.path.isabs(path):
        raise ValueError(f"working directory must be an absolute path, got: {path!r}")

    normalised = path.rstrip("/") or "/"
    s = settings or get_settings()

    pointer = workdir_pointer_path(s.storage_root)
    pointer.parent.mkdir(parents=True, exist_ok=True)
    # Write-then-rename so an interrupted write cannot leave a half-written pointer
    # that would send the server at a truncated path.
    tmp = pointer.with_name(f"{pointer.name}.tmp")
    tmp.write_text(f"{normalised}\n", encoding="utf-8")
    tmp.replace(pointer)

    # The recovery hint has served its purpose now that a directory is chosen again.
    try:
        previous_workdir_pointer_path(s.storage_root).unlink(missing_ok=True)
    except OSError:
        pass

    object.__setattr__(s, "media_root", normalised)
    object.__setattr__(s, "database_url", f"{normalised}/photovault.db")

    logging.getLogger("photovault.config").info(
        "Working directory set to %s (database: %s)", normalised, s.database_url
    )
    return normalised


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
