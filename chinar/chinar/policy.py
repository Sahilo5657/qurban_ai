"""Security policy, authorization, and rate limiting for Chinar.

Every action passes through the policy engine before execution.
High-risk actions require explicit user confirmation.
"""

from __future__ import annotations

import logging
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any

from chinar.schemas import ActionResult, ActionStatus, CommandIntent, RiskLevel

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.policy")


# --- Action risk registry ---

ACTION_REGISTRY: dict[str, dict[str, Any]] = {
    "open_application":      {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "close_application":     {"risk": RiskLevel.MEDIUM,   "confirmation_required": False},
    "set_volume":            {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "get_volume":            {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "mute_audio":            {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "unmute_audio":          {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "media_play_pause":      {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "media_next":            {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "media_previous":        {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "open_file":             {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "open_directory":        {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "search_files":          {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "get_battery":           {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "get_system_info":       {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "get_time":              {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "get_date":              {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "get_wifi_status":       {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "enable_wifi":           {"risk": RiskLevel.MEDIUM,   "confirmation_required": False},
    "disable_wifi":          {"risk": RiskLevel.MEDIUM,   "confirmation_required": False},
    "type_text":             {"risk": RiskLevel.MEDIUM,   "confirmation_required": False},
    "press_key":             {"risk": RiskLevel.MEDIUM,   "confirmation_required": False},
    "screenshot":            {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "lock_screen":           {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "execute_shell":         {"risk": RiskLevel.HIGH,     "confirmation_required": True},
    "get_bluetooth_status":  {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "help":                  {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "stop_listening":        {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "emergency_stop":        {"risk": RiskLevel.LOW,      "confirmation_required": False},
    "history":               {"risk": RiskLevel.LOW,      "confirmation_required": False},
}


class PolicyEngine:
    """Evaluates authorization policies for command intents."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._action_timestamps: deque[float] = deque()
        self._rate_limit = config.security.rate_limit
        self._allowed_dirs = [
            Path(d).resolve() for d in config.security.allowed_directories
        ]

    def authorize(self, intent: CommandIntent) -> ActionResult | None:
        """Check if an intent is authorized.

        Returns:
            None if authorized (proceed with execution).
            An ActionResult if denied or confirmation is required.
        """
        # Emergency stop check
        if self._config.general.emergency_stop:
            logger.warning("Emergency stop is active — denying action: %s", intent.intent)
            return ActionResult(
                status=ActionStatus.DENIED,
                message="Emergency stop is active. All automation is disabled.",
                intent=intent.intent,
            )

        # Check if action is registered
        registry_entry = ACTION_REGISTRY.get(intent.intent)
        if registry_entry is None:
            logger.warning("Unregistered action denied: %s", intent.intent)
            return ActionResult(
                status=ActionStatus.DENIED,
                message=f"Action '{intent.intent}' is not registered in the policy.",
                intent=intent.intent,
            )

        # Rate limiting
        if self._rate_limit > 0:
            now = time.monotonic()
            # Purge entries older than 60 seconds
            while self._action_timestamps and self._action_timestamps[0] < now - 60:
                self._action_timestamps.popleft()
            if len(self._action_timestamps) >= self._rate_limit:
                logger.warning("Rate limit exceeded (%d/min)", self._rate_limit)
                return ActionResult(
                    status=ActionStatus.DENIED,
                    message=f"Rate limit exceeded ({self._rate_limit} actions per minute).",
                    intent=intent.intent,
                )
            self._action_timestamps.append(now)

        # Path validation for file operations
        path_result = self._validate_paths(intent)
        if path_result is not None:
            return path_result

        # Confirmation requirement
        needs_confirm = registry_entry["confirmation_required"]
        if needs_confirm and self._config.security.require_confirmation:
            if not intent.requires_confirmation:
                logger.info(
                    "Action '%s' requires confirmation (risk: %s)",
                    intent.intent,
                    registry_entry["risk"].value,
                )
                return ActionResult(
                    status=ActionStatus.CONFIRMATION_REQUIRED,
                    message=(
                        f"Action '{intent.intent}' is high-risk and requires confirmation. "
                        f"Please confirm to proceed."
                    ),
                    intent=intent.intent,
                )

        logger.debug("Action '%s' authorized", intent.intent)
        return None  # Authorized

    def _validate_paths(self, intent: CommandIntent) -> ActionResult | None:
        """Validate filesystem paths in intent parameters."""
        path_params = ("path", "save_path", "directory")
        for param in path_params:
            raw_path = intent.parameters.get(param)
            if raw_path is None:
                continue

            try:
                resolved = Path(raw_path).resolve()
            except (ValueError, OSError):
                return ActionResult(
                    status=ActionStatus.DENIED,
                    message=f"Invalid path: '{raw_path}'",
                    intent=intent.intent,
                )

            # Block obvious traversal
            raw_str = str(raw_path)
            if ".." in raw_str:
                logger.warning("Path traversal attempt blocked: %s", raw_path)
                return ActionResult(
                    status=ActionStatus.DENIED,
                    message="Path traversal is not allowed.",
                    intent=intent.intent,
                )

            # Check allowed directories if configured
            if self._allowed_dirs:
                allowed = any(
                    self._is_within(resolved, allowed_dir)
                    for allowed_dir in self._allowed_dirs
                )
                if not allowed:
                    return ActionResult(
                        status=ActionStatus.DENIED,
                        message=f"Path '{resolved}' is outside allowed directories.",
                        intent=intent.intent,
                    )

        return None

    @staticmethod
    def _is_within(path: Path, directory: Path) -> bool:
        """Check if path is within directory (safely)."""
        try:
            path.relative_to(directory)
            return True
        except ValueError:
            return False

    def get_risk_level(self, intent_name: str) -> RiskLevel | None:
        """Get the risk level of an action."""
        entry = ACTION_REGISTRY.get(intent_name)
        return entry["risk"] if entry else None
