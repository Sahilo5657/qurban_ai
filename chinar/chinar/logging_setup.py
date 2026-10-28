"""Logging setup with rotation for Chinar."""

from __future__ import annotations

import logging
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from chinar.config import ChinarConfig


def setup_logging(config: ChinarConfig) -> logging.Logger:
    """Configure the root logger with console and rotating file handlers.

    Args:
        config: The application configuration.

    Returns:
        The configured root logger.
    """
    log_path = config.log_path
    log_path.parent.mkdir(parents=True, exist_ok=True)

    level = getattr(logging, config.logging.level.upper(), logging.INFO)

    # Format: timestamp - level - module - message
    fmt = logging.Formatter(
        "%(asctime)s | %(levelname)-8s | %(name)-25s | %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    root_logger = logging.getLogger("chinar")
    root_logger.setLevel(level)

    # Avoid duplicate handlers on reload
    root_logger.handlers.clear()

    # Console handler (stderr)
    console = logging.StreamHandler(sys.stderr)
    console.setLevel(level)
    console.setFormatter(fmt)
    root_logger.addHandler(console)

    # Rotating file handler
    try:
        file_handler = RotatingFileHandler(
            str(log_path),
            maxBytes=config.logging.max_bytes,
            backupCount=config.logging.backup_count,
            encoding="utf-8",
        )
        file_handler.setLevel(level)
        file_handler.setFormatter(fmt)
        root_logger.addHandler(file_handler)
    except OSError as exc:
        root_logger.warning("Could not open log file %s: %s", log_path, exc)

    return root_logger
