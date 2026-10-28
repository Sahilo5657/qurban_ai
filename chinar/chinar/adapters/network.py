"""Network adapter for Wi-Fi status and control on Windows.

Uses netsh commands via subprocess. Network toggling may require
elevated privileges depending on Windows configuration.
"""

from __future__ import annotations

import logging
from typing import Any

from chinar.adapters.base import run_subprocess, run_powershell
from chinar.schemas import ActionResult, ActionStatus

logger = logging.getLogger("chinar.adapters.network")


class NetworkAdapter:
    """Wi-Fi status and control on Windows."""

    async def execute(self, action: str, parameters: dict[str, Any]) -> ActionResult:
        handlers = {
            "get_wifi_status": self._get_status,
            "enable_wifi": self._enable,
            "disable_wifi": self._disable,
        }
        handler = handlers.get(action)
        if handler is None:
            return ActionResult(
                status=ActionStatus.UNSUPPORTED,
                message=f"Unknown network action: {action}",
                intent=action,
            )
        return await handler(parameters)

    async def _get_status(self, params: dict[str, Any]) -> ActionResult:
        rc, stdout, stderr = await run_subprocess(
            ["netsh", "wlan", "show", "interfaces"],
            timeout=10,
        )
        if rc != 0:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not query Wi-Fi status. Wireless may not be available.",
                intent="get_wifi_status",
            )

        # Parse key fields
        info: dict[str, str] = {}
        for line in stdout.splitlines():
            if ":" in line:
                key, _, value = line.partition(":")
                info[key.strip().lower()] = value.strip()

        state = info.get("state", "unknown")
        ssid = info.get("ssid", "")
        signal = info.get("signal", "")

        msg = f"Wi-Fi is {state}."
        if ssid:
            msg += f" Connected to: {ssid}."
        if signal:
            msg += f" Signal: {signal}."

        return ActionResult(
            status=ActionStatus.SUCCESS,
            message=msg,
            data={"state": state, "ssid": ssid, "signal": signal},
            intent="get_wifi_status",
        )

    async def _enable(self, params: dict[str, Any]) -> ActionResult:
        # Find wireless interface name first
        iface = await self._get_interface_name()
        if not iface:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not find wireless interface.",
                intent="enable_wifi",
            )

        rc, stdout, stderr = await run_subprocess(
            ["netsh", "interface", "set", "interface", iface, "enabled"],
            timeout=15,
        )
        if rc == 0:
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Wi-Fi enabled.",
                intent="enable_wifi",
            )
        return ActionResult(
            status=ActionStatus.FAILURE,
            message=f"Could not enable Wi-Fi: {stderr}. May need administrator privileges.",
            intent="enable_wifi",
        )

    async def _disable(self, params: dict[str, Any]) -> ActionResult:
        iface = await self._get_interface_name()
        if not iface:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not find wireless interface.",
                intent="disable_wifi",
            )

        rc, stdout, stderr = await run_subprocess(
            ["netsh", "interface", "set", "interface", iface, "disabled"],
            timeout=15,
        )
        if rc == 0:
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Wi-Fi disabled.",
                intent="disable_wifi",
            )
        return ActionResult(
            status=ActionStatus.FAILURE,
            message=f"Could not disable Wi-Fi: {stderr}. May need administrator privileges.",
            intent="disable_wifi",
        )

    async def _get_interface_name(self) -> str:
        """Find the name of the wireless network interface."""
        rc, stdout, stderr = await run_subprocess(
            ["netsh", "wlan", "show", "interfaces"],
            timeout=10,
        )
        if rc != 0:
            return ""
        for line in stdout.splitlines():
            if "name" in line.lower() and ":" in line:
                _, _, name = line.partition(":")
                return name.strip()
        return ""
