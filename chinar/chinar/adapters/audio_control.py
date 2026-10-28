"""Audio control adapter for Windows.

Uses pycaw (Python Core Audio Windows) for volume control,
and virtual key simulation for media playback controls.
"""

from __future__ import annotations

import ctypes
import logging
from typing import Any, Optional

from chinar.schemas import ActionResult, ActionStatus

logger = logging.getLogger("chinar.adapters.audio_control")

# Windows virtual key codes for media
VK_VOLUME_MUTE = 0xAD
VK_VOLUME_DOWN = 0xAE
VK_VOLUME_UP = 0xAF
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_STOP = 0xB2
VK_MEDIA_PLAY_PAUSE = 0xB3

KEYEVENTF_KEYUP = 0x0002

# Input type constants
INPUT_KEYBOARD = 1


def _press_vk(vk_code: int) -> None:
    """Simulate a key press and release using SendInput."""
    # We use ctypes to call SendInput for media keys
    user32 = ctypes.windll.user32

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", ctypes.c_ushort),
            ("wScan", ctypes.c_ushort),
            ("dwFlags", ctypes.c_ulong),
            ("time", ctypes.c_ulong),
            ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
        ]

    class INPUT(ctypes.Structure):
        class _INPUT_UNION(ctypes.Union):
            _fields_ = [("ki", KEYBDINPUT)]
        _fields_ = [
            ("type", ctypes.c_ulong),
            ("input", _INPUT_UNION),
        ]

    # Key down
    inp_down = INPUT()
    inp_down.type = INPUT_KEYBOARD
    inp_down.input.ki.wVk = vk_code
    inp_down.input.ki.dwFlags = 0

    # Key up
    inp_up = INPUT()
    inp_up.type = INPUT_KEYBOARD
    inp_up.input.ki.wVk = vk_code
    inp_up.input.ki.dwFlags = KEYEVENTF_KEYUP

    user32.SendInput(1, ctypes.byref(inp_down), ctypes.sizeof(INPUT))
    user32.SendInput(1, ctypes.byref(inp_up), ctypes.sizeof(INPUT))


def _get_volume_interface() -> Optional[Any]:
    """Get the Windows audio endpoint volume interface via pycaw."""
    try:
        from comtypes import CLSCTX_ALL
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

        devices = AudioUtilities.GetSpeakers()
        interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        return interface.QueryInterface(IAudioEndpointVolume)
    except Exception as exc:
        logger.warning("Could not access audio endpoint: %s", exc)
        return None


class AudioControlAdapter:
    """Windows audio and media control."""

    async def execute(self, action: str, parameters: dict[str, Any]) -> ActionResult:
        handlers = {
            "set_volume": self._set_volume,
            "get_volume": self._get_volume,
            "mute_audio": self._mute,
            "unmute_audio": self._unmute,
            "media_play_pause": self._media_play_pause,
            "media_next": self._media_next,
            "media_previous": self._media_previous,
        }
        handler = handlers.get(action)
        if handler is None:
            return ActionResult(
                status=ActionStatus.UNSUPPORTED,
                message=f"Unknown audio action: {action}",
                intent=action,
            )
        return await handler(parameters)

    async def _set_volume(self, params: dict[str, Any]) -> ActionResult:
        level = params.get("level", 50)
        volume = _get_volume_interface()
        if volume is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not access audio controls.",
                intent="set_volume",
            )
        try:
            # pycaw uses scalar 0.0–1.0
            scalar = max(0.0, min(1.0, level / 100.0))
            volume.SetMasterVolumeLevelScalar(scalar, None)
            logger.info("Volume set to %d%%", level)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Volume set to {level}%.",
                data={"level": level},
                intent="set_volume",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Failed to set volume: {exc}",
                intent="set_volume",
            )

    async def _get_volume(self, params: dict[str, Any]) -> ActionResult:
        volume = _get_volume_interface()
        if volume is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not access audio controls.",
                intent="get_volume",
            )
        try:
            scalar = volume.GetMasterVolumeLevelScalar()
            level = int(scalar * 100)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message=f"Current volume is {level}%.",
                data={"level": level},
                intent="get_volume",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Failed to get volume: {exc}",
                intent="get_volume",
            )

    async def _mute(self, params: dict[str, Any]) -> ActionResult:
        volume = _get_volume_interface()
        if volume is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not access audio controls.",
                intent="mute_audio",
            )
        try:
            volume.SetMute(True, None)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Audio muted.",
                intent="mute_audio",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Failed to mute: {exc}",
                intent="mute_audio",
            )

    async def _unmute(self, params: dict[str, Any]) -> ActionResult:
        volume = _get_volume_interface()
        if volume is None:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message="Could not access audio controls.",
                intent="unmute_audio",
            )
        try:
            volume.SetMute(False, None)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Audio unmuted.",
                intent="unmute_audio",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Failed to unmute: {exc}",
                intent="unmute_audio",
            )

    async def _media_play_pause(self, params: dict[str, Any]) -> ActionResult:
        try:
            _press_vk(VK_MEDIA_PLAY_PAUSE)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Media play/pause toggled.",
                intent="media_play_pause",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Failed to toggle media: {exc}",
                intent="media_play_pause",
            )

    async def _media_next(self, params: dict[str, Any]) -> ActionResult:
        try:
            _press_vk(VK_MEDIA_NEXT_TRACK)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Skipped to next track.",
                intent="media_next",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Failed: {exc}",
                intent="media_next",
            )

    async def _media_previous(self, params: dict[str, Any]) -> ActionResult:
        try:
            _press_vk(VK_MEDIA_PREV_TRACK)
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="Went to previous track.",
                intent="media_previous",
            )
        except Exception as exc:
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Failed: {exc}",
                intent="media_previous",
            )
