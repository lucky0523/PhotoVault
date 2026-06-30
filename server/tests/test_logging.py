"""Tests for the logging configuration module."""

import logging
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

import pytest

from app.core.config import reset_settings
from app.core.logging import BACKUP_COUNT, LOG_FORMAT, MAX_BYTES, setup_logging


@pytest.fixture(autouse=True)
def _clean_logger():
    """Ensure the photovault logger is clean before and after each test."""
    logger = logging.getLogger("photovault")
    logger.handlers.clear()
    logger.setLevel(logging.WARNING)
    yield
    logger.handlers.clear()
    logger.setLevel(logging.WARNING)


@pytest.fixture(autouse=True)
def _reset_config():
    """Reset settings singleton between tests."""
    reset_settings()
    yield
    reset_settings()


class TestSetupLogging:
    """Test setup_logging configures the photovault logger correctly."""

    def test_creates_log_directory(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        assert log_dir.exists()
        assert log_dir.is_dir()

    def test_creates_nested_log_directory(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "deep" / "nested" / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        assert log_dir.exists()

    def test_adds_console_handler(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        stream_handlers = [
            h for h in logger.handlers if isinstance(h, logging.StreamHandler)
            and not isinstance(h, RotatingFileHandler)
        ]
        assert len(stream_handlers) == 1
        assert stream_handlers[0].stream is sys.stdout

    def test_adds_file_handler(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        file_handlers = [
            h for h in logger.handlers if isinstance(h, RotatingFileHandler)
        ]
        assert len(file_handlers) == 1
        assert file_handlers[0].baseFilename == str(log_dir / "photovault.log")

    def test_file_handler_rotation_settings(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        file_handlers = [
            h for h in logger.handlers if isinstance(h, RotatingFileHandler)
        ]
        handler = file_handlers[0]
        assert handler.maxBytes == MAX_BYTES
        assert handler.backupCount == BACKUP_COUNT

    def test_sets_log_level_info_by_default(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        assert logger.level == logging.INFO

    def test_sets_custom_log_level(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_level="DEBUG", log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        assert logger.level == logging.DEBUG

    def test_log_level_case_insensitive(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_level="warning", log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        assert logger.level == logging.WARNING

    def test_log_format_applied(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        for handler in logger.handlers:
            assert handler.formatter._fmt == LOG_FORMAT

    def test_no_duplicate_handlers_on_repeated_calls(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))
        setup_logging(log_dir=str(log_dir))
        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        # Should have exactly 2 handlers (1 console + 1 file)
        assert len(logger.handlers) == 2

    def test_propagate_disabled(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        assert logger.propagate is False

    def test_writes_to_log_file(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        logger = logging.getLogger("photovault")
        logger.info("test message for file output")

        # Flush handlers
        for handler in logger.handlers:
            handler.flush()

        log_file = log_dir / "photovault.log"
        assert log_file.exists()
        content = log_file.read_text()
        assert "test message for file output" in content

    def test_child_logger_inherits_config(self, tmp_path, monkeypatch):
        log_dir = tmp_path / "logs"
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging(log_dir=str(log_dir))

        child_logger = logging.getLogger("photovault.database")
        child_logger.info("child logger message")

        # Flush handlers
        parent = logging.getLogger("photovault")
        for handler in parent.handlers:
            handler.flush()

        log_file = log_dir / "photovault.log"
        content = log_file.read_text()
        assert "child logger message" in content

    def test_uses_settings_log_dir_when_not_provided(self, tmp_path, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging()

        expected_dir = tmp_path / "logs"
        assert expected_dir.exists()

        logger = logging.getLogger("photovault")
        file_handlers = [
            h for h in logger.handlers if isinstance(h, RotatingFileHandler)
        ]
        assert file_handlers[0].baseFilename == str(expected_dir / "photovault.log")

    def test_uses_settings_log_level_when_not_provided(self, tmp_path, monkeypatch):
        monkeypatch.setenv("PHOTOVAULT_STORAGE_ROOT", str(tmp_path))
        monkeypatch.setenv("PHOTOVAULT_LOG_LEVEL", "DEBUG")
        monkeypatch.setenv("PHOTOVAULT_CONFIG_PATH", "/nonexistent")

        setup_logging()

        logger = logging.getLogger("photovault")
        assert logger.level == logging.DEBUG
