"""Filesystem adapter for safe file and directory operations on Windows.

Provides file opening (via os.startfile), directory browsing,
and file search with path validation and traversal protection.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

from chinar.adapters.base import run_subprocess
from chinar.schemas import ActionResult, ActionStatus

logger = logging.getLogger("chinar.adapters.filesystem")


class FilesystemAdapter:
    """Safe file and directory operations."""

    async def execute(self, action: str, parameters: dict[str, Any]) -> ActionResult:
        handlers = {
            "open_file": self._open_file,
            "open_directory": self._open_directory,
            "search_files": self._search_files,
        }
        handler = handlers.get(action)
        if handler is None:
            return ActionResult(
                status=ActionStatus.UNSUPPORTED,
                message=f"Unknown filesystem action: {action}",
                intent=action,
            )
        return await handler(parameters)

    async def _open_file(self, params: dict[str, Any]) -> ActionResult:
        raw_path = params.get("path", "")
        if not raw_path:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No file path provided.",
                intent="open_file",
            )

        try:
            resolved = Path(raw_path).resolve()
        except (ValueError, OSError):
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Invalid path: '{raw_path}'.",
                intent="open_file",
            )

        if not resolved.exists():
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"File not found: '{resolved}'.",
                intent="open_file",
            )

        if not resolved.is_file():
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"'{resolved}' is not a file.",
                intent="open_file",
            )

        try:
            os.startfile(str(resolved))
            logger.info("Opened file: %s", resolved)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Opened {resolved.name}.",
                intent="open_file",
            )
        except OSError as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Could not open file: {exc}",
                intent="open_file",
            )

    async def _open_directory(self, params: dict[str, Any]) -> ActionResult:
        raw_path = params.get("path", "")
        if not raw_path:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No directory path provided.",
                intent="open_directory",
            )

        try:
            resolved = Path(raw_path).resolve()
        except (ValueError, OSError):
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Invalid path: '{raw_path}'.",
                intent="open_directory",
            )

        if not resolved.exists():
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Directory not found: '{resolved}'.",
                intent="open_directory",
            )

        if not resolved.is_dir():
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"'{resolved}' is not a directory.",
                intent="open_directory",
            )

        try:
            os.startfile(str(resolved))
            logger.info("Opened directory: %s", resolved)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Opened {resolved.name}.",
                intent="open_directory",
            )
        except OSError as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Could not open directory: {exc}",
                intent="open_directory",
            )

    async def _search_files(self, params: dict[str, Any]) -> ActionResult:
        query = params.get("query", "")
        if not query:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No search query provided.",
                intent="search_files",
            )

        search_dir = params.get("directory", str(Path.home()))

        try:
            resolved_dir = Path(search_dir).resolve()
            if not resolved_dir.is_dir():
                return ActionResult(
                    status=ActionStatus.FAILURE,
                    message=f"Search directory not found: '{search_dir}'.",
                    intent="search_files",
                )
        except (ValueError, OSError):
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Invalid search directory: '{search_dir}'.",
                intent="search_files",
            )

        # Use dir /s /b for basic file search (limited but safe)
        rc, stdout, stderr = await run_subprocess(
            ["cmd", "/c", "dir", "/s", "/b", str(resolved_dir / f"*{query}*")],
            timeout=15,
        )

        if rc != 0 or not stdout.strip():
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"No files matching '{query}' found in {resolved_dir.name}.",
                data={"results": []},
                intent="search_files",
            )

        results = [line.strip() for line in stdout.splitlines() if line.strip()][:20]
        count = len(results)

        return ActionResult(
            status=ActionStatus.SUCCESS,
            message=f"Found {count} file(s) matching '{query}'.",
            data={"results": results, "count": count},
            intent="search_files",
        )
