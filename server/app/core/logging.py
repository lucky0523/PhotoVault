"""Logging configuration for PhotoVault.

Provides a setup_logging() function that configures the 'photovault' logger
with both console (stdout) and rotating file handlers.

Usage:
    from app.core.logging import setup_logging
    setup_logging()  # Uses settings from config
"""

from __future__ import annotations

import logging
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOG_FORMAT = "[%(asctime)s] %(levelname)s %(name)s: %(message)s"

# Max log file size: 10 MB
MAX_BYTES = 10 * 1024 * 1024

# Keep 5 backup log files
BACKUP_COUNT = 5


def setup_logging(log_level: str | None = None, log_dir: str | None = None) -> None:
    """Configure the photovault logger with console and file handlers.

    Args:
        log_level: Logging level string (e.g. "INFO", "DEBUG"). If None,
            reads from application settings.
        log_dir: Directory for log files. If None, reads from application
            settings (defaults to {storage_root}/logs).
    """
    from app.core.config import get_settings

    settings = get_settings()

    level_str = (log_level or settings.log_level).upper()
    level = getattr(logging, level_str, logging.INFO)

    directory = log_dir or settings.log_dir

    # Ensure log directory exists
    log_path = Path(directory)
    log_path.mkdir(parents=True, exist_ok=True)

    # Get the root photovault logger
    logger = logging.getLogger("photovault")
    logger.setLevel(level)

    # Remove existing handlers to avoid duplicates on repeated calls
    logger.handlers.clear()

    # Formatter
    formatter = logging.Formatter(LOG_FORMAT)

    # Console handler (stdout)
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(level)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)

    # File handler (rotating)
    log_file = log_path / "photovault.log"
    file_handler = RotatingFileHandler(
        filename=str(log_file),
        maxBytes=MAX_BYTES,
        backupCount=BACKUP_COUNT,
        encoding="utf-8",
    )
    file_handler.setLevel(level)
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)

    # Prevent log messages from propagating to the root logger
    logger.propagate = False
