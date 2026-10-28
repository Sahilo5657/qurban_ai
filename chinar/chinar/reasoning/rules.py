"""Deterministic rule-based command parser.

Matches spoken text to structured intents using keyword patterns
and regular expressions. This is the primary command interpreter
that works without any LLM dependency.
"""

from __future__ import annotations

import logging
import re
from typing import Any, Optional

from chinar.schemas import CommandIntent

logger = logging.getLogger("chinar.reasoning.rules")


# --- Pattern definitions ---
# Each rule: (compiled regex, intent_name, param_extractor_func or None)

def _extract_volume_level(match: re.Match) -> dict[str, Any]:
    """Extract volume level from a regex match."""
    level_str = match.group("level")
    try:
        level = int(level_str)
        return {"level": max(0, min(100, level))}
    except (ValueError, IndexError):
        return {"level": 50}


def _extract_app_name(match: re.Match) -> dict[str, Any]:
    """Extract application name from a regex match."""
    return {"app_name": match.group("app").strip()}


def _extract_text(match: re.Match) -> dict[str, Any]:
    """Extract text to type."""
    return {"text": match.group("text").strip()}


def _extract_key(match: re.Match) -> dict[str, Any]:
    """Extract key to press."""
    return {"key": match.group("key").strip().lower()}


def _extract_path(match: re.Match) -> dict[str, Any]:
    """Extract a file or directory path."""
    return {"path": match.group("path").strip()}


def _extract_query(match: re.Match) -> dict[str, Any]:
    """Extract search query."""
    return {"query": match.group("query").strip()}


def _extract_shell_cmd(match: re.Match) -> dict[str, Any]:
    """Extract shell command."""
    return {"command": match.group("cmd").strip()}


# Rule table: (pattern, intent, extractor)
_RULES: list[tuple[re.Pattern, str, Optional[Any]]] = [
    # --- Volume ---
    (re.compile(r"\b(?:set|change)\s+(?:the\s+)?volume\s+(?:to\s+)?(?P<level>\d+)", re.I),
     "set_volume", _extract_volume_level),
    (re.compile(r"\bvolume\s+(?P<level>\d+)", re.I),
     "set_volume", _extract_volume_level),
    (re.compile(r"\b(?:what(?:'s| is)\s+the\s+)?(?:current\s+)?volume\b", re.I),
     "get_volume", None),
    (re.compile(r"\bmute(?:\s+audio|\s+sound|\s+volume)?\b", re.I),
     "mute_audio", None),
    (re.compile(r"\bunmute(?:\s+audio|\s+sound|\s+volume)?\b", re.I),
     "unmute_audio", None),

    # --- Media ---
    (re.compile(r"\b(?:play|pause|play pause)\b", re.I),
     "media_play_pause", None),
    (re.compile(r"\b(?:next\s+(?:track|song)|skip)\b", re.I),
     "media_next", None),
    (re.compile(r"\b(?:previous\s+(?:track|song)|go\s+back)\b", re.I),
     "media_previous", None),

    # --- Applications ---
    (re.compile(r"\b(?:open|launch|start|run)\s+(?P<app>.+)", re.I),
     "open_application", _extract_app_name),
    (re.compile(r"\b(?:close|quit|exit|kill)\s+(?P<app>.+)", re.I),
     "close_application", _extract_app_name),

    # --- Files ---
    (re.compile(r"\b(?:open\s+file|open)\s+(?P<path>[a-zA-Z]:\\[^\s]+)", re.I),
     "open_file", _extract_path),
    (re.compile(r"\b(?:open\s+(?:folder|directory))\s+(?P<path>.+)", re.I),
     "open_directory", _extract_path),
    (re.compile(r"\b(?:search|find|look)\s+(?:for\s+)?(?:files?\s+)?(?P<query>.+)", re.I),
     "search_files", _extract_query),

    # --- System info ---
    (re.compile(r"\b(?:battery|battery\s+(?:level|status|percent))\b", re.I),
     "get_battery", None),
    (re.compile(r"\b(?:system\s+info|system\s+status|cpu|memory|ram)\b", re.I),
     "get_system_info", None),
    (re.compile(r"\b(?:what\s+time|current\s+time|time\s+(?:is\s+it|now))\b", re.I),
     "get_time", None),
    (re.compile(r"\b(?:what(?:'s| is)\s+(?:the\s+)?date|today(?:'s)?\s+date|date\s+today)\b", re.I),
     "get_date", None),

    # --- Network ---
    (re.compile(r"\b(?:wifi|wi-fi)\s+(?:status|state)\b", re.I),
     "get_wifi_status", None),
    (re.compile(r"\b(?:enable|turn\s+on|connect)\s+(?:the\s+)?(?:wifi|wi-fi)\b", re.I),
     "enable_wifi", None),
    (re.compile(r"\b(?:disable|turn\s+off|disconnect)\s+(?:the\s+)?(?:wifi|wi-fi)\b", re.I),
     "disable_wifi", None),

    # --- Bluetooth ---
    (re.compile(r"\bblue\s*tooth\s+(?:status|state)\b", re.I),
     "get_bluetooth_status", None),

    # --- Desktop automation ---
    (re.compile(r"\btype\s+(?P<text>.+)", re.I),
     "type_text", _extract_text),
    (re.compile(r"\bpress\s+(?:key\s+)?(?P<key>[\w+\s]+)", re.I),
     "press_key", _extract_key),
    (re.compile(r"\b(?:take\s+a?\s*)?screenshot\b", re.I),
     "screenshot", None),
    (re.compile(r"\block\s+(?:the\s+)?(?:screen|computer|pc)\b", re.I),
     "lock_screen", None),

    # --- Shell ---
    (re.compile(r"\b(?:run|execute)\s+(?:command\s+)?(?P<cmd>.+)", re.I),
     "execute_shell", _extract_shell_cmd),

    # --- Chinar control ---
    (re.compile(r"\bhelp\b", re.I),
     "help", None),
    (re.compile(r"\b(?:stop\s+listening|be\s+quiet|shut\s+up)\b", re.I),
     "stop_listening", None),
    (re.compile(r"\bemergency\s+stop\b", re.I),
     "emergency_stop", None),
    (re.compile(r"\b(?:show\s+)?history\b", re.I),
     "history", None),
]


class RuleEngine:
    """Deterministic command matcher using keyword patterns."""

    def parse(self, text: str) -> Optional[CommandIntent]:
        """Attempt to match spoken text to a known command intent.

        Args:
            text: The transcribed text to parse.

        Returns:
            A CommandIntent if matched, or None if no rule matched.
        """
        if not text or not text.strip():
            return None

        text = text.strip()

        for pattern, intent_name, extractor in _RULES:
            match = pattern.search(text)
            if match:
                params = extractor(match) if extractor else {}
                logger.debug("Rule matched: '%s' → %s(%s)", text, intent_name, params)
                return CommandIntent(
                    intent=intent_name,
                    parameters=params,
                    raw_text=text,
                    source="rules",
                )

        logger.debug("No rule matched for: '%s'", text)
        return None
