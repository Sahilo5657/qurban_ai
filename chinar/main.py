"""Chinar — Main entry point.

Usage:
    python main.py                  # Interactive mode
    python main.py --check          # Run diagnostics
    python main.py --command "..."  # Execute a single command
    python main.py --help           # Show help
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path

# Ensure the project root is on sys.path
_project_root = Path(__file__).resolve().parent
if str(_project_root) not in sys.path:
    sys.path.insert(0, str(_project_root))

from chinar.config import load_config, ConfigError
from chinar.logging_setup import setup_logging
from chinar.application import ChinarApp


async def async_main(args: argparse.Namespace) -> int:
    """Async entry point."""
    # Load configuration
    try:
        config_path = Path(args.config) if args.config else None
        config, warnings = load_config(config_path)
    except ConfigError as exc:
        print(f"❌ Configuration error: {exc}", file=sys.stderr)
        return 1

    # Set up logging
    logger = setup_logging(config)
    for w in warnings:
        logger.warning(w)

    # Create application
    app = ChinarApp(config)

    try:
        # Initialize
        ok = await app.initialize()
        if not ok:
            logger.error("Initialization failed")
            return 1

        if args.check:
            # Diagnostic mode
            await app.run_diagnostic()
            return 0

        if args.command:
            # Single command mode
            await app.run_single_command(args.command)
            return 0

        # Interactive mode
        await app.run_interactive()
        return 0

    except KeyboardInterrupt:
        print("\nInterrupted.")
        return 130
    except Exception as exc:
        logger.error("Fatal error: %s", exc, exc_info=True)
        return 1
    finally:
        await app.shutdown()


def main_entry() -> None:
    """CLI entry point (called by pyproject.toml [project.scripts])."""
    parser = argparse.ArgumentParser(
        prog="chinar",
        description="Chinar — Local-first voice assistant for Windows",
    )
    parser.add_argument(
        "--config", "-c",
        type=str,
        default=None,
        help="Path to config.toml (default: config/config.toml)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Run diagnostic checks and exit",
    )
    parser.add_argument(
        "--command", "-x",
        type=str,
        default=None,
        help="Execute a single text command and exit",
    )
    parser.add_argument(
        "--version", "-v",
        action="version",
        version="Chinar 0.1.0",
    )

    args = parser.parse_args()

    try:
        exit_code = asyncio.run(async_main(args))
    except KeyboardInterrupt:
        exit_code = 130

    sys.exit(exit_code)


if __name__ == "__main__":
    main_entry()
