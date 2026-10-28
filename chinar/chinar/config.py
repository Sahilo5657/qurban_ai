"""Configuration loader and validator for Chinar.

Reads TOML configuration, validates all fields, and provides typed access
to settings throughout the application.
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

if sys.version_info >= (3, 11):
    import tomllib
else:
    import tomli as tomllib


def _project_root() -> Path:
    """Resolve the project root directory (where pyproject.toml lives)."""
    return Path(__file__).resolve().parent.parent


@dataclass
class GeneralConfig:
    name: str = "Chinar"
    language: str = "en"
    enable_history: bool = True
    max_history: int = 1000
    emergency_stop: bool = False


@dataclass
class AudioConfig:
    mode: str = "push_to_talk"
    sample_rate: int = 16000
    channels: int = 1
    voice_activity_detection: bool = True
    vad_energy_threshold: float = 0.02
    max_recording_seconds: int = 30
    chunk_ms: int = 100
    input_device: Optional[int] = None


@dataclass
class STTConfig:
    engine: str = "vosk"
    model_path: str = "models/stt/vosk-model-small-en-us-0.15"
    language: str = "en-us"


@dataclass
class TTSConfig:
    enabled: bool = True
    engine: str = "pyttsx3"
    rate: int = 175
    volume: float = 0.9
    voice_id: Optional[str] = None
    muted: bool = False


@dataclass
class LLMConfig:
    provider: str = "ollama"
    endpoint: str = "http://127.0.0.1:11434"
    model: str = ""
    enabled: bool = True
    request_timeout_seconds: int = 15
    fallback_to_rules: bool = True


@dataclass
class LoggingConfig:
    level: str = "INFO"
    file: str = "logs/chinar.log"
    max_bytes: int = 5_242_880
    backup_count: int = 3


@dataclass
class DatabaseConfig:
    path: str = "data/chinar.db"
    wal_mode: bool = True


@dataclass
class SecurityConfig:
    require_confirmation: bool = True
    allowed_directories: list[str] = field(default_factory=list)
    subprocess_timeout: int = 30
    audit_logging: bool = True
    rate_limit: int = 30


@dataclass
class ChinarConfig:
    """Top-level configuration container."""

    general: GeneralConfig = field(default_factory=GeneralConfig)
    audio: AudioConfig = field(default_factory=AudioConfig)
    stt: STTConfig = field(default_factory=STTConfig)
    tts: TTSConfig = field(default_factory=TTSConfig)
    llm: LLMConfig = field(default_factory=LLMConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)
    database: DatabaseConfig = field(default_factory=DatabaseConfig)
    security: SecurityConfig = field(default_factory=SecurityConfig)

    # Resolved at load time
    project_root: Path = field(default_factory=_project_root)

    def resolve_path(self, relative: str) -> Path:
        """Resolve a config-relative path against the project root."""
        p = Path(relative)
        if p.is_absolute():
            return p
        return self.project_root / p

    @property
    def db_path(self) -> Path:
        return self.resolve_path(self.database.path)

    @property
    def log_path(self) -> Path:
        return self.resolve_path(self.logging.file)

    @property
    def stt_model_path(self) -> Path:
        return self.resolve_path(self.stt.model_path)


class ConfigError(Exception):
    """Raised when configuration is invalid."""


def _apply_section(target: Any, data: dict[str, Any], section_name: str) -> list[str]:
    """Apply a dict of values onto a dataclass instance. Returns warnings."""
    warnings: list[str] = []
    for key, value in data.items():
        if hasattr(target, key):
            expected_type = type(getattr(target, key))
            if expected_type is not type(None) and not isinstance(value, expected_type):
                # Allow int -> float coercion
                if expected_type is float and isinstance(value, int):
                    value = float(value)
                else:
                    warnings.append(
                        f"[{section_name}] '{key}': expected {expected_type.__name__}, "
                        f"got {type(value).__name__}"
                    )
                    continue
            setattr(target, key, value)
        else:
            warnings.append(f"[{section_name}] unknown key: '{key}'")
    return warnings


def load_config(config_path: Optional[Path] = None) -> tuple[ChinarConfig, list[str]]:
    """Load and validate configuration from a TOML file.

    Args:
        config_path: Path to the TOML config file. If None, searches for
                     config/config.toml relative to the project root.

    Returns:
        Tuple of (validated config, list of warning messages).

    Raises:
        ConfigError: If the file cannot be read or contains critical errors.
    """
    root = _project_root()
    warnings: list[str] = []

    if config_path is None:
        config_path = root / "config" / "config.toml"

    cfg = ChinarConfig(project_root=root)

    if not config_path.exists():
        warnings.append(
            f"Config file not found at {config_path}; using defaults. "
            f"Copy config/config.example.toml to config/config.toml to customize."
        )
        return cfg, warnings

    try:
        with open(config_path, "rb") as f:
            raw = tomllib.load(f)
    except Exception as exc:
        raise ConfigError(f"Failed to parse config file {config_path}: {exc}") from exc

    section_map = {
        "general": cfg.general,
        "audio": cfg.audio,
        "stt": cfg.stt,
        "tts": cfg.tts,
        "llm": cfg.llm,
        "logging": cfg.logging,
        "database": cfg.database,
        "security": cfg.security,
    }

    for section_name, target in section_map.items():
        if section_name in raw:
            section_warnings = _apply_section(target, raw[section_name], section_name)
            warnings.extend(section_warnings)

    # Validate critical constraints
    validation_errors = _validate(cfg)
    if validation_errors:
        raise ConfigError(
            "Configuration validation failed:\n" +
            "\n".join(f"  - {e}" for e in validation_errors)
        )

    return cfg, warnings


def _validate(cfg: ChinarConfig) -> list[str]:
    """Validate configuration constraints. Returns list of error messages."""
    errors: list[str] = []

    if cfg.audio.mode not in ("push_to_talk", "wake_word"):
        errors.append(f"[audio] mode must be 'push_to_talk' or 'wake_word', got '{cfg.audio.mode}'")

    if cfg.audio.sample_rate not in (8000, 16000, 22050, 44100, 48000):
        errors.append(f"[audio] sample_rate {cfg.audio.sample_rate} is not a standard rate")

    if cfg.audio.channels not in (1, 2):
        errors.append(f"[audio] channels must be 1 or 2, got {cfg.audio.channels}")

    if not 0.0 <= cfg.audio.vad_energy_threshold <= 1.0:
        errors.append("[audio] vad_energy_threshold must be between 0.0 and 1.0")

    if cfg.audio.max_recording_seconds < 1 or cfg.audio.max_recording_seconds > 300:
        errors.append("[audio] max_recording_seconds must be between 1 and 300")

    if cfg.stt.engine not in ("vosk",):
        errors.append(f"[stt] engine '{cfg.stt.engine}' is not supported; use 'vosk'")

    if cfg.tts.engine not in ("pyttsx3",):
        errors.append(f"[tts] engine '{cfg.tts.engine}' is not supported; use 'pyttsx3'")

    if not 0.0 <= cfg.tts.volume <= 1.0:
        errors.append("[tts] volume must be between 0.0 and 1.0")

    if cfg.llm.provider not in ("ollama", "none"):
        errors.append(f"[llm] provider '{cfg.llm.provider}' is not supported")

    if cfg.llm.request_timeout_seconds < 1:
        errors.append("[llm] request_timeout_seconds must be >= 1")

    if cfg.security.subprocess_timeout < 1:
        errors.append("[security] subprocess_timeout must be >= 1")

    if cfg.security.rate_limit < 0:
        errors.append("[security] rate_limit must be >= 0")

    valid_levels = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}
    if cfg.logging.level.upper() not in valid_levels:
        errors.append(f"[logging] level must be one of {valid_levels}")

    return errors
