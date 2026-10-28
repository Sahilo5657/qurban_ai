"""Bluetooth status adapter for Windows.

Uses PowerShell to query Bluetooth radio status. Toggling Bluetooth
programmatically on Windows is limited without admin/UWP APIs.
"""

from __future__ import annotations

import logging
from typing import Any

from chinar.adapters.base import run_powershell
from chinar.schemas import ActionResult, ActionStatus

logger = logging.getLogger("chinar.adapters.bluetooth")


class BluetoothAdapter:
    """Bluetooth status on Windows."""

    async def execute(self, action: str, parameters: dict[str, Any]) -> ActionResult:
        if action == "get_bluetooth_status":
            return await self._get_status(parameters)
        return ActionResult(
            status=ActionStatus.UNSUPPORTED,
            message=f"Unknown bluetooth action: {action}",
            intent=action,
        )

    async def _get_status(self, params: dict[str, Any]) -> ActionResult:
        # Query Bluetooth radio via PowerShell
        cmd = (
            "Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue | "
            "Select-Object -First 1 Status, FriendlyName | Format-List"
        )
        rc, stdout, stderr = await run_powershell(cmd, timeout=10)

        if rc != 0 or not stdout.strip():
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not query Bluetooth status. Bluetooth may not be available.",
                intent="get_bluetooth_status",
            )

        # Parse output
        info: dict[str, str] = {}
        for line in stdout.splitlines():
            if ":" in line:
                key, _, value = line.partition(":")
                info[key.strip().lower()] = value.strip()

        status = info.get("status", "unknown")
        name = info.get("friendlyname", "")

        msg = f"Bluetooth: {status}."
        if name:
            msg += f" Device: {name}."

        return ActionResult(
            status=ActionStatus.SUCCESS,
            message=msg,
            data={"status": status, "device": name},
            intent="get_bluetooth_status",
        )
