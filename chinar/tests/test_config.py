"""Tests for configuration loading and validation."""

import pytest
from pathlib import Path
from unittest.mock import patch
import tempfile
import os

from chinar.config import load_config, ConfigError, ChinarConfig


class TestConfigDefaults:
    """Test default configuration values."""

    def test_default_config_loads(self):
        """Config loads with defaults when no file exists."""
        config, warnings = load_config(Path("nonexistent/config.toml"))
        assert isinstance(config, ChinarConfig)
        assert len(warnings) > 0  # Should warn about missing file

    def test_default_values(self):
        config, _ = load_config(Path("nonexistent.toml"))
        assert config.general.name == "Chinar"
        assert config.audio.mode == "push_to_talk"
        assert config.audio.sample_rate == 16000
        assert config.audio.channels == 1
        assert config.stt.engine == "vosk"
        assert config.tts.engine == "pyttsx3"
        assert config.llm.provider == "ollama"
        assert config.security.require_confirmation is True

    def test_emergency_stop_default_off(self):
        config, _ = load_config(Path("nonexistent.toml"))
        assert config.general.emergency_stop is False


class TestConfigValidation:
    """Test configuration validation rules."""

    def _write_config(self, tmpdir: Path, content: str) -> Path:
        config_path = tmpdir / "config.toml"
        config_path.write_text(content)
        return config_path

    def test_invalid_audio_mode(self, tmp_path):
        p = self._write_config(tmp_path, '[audio]\nmode = "invalid"')
        with pytest.raises(ConfigError, match="mode"):
            load_config(p)

    def test_invalid_sample_rate(self, tmp_path):
        p = self._write_config(tmp_path, '[audio]\nsample_rate = 12345')
        with pytest.raises(ConfigError, match="sample_rate"):
            load_config(p)

    def test_invalid_channels(self, tmp_path):
        p = self._write_config(tmp_path, '[audio]\nchannels = 5')
        with pytest.raises(ConfigError, match="channels"):
            load_config(p)

    def test_invalid_vad_threshold(self, tmp_path):
        p = self._write_config(tmp_path, '[audio]\nvad_energy_threshold = 2.0')
        with pytest.raises(ConfigError, match="vad_energy_threshold"):
            load_config(p)

    def test_invalid_stt_engine(self, tmp_path):
        p = self._write_config(tmp_path, '[stt]\nengine = "whisper"')
        with pytest.raises(ConfigError, match="engine"):
            load_config(p)

    def test_invalid_tts_engine(self, tmp_path):
        p = self._write_config(tmp_path, '[tts]\nengine = "espeak"')
        with pytest.raises(ConfigError, match="engine"):
            load_config(p)

    def test_invalid_llm_provider(self, tmp_path):
        p = self._write_config(tmp_path, '[llm]\nprovider = "openai"')
        with pytest.raises(ConfigError, match="provider"):
            load_config(p)

    def test_invalid_log_level(self, tmp_path):
        p = self._write_config(tmp_path, '[logging]\nlevel = "TRACE"')
        with pytest.raises(ConfigError, match="level"):
            load_config(p)

    def test_negative_rate_limit(self, tmp_path):
        p = self._write_config(tmp_path, '[security]\nrate_limit = -1')
        with pytest.raises(ConfigError, match="rate_limit"):
            load_config(p)

    def test_valid_config(self, tmp_path):
        content = """
[general]
name = "TestChinar"

[audio]
mode = "push_to_talk"
sample_rate = 16000

[stt]
engine = "vosk"

[llm]
provider = "none"
"""
        p = self._write_config(tmp_path, content)
        config, warnings = load_config(p)
        assert config.general.name == "TestChinar"
        assert config.llm.provider == "none"

    def test_unknown_keys_produce_warnings(self, tmp_path):
        content = '[general]\nunknown_field = true'
        p = self._write_config(tmp_path, content)
        config, warnings = load_config(p)
        assert any("unknown key" in w for w in warnings)

    def test_type_mismatch_produces_warning(self, tmp_path):
        content = '[general]\nenable_history = "yes"'  # should be bool
        p = self._write_config(tmp_path, content)
        config, warnings = load_config(p)
        assert any("expected" in w for w in warnings)


class TestPathResolution:
    """Test configuration path resolution."""

    def test_relative_path_resolved(self):
        config, _ = load_config(Path("nonexistent.toml"))
        db_path = config.db_path
        assert db_path.is_absolute()
        assert str(db_path).endswith("chinar.db")

    def test_absolute_path_preserved(self):
        config, _ = load_config(Path("nonexistent.toml"))
        if os.name == "nt":
            result = config.resolve_path("C:\\absolute\\path")
            assert str(result) == "C:\\absolute\\path"
        else:
            result = config.resolve_path("/absolute/path")
            assert str(result) == "/absolute/path"
