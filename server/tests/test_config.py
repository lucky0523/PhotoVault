"""Tests for the configuration management module."""

import os
import tempfile
from pathlib import Path

import pytest
import yaml

from app.core.config import (
    Settings,
    _flatten_yaml,
    _load_yaml_config,
    get_settings,
    load_settings,
    reset_settings,
)


# ---------------------------------------------------------------------------
# Settings defaults
# ---------------------------------------------------------------------------


class TestSettingsDefaults:
    """Test that Settings has correct default values."""

    def test_default_server_host(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.server_host == "127.0.0.1"

    def test_default_server_port(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.server_port == 8000

    def test_default_storage_root(self):
        settings = Settings()
        assert settings.storage_root == "/data/photovault"

    def test_default_access_token_expire_hours(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.access_token_expire_hours == 24

    def test_default_refresh_token_expire_days(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.refresh_token_expire_days == 7

    def test_default_max_users(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.max_users == 20

    def test_default_jwt_secret_key(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.jwt_secret_key == "change-me-in-production"

    def test_default_chunk_size_mb(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.chunk_size_mb == 2

    def test_default_session_expire_days(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.session_expire_days == 7

    def test_default_database_url_derived_from_storage_root(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.database_url == "/data/photovault/photovault.db"

    def test_database_url_follows_custom_storage_root(self):
        settings = Settings(storage_root="/mnt/nas/photos")
        assert settings.database_url == "/mnt/nas/photos/photovault.db"

    def test_explicit_database_url_overrides_default(self):
        settings = Settings(
            storage_root="/data/photovault",
            database_url="/custom/path/my.db",
        )
        assert settings.database_url == "/custom/path/my.db"


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


class TestSettingsValidation:
    """Test field validation rules."""

    def test_storage_root_must_be_absolute(self):
        with pytest.raises(ValueError, match="absolute path"):
            Settings(storage_root="relative/path")

    def test_storage_root_rejects_relative_dot_path(self):
        with pytest.raises(ValueError, match="absolute path"):
            Settings(storage_root="./data")

    def test_storage_root_accepts_absolute_path(self):
        settings = Settings(storage_root="/absolute/path")
        assert settings.storage_root == "/absolute/path"

    def test_chunk_size_must_be_positive(self):
        with pytest.raises(ValueError, match="chunk_size_mb must be > 0"):
            Settings(storage_root="/data", chunk_size_mb=0)

    def test_chunk_size_rejects_negative(self):
        with pytest.raises(ValueError, match="chunk_size_mb must be > 0"):
            Settings(storage_root="/data", chunk_size_mb=-1)

    def test_access_token_expire_must_be_positive(self):
        with pytest.raises(ValueError, match="access_token_expire_hours must be > 0"):
            Settings(storage_root="/data", access_token_expire_hours=0)

    def test_refresh_token_expire_must_be_positive(self):
        with pytest.raises(ValueError, match="refresh_token_expire_days must be > 0"):
            Settings(storage_root="/data", refresh_token_expire_days=0)

    def test_max_users_must_be_positive(self):
        with pytest.raises(ValueError, match="max_users must be > 0"):
            Settings(storage_root="/data", max_users=0)

    def test_server_port_must_be_in_range(self):
        with pytest.raises(ValueError, match="server_port must be between"):
            Settings(storage_root="/data", server_port=0)

    def test_server_port_rejects_too_high(self):
        with pytest.raises(ValueError, match="server_port must be between"):
            Settings(storage_root="/data", server_port=70000)

    def test_session_expire_must_be_positive(self):
        with pytest.raises(ValueError, match="session_expire_days must be > 0"):
            Settings(storage_root="/data", session_expire_days=0)


# ---------------------------------------------------------------------------
# Computed properties
# ---------------------------------------------------------------------------


class TestComputedProperties:
    """Test computed/derived properties."""

    def test_chunk_size_bytes_default(self):
        settings = Settings(storage_root="/data/photovault")
        assert settings.chunk_size_bytes == 2 * 1024 * 1024

    def test_chunk_size_bytes_custom(self):
        settings = Settings(storage_root="/data/photovault", chunk_size_mb=5)
        assert settings.chunk_size_bytes == 5 * 1024 * 1024


# ---------------------------------------------------------------------------
# YAML loading
# ---------------------------------------------------------------------------


class TestYamlLoading:
    """Test loading configuration from YAML files."""

    def test_load_from_yaml_file(self, tmp_path, monkeypatch):
        config_data = {
            "server": {"host": "0.0.0.0", "port": 9000},
            "storage": {"root": "/mnt/backup"},
            "auth": {
                "access_token_expire_hours": 48,
                "refresh_token_expire_days": 14,
                "max_users": 10,
                "jwt_secret_key": "my-secret",
            },
            "backup": {"chunk_size_mb": 4, "session_expire_days": 3},
        }
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump(config_data))

        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", str(config_file))

        settings = load_settings()
        assert settings.server_host == "0.0.0.0"
        assert settings.server_port == 9000
        assert settings.storage_root == "/mnt/backup"
        assert settings.access_token_expire_hours == 48
        assert settings.refresh_token_expire_days == 14
        assert settings.max_users == 10
        assert settings.jwt_secret_key == "my-secret"
        assert settings.chunk_size_mb == 4
        assert settings.session_expire_days == 3

    def test_yaml_partial_config(self, tmp_path, monkeypatch):
        """YAML with only some fields uses defaults for the rest."""
        config_data = {
            "storage": {"root": "/custom/storage"},
        }
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump(config_data))

        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", str(config_file))

        settings = load_settings()
        assert settings.storage_root == "/custom/storage"
        # Defaults preserved
        assert settings.server_host == "127.0.0.1"
        assert settings.chunk_size_mb == 2

    def test_missing_yaml_uses_defaults(self, monkeypatch):
        """When no YAML file exists, defaults are used."""
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/config.yaml")
        settings = load_settings()
        assert settings.storage_root == "/data/photovault"
        assert settings.server_port == 8000

    def test_empty_yaml_uses_defaults(self, tmp_path, monkeypatch):
        """An empty YAML file uses defaults."""
        config_file = tmp_path / "config.yaml"
        config_file.write_text("")

        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", str(config_file))

        settings = load_settings()
        assert settings.storage_root == "/data/photovault"


# ---------------------------------------------------------------------------
# Environment variable override
# ---------------------------------------------------------------------------


class TestEnvVarOverride:
    """Test that environment variables override YAML and defaults."""

    def test_env_var_overrides_default(self, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", "/env/storage")
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")

        settings = load_settings()
        assert settings.storage_root == "/env/storage"

    def test_env_var_overrides_yaml(self, tmp_path, monkeypatch):
        config_data = {"storage": {"root": "/yaml/storage"}}
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump(config_data))

        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", str(config_file))
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", "/env/override")

        settings = load_settings()
        assert settings.storage_root == "/env/override"

    def test_env_var_for_port(self, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_SERVER_PORT", "3000")
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")

        settings = load_settings()
        assert settings.server_port == 3000


# ---------------------------------------------------------------------------
# Singleton behavior
# ---------------------------------------------------------------------------


class TestSingleton:
    """Test the get_settings singleton pattern."""

    def test_get_settings_returns_same_instance(self, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")
        reset_settings()

        s1 = get_settings()
        s2 = get_settings()
        assert s1 is s2

    def test_reset_settings_clears_singleton(self, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")
        reset_settings()

        s1 = get_settings()
        reset_settings()
        s2 = get_settings()
        # New instance after reset
        assert s1 is not s2

    def test_get_settings_after_reset_picks_up_new_env(self, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", "/first")
        reset_settings()

        s1 = get_settings()
        assert s1.storage_root == "/first"

        reset_settings()
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", "/second")

        s2 = get_settings()
        assert s2.storage_root == "/second"


# ---------------------------------------------------------------------------
# Flatten YAML helper
# ---------------------------------------------------------------------------


class TestFlattenYaml:
    """Test the _flatten_yaml helper function."""

    def test_empty_dict(self):
        assert _flatten_yaml({}) == {}

    def test_full_config(self):
        data = {
            "server": {"host": "0.0.0.0", "port": 9000},
            "storage": {"root": "/mnt/data"},
            "auth": {
                "access_token_expire_hours": 12,
                "refresh_token_expire_days": 30,
                "max_users": 5,
                "jwt_secret_key": "secret",
            },
            "backup": {"chunk_size_mb": 8, "session_expire_days": 14},
            "database_url": "/custom/db.sqlite",
        }
        flat = _flatten_yaml(data)
        assert flat["server_host"] == "0.0.0.0"
        assert flat["server_port"] == 9000
        assert flat["storage_root"] == "/mnt/data"
        assert flat["access_token_expire_hours"] == 12
        assert flat["refresh_token_expire_days"] == 30
        assert flat["max_users"] == 5
        assert flat["jwt_secret_key"] == "secret"
        assert flat["chunk_size_mb"] == 8
        assert flat["session_expire_days"] == 14
        assert flat["database_url"] == "/custom/db.sqlite"

    def test_partial_config(self):
        data = {"storage": {"root": "/data"}}
        flat = _flatten_yaml(data)
        assert flat == {"storage_root": "/data"}


# ---------------------------------------------------------------------------
# load_settings with overrides
# ---------------------------------------------------------------------------


class TestLoadSettingsOverrides:
    """Test load_settings with explicit overrides."""

    def test_overrides_take_precedence_over_yaml(self, tmp_path, monkeypatch):
        config_data = {"storage": {"root": "/yaml/path"}}
        config_file = tmp_path / "config.yaml"
        config_file.write_text(yaml.dump(config_data))

        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", str(config_file))

        settings = load_settings(storage_root="/override/path")
        assert settings.storage_root == "/override/path"

    def test_overrides_with_no_yaml(self, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent/path")

        settings = load_settings(chunk_size_mb=10, max_users=50)
        assert settings.chunk_size_mb == 10
        assert settings.max_users == 50
