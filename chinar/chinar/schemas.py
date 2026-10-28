"""Schemas for structured command intents and action results.

All data exchanged between the reasoning layer and the execution layer
uses these typed structures. Model output is validated against these
schemas before any action is taken.
"""

from __future__ import annotations

import enum
import json
from dataclasses import dataclass, field, asdict
from typing import Any, Optional


class RiskLevel(enum.Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class ActionStatus(enum.Enum):
    SUCCESS = "success"
    FAILURE = "failure"
    DENIED = "denied"
    TIMEOUT = "timeout"
    CONFIRMATION_REQUIRED = "confirmation_required"
    UNSUPPORTED = "unsupported"


@dataclass
class CommandIntent:
    """A validated, structured action parsed from user speech."""

    intent: str
    parameters: dict[str, Any] = field(default_factory=dict)
    requires_confirmation: bool = False
    raw_text: str = ""
    source: str = "rules"  # "rules" or "llm"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ActionResult:
    """Result from executing an action through an adapter."""

    status: ActionStatus
    message: str
    data: dict[str, Any] = field(default_factory=dict)
    intent: str = ""

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["status"] = self.status.value
        return d

    @property
    def is_success(self) -> bool:
        return self.status == ActionStatus.SUCCESS


# --- Known intents registry ---

KNOWN_INTENTS: dict[str, dict[str, Any]] = {
    # Application control
    "open_application": {
        "required_params": ["app_name"],
        "optional_params": [],
        "param_types": {"app_name": str},
    },
    "close_application": {
        "required_params": ["app_name"],
        "optional_params": [],
        "param_types": {"app_name": str},
    },
    # Volume
    "set_volume": {
        "required_params": ["level"],
        "optional_params": [],
        "param_types": {"level": (int, float)},
        "param_ranges": {"level": (0, 100)},
    },
    "get_volume": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "mute_audio": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "unmute_audio": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    # Media
    "media_play_pause": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "media_next": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "media_previous": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    # Files
    "open_file": {
        "required_params": ["path"],
        "optional_params": [],
        "param_types": {"path": str},
    },
    "open_directory": {
        "required_params": ["path"],
        "optional_params": [],
        "param_types": {"path": str},
    },
    "search_files": {
        "required_params": ["query"],
        "optional_params": ["directory"],
        "param_types": {"query": str, "directory": str},
    },
    # System info
    "get_battery": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "get_system_info": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "get_time": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "get_date": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    # Network
    "get_wifi_status": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "enable_wifi": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "disable_wifi": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    # Desktop
    "type_text": {
        "required_params": ["text"],
        "optional_params": [],
        "param_types": {"text": str},
    },
    "press_key": {
        "required_params": ["key"],
        "optional_params": [],
        "param_types": {"key": str},
    },
    "screenshot": {
        "required_params": [],
        "optional_params": ["save_path"],
        "param_types": {"save_path": str},
    },
    "lock_screen": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    # Shell
    "execute_shell": {
        "required_params": ["command"],
        "optional_params": [],
        "param_types": {"command": str},
    },
    # Bluetooth
    "get_bluetooth_status": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    # Chinar internal
    "help": {
        "required_params": [],
        "optional_params": ["topic"],
        "param_types": {"topic": str},
    },
    "stop_listening": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "emergency_stop": {
        "required_params": [],
        "optional_params": [],
        "param_types": {},
    },
    "history": {
        "required_params": [],
        "optional_params": ["count"],
        "param_types": {"count": int},
    },
}


def validate_intent(intent_data: dict[str, Any]) -> CommandIntent | str:
    """Validate a parsed intent dict against the known intent registry.

    Returns:
        A CommandIntent on success, or an error message string on failure.
    """
    if not isinstance(intent_data, dict):
        return "Intent data must be a dictionary"

    intent_name = intent_data.get("intent")
    if not intent_name or not isinstance(intent_name, str):
        return "Missing or invalid 'intent' field"

    if intent_name not in KNOWN_INTENTS:
        return f"Unknown intent: '{intent_name}'"

    schema = KNOWN_INTENTS[intent_name]
    params = intent_data.get("parameters", {})
    if not isinstance(params, dict):
        return "'parameters' must be a dictionary"

    # Check required parameters
    for req in schema["required_params"]:
        if req not in params:
            return f"Missing required parameter '{req}' for intent '{intent_name}'"

    # Check parameter types
    all_known_params = set(schema["required_params"]) | set(schema.get("optional_params", []))
    for key, value in params.items():
        if key not in all_known_params:
            return f"Unexpected parameter '{key}' for intent '{intent_name}'"
        expected = schema["param_types"].get(key)
        if expected and not isinstance(value, expected):
            return (
                f"Parameter '{key}' for '{intent_name}': "
                f"expected {expected}, got {type(value).__name__}"
            )

    # Check parameter ranges
    for param_name, (low, high) in schema.get("param_ranges", {}).items():
        if param_name in params:
            val = params[param_name]
            if isinstance(val, (int, float)) and not (low <= val <= high):
                return f"Parameter '{param_name}' must be between {low} and {high}, got {val}"

    return CommandIntent(
        intent=intent_name,
        parameters=params,
        requires_confirmation=intent_data.get("requires_confirmation", False),
        raw_text=intent_data.get("raw_text", ""),
        source=intent_data.get("source", "rules"),
    )


def parse_llm_response(raw_json: str) -> CommandIntent | str:
    """Parse a raw JSON string from the LLM into a validated CommandIntent.

    Returns:
        A CommandIntent on success, or an error message string on failure.
    """
    try:
        data = json.loads(raw_json)
    except json.JSONDecodeError as exc:
        return f"Invalid JSON from LLM: {exc}"

    if not isinstance(data, dict):
        return "LLM response must be a JSON object"

    data.setdefault("source", "llm")
    return validate_intent(data)
