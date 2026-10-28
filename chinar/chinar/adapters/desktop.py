"""Desktop automation and system information adapter for Windows.

Uses pyautogui for input simulation, ctypes for screen lock,
and psutil/PowerShell for system information queries.
"""

from __future__ import annotations

import ctypes
import datetime
import logging
import os
from pathlib import Path
from typing import Any

from chinar.adapters.base import run_powershell
from chinar.schemas import ActionResult, ActionStatus

logger = logging.getLogger("chinar.adapters.desktop")

try:
    import pyautogui
    pyautogui.FAILSAFE = True  # Move mouse to corner to abort
    pyautogui.PAUSE = 0.05
except ImportError:
    pyautogui = None  # type: ignore[assignment]

try:
    import psutil
except ImportError:
    psutil = None  # type: ignore[assignment]


# Allowed keys for press_key (safety restriction)
ALLOWED_KEYS = {
    "enter", "return", "tab", "escape", "esc", "space", "backspace", "delete",
    "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
    "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12",
    "ctrl+c", "ctrl+v", "ctrl+x", "ctrl+z", "ctrl+a", "ctrl+s",
    "ctrl+shift+esc", "alt+tab", "alt+f4", "win",
    "printscreen", "volumeup", "volumedown", "volumemute",
    "playpause", "nexttrack", "prevtrack",
}


class DesktopAdapter:
    """Desktop automation and system information."""

    async def execute(self, action: str, parameters: dict[str, Any]) -> ActionResult:
        handlers = {
            "type_text": self._type_text,
            "press_key": self._press_key,
            "screenshot": self._screenshot,
            "lock_screen": self._lock_screen,
            "get_battery": self._get_battery,
            "get_system_info": self._get_system_info,
            "get_time": self._get_time,
            "get_date": self._get_date,
            "execute_shell": self._execute_shell,
            "help": self._help,
            "history": self._history,
        }
        handler = handlers.get(action)
        if handler is None:
            return ActionResult(
                status=ActionStatus.UNSUPPORTED,
                message=f"Unknown desktop action: {action}",
                intent=action,
            )
        return await handler(parameters)

    async def _type_text(self, params: dict[str, Any]) -> ActionResult:
        text = params.get("text", "")
        if not text:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No text to type.",
                intent="type_text",
            )
        if pyautogui is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="pyautogui not installed — cannot simulate typing.",
                intent="type_text",
            )

        try:
            pyautogui.typewrite(text, interval=0.02) if text.isascii() else pyautogui.write(text)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Typed {len(text)} characters.",
                intent="type_text",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Typing failed: {exc}",
                intent="type_text",
            )

    async def _press_key(self, params: dict[str, Any]) -> ActionResult:
        key = params.get("key", "").strip().lower()
        if not key:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No key specified.",
                intent="press_key",
            )
        if pyautogui is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="pyautogui not installed.",
                intent="press_key",
            )

        # Safety: only allow known keys
        if key not in ALLOWED_KEYS:
            return ActionResult(
                status=ActionStatus.DENIED,
                message=f"Key '{key}' is not in the allowed keys list.",
                intent="press_key",
            )

        try:
            if "+" in key:
                parts = [k.strip() for k in key.split("+")]
                pyautogui.hotkey(*parts)
            else:
                pyautogui.press(key)

            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Pressed {key}.",
                intent="press_key",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Key press failed: {exc}",
                intent="press_key",
            )

    async def _screenshot(self, params: dict[str, Any]) -> ActionResult:
        if pyautogui is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="pyautogui not installed.",
                intent="screenshot",
            )
        try:
            save_path = params.get("save_path")
            if not save_path:
                save_dir = Path.home() / "Pictures" / "Chinar"
                save_dir.mkdir(parents=True, exist_ok=True)
                ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
                save_path = str(save_dir / f"screenshot_{ts}.png")

            img = pyautogui.screenshot()
            img.save(save_path)
            logger.info("Screenshot saved: %s", save_path)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Screenshot saved to {Path(save_path).name}.",
                data={"path": save_path},
                intent="screenshot",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Screenshot failed: {exc}",
                intent="screenshot",
            )

    async def _lock_screen(self, params: dict[str, Any]) -> ActionResult:
        try:
            ctypes.windll.user32.LockWorkStation()
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Screen locked.",
                intent="lock_screen",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Could not lock screen: {exc}",
                intent="lock_screen",
            )

    async def _get_battery(self, params: dict[str, Any]) -> ActionResult:
        if psutil is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="psutil not installed — cannot query battery.",
                intent="get_battery",
            )
        try:
            battery = psutil.sensors_battery()
            if battery is None:
                return ActionResult(
                    status=ActionStatus.SUCCESS,
                    message="No battery detected (desktop system).",
                    intent="get_battery",
                )
            percent = battery.percent
            plugged = battery.power_plugged
            status = "charging" if plugged else "discharging"
            secs_left = battery.secsleft
            time_str = ""
            if secs_left and secs_left > 0 and not plugged:
                hours = secs_left // 3600
                mins = (secs_left % 3600) // 60
                time_str = f" (~{hours}h {mins}m remaining)"

            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Battery: {percent}% ({status}){time_str}.",
                data={"percent": percent, "plugged": plugged},
                intent="get_battery",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Battery query failed: {exc}",
                intent="get_battery",
            )

    async def _get_system_info(self, params: dict[str, Any]) -> ActionResult:
        info: dict[str, Any] = {}
        try:
            if psutil:
                info["cpu_percent"] = psutil.cpu_percent(interval=0.5)
                mem = psutil.virtual_memory()
                info["memory_total_gb"] = round(mem.total / (1024 ** 3), 1)
                info["memory_used_percent"] = mem.percent
                info["cpu_count"] = psutil.cpu_count()

            msg_parts = []
            if "cpu_percent" in info:
                msg_parts.append(f"CPU: {info['cpu_percent']}%")
            if "memory_used_percent" in info:
                msg_parts.append(
                    f"Memory: {info['memory_used_percent']}% of {info['memory_total_gb']} GB"
                )

            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=". ".join(msg_parts) + "." if msg_parts else "System info unavailable.",
                data=info,
                intent="get_system_info",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"System info query failed: {exc}",
                intent="get_system_info",
            )

    async def _get_time(self, params: dict[str, Any]) -> ActionResult:
        now = datetime.datetime.now()
        time_str = now.strftime("%I:%M %p")
        return ActionResult(
            status=ActionStatus.SUCCESS,
            message=f"The time is {time_str}.",
            data={"time": time_str},
            intent="get_time",
        )

    async def _get_date(self, params: dict[str, Any]) -> ActionResult:
        now = datetime.datetime.now()
        date_str = now.strftime("%A, %B %d, %Y")
        return ActionResult(
            status=ActionStatus.SUCCESS,
            message=f"Today is {date_str}.",
            data={"date": date_str},
            intent="get_date",
        )

    async def _execute_shell(self, params: dict[str, Any]) -> ActionResult:
        command = params.get("command", "")
        if not command:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="No command specified.",
                intent="execute_shell",
            )

        # This is a high-risk action — confirmation should already be checked by policy
        rc, stdout, stderr = await run_powershell(command, timeout=30)

        output = stdout if stdout else stderr
        # Truncate long output
        if len(output) > 500:
            output = output[:500] + "... (truncated)"

        if rc == 0:
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Command completed.\n{output}" if output else "Command completed.",
                data={"return_code": rc, "output": output},
                intent="execute_shell",
            )
        return ActionResult(
            status=ActionStatus.FAILURE,
            message=f"Command failed (exit code {rc}).\n{output}" if output else f"Command failed (exit code {rc}).",
            data={"return_code": rc, "output": output},
            intent="execute_shell",
        )

    async def _help(self, params: dict[str, Any]) -> ActionResult:
        commands = [
            "open <app name> — Launch an application",
            "close <app name> — Close an application",
            "set volume <0-100> — Set system volume",
            "mute / unmute — Toggle audio mute",
            "play / pause — Media playback toggle",
            "next track / previous track — Skip media",
            "battery — Check battery status",
            "system info — CPU and memory usage",
            "time / date — Current time or date",
            "wifi status — Check Wi-Fi connection",
            "screenshot — Take a screenshot",
            "lock screen — Lock the computer",
            "search <query> — Search for files",
            "history — View command history",
            "emergency stop — Disable all automation",
            "stop listening — Pause voice input",
        ]
        msg = "Available commands:\n" + "\n".join(f"  • {c}" for c in commands)
        return ActionResult(
            status=ActionStatus.SUCCESS,
            message=msg,
            intent="help",
        )

    async def _history(self, params: dict[str, Any]) -> ActionResult:
        # History is handled by the pipeline/application layer
        return ActionResult(
            status=ActionStatus.SUCCESS,
            message="History will be displayed by the application.",
            data={"delegate": True},
            intent="history",
        )
