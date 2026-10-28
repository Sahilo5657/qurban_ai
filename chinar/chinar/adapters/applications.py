"""Application launcher and manager for Windows.

Opens applications using os.startfile, Start-Process, or known paths.
Closes applications by process name via taskkill.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from chinar.adapters.base import run_subprocess, run_powershell
from chinar.schemas import ActionResult, ActionStatus

logger = logging.getLogger("chinar.adapters.applications")

# Map of common application aliases to executable names or commands
APP_ALIASES: dict[str, str] = {
    "notepad": "notepad.exe",
    "calculator": "calc.exe",
    "calc": "calc.exe",
    "paint": "mspaint.exe",
    "file explorer": "explorer.exe",
    "explorer": "explorer.exe",
    "task manager": "taskmgr.exe",
    "command prompt": "cmd.exe",
    "cmd": "cmd.exe",
    "powershell": "powershell.exe",
    "terminal": "wt.exe",
    "windows terminal": "wt.exe",
    "settings": "ms-settings:",
    "control panel": "control.exe",
    "snipping tool": "snippingtool.exe",
    "edge": "msedge.exe",
    "microsoft edge": "msedge.exe",
    "chrome": "chrome.exe",
    "google chrome": "chrome.exe",
    "firefox": "firefox.exe",
    "word": "winword.exe",
    "excel": "excel.exe",
    "powerpoint": "powerpnt.exe",
    "outlook": "outlook.exe",
    "spotify": "spotify.exe",
    "discord": "discord.exe",
    "vscode": "code.exe",
    "visual studio code": "code.exe",
    "vs code": "code.exe",
}


class ApplicationAdapter:
    """Manages launching and closing Windows applications."""

    async def execute(self, action: str, parameters: dict[str, Any]) -> ActionResult:
        if action == "open_application":
            return await self._open(parameters)
        elif action == "close_application":
            return await self._close(parameters)
        return ActionResult(
            status=ActionStatus.UNSUPPORTED,
            message=f"Unknown application action: {action}",
            intent=action,
        )

    async def _open(self, params: dict[str, Any]) -> ActionResult:
        app_name = params.get("app_name", "").strip().lower()
        if not app_name:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No application name provided.",
                intent="open_application",
            )

        # Resolve alias
        executable = APP_ALIASES.get(app_name, app_name)

        # Try os.startfile for URIs (like ms-settings:)
        if ":" in executable and not executable.endswith(".exe"):
            try:
                os.startfile(executable)
                logger.info("Opened URI: %s", executable)
                return ActionResult(
                    status=ActionStatus.SUCCESS,
                    message=f"Opened {app_name}.",
                    intent="open_application",
                )
            except OSError as exc:
                logger.warning("Failed to open URI %s: %s", executable, exc)

        # Try Start-Process
        rc, stdout, stderr = await run_powershell(
            f'Start-Process "{executable}" -ErrorAction Stop'
        )
        if rc == 0:
            logger.info("Launched: %s", executable)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Opened {app_name}.",
                intent="open_application",
            )

        # Final attempt: try with just the name
        rc2, _, stderr2 = await run_powershell(
            f'Start-Process "{app_name}" -ErrorAction Stop'
        )
        if rc2 == 0:
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Opened {app_name}.",
                intent="open_application",
            )

        return ActionResult(
            status=ActionStatus.FAILURE,
            message=f"Could not open '{app_name}'. It may not be installed.",
            intent="open_application",
        )

    async def _close(self, params: dict[str, Any]) -> ActionResult:
        app_name = params.get("app_name", "").strip().lower()
        if not app_name:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No application name provided.",
                intent="close_application",
            )

        # Resolve to process name
        executable = APP_ALIASES.get(app_name, app_name)
        # Ensure .exe extension for taskkill
        proc_name = executable if executable.endswith(".exe") else f"{executable}.exe"

        rc, stdout, stderr = await run_subprocess(
            ["taskkill", "/IM", proc_name, "/F"],
            timeout=10,
        )

        if rc == 0:
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Closed {app_name}.",
                intent="close_application",
            )

        # Process may not be running
        if "not found" in stderr.lower() or rc == 128:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"'{app_name}' is not running.",
                intent="close_application",
            )

        return ActionResult(
            status=ActionStatus.FAILURE,
            message=f"Could not close '{app_name}': {stderr}",
            intent="close_application",
        )
