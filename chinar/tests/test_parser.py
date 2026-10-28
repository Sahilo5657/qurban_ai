"""Tests for the deterministic rule-based command parser."""

import pytest
from chinar.reasoning.rules import RuleEngine


@pytest.fixture
def engine():
    return RuleEngine()


class TestVolumeCommands:
    def test_set_volume(self, engine):
        intent = engine.parse("set volume to 50")
        assert intent is not None
        assert intent.intent == "set_volume"
        assert intent.parameters["level"] == 50

    def test_volume_number_only(self, engine):
        intent = engine.parse("volume 75")
        assert intent is not None
        assert intent.intent == "set_volume"
        assert intent.parameters["level"] == 75

    def test_volume_clamped_to_100(self, engine):
        intent = engine.parse("set volume to 150")
        assert intent is not None
        assert intent.parameters["level"] == 100

    def test_get_volume(self, engine):
        intent = engine.parse("what's the volume")
        assert intent is not None
        assert intent.intent == "get_volume"

    def test_mute(self, engine):
        intent = engine.parse("mute")
        assert intent is not None
        assert intent.intent == "mute_audio"

    def test_unmute(self, engine):
        intent = engine.parse("unmute audio")
        assert intent is not None
        assert intent.intent == "unmute_audio"


class TestMediaCommands:
    def test_play_pause(self, engine):
        intent = engine.parse("play")
        assert intent is not None
        assert intent.intent == "media_play_pause"

    def test_next_track(self, engine):
        intent = engine.parse("next track")
        assert intent is not None
        assert intent.intent == "media_next"

    def test_skip(self, engine):
        intent = engine.parse("skip")
        assert intent is not None
        assert intent.intent == "media_next"

    def test_previous_track(self, engine):
        intent = engine.parse("previous track")
        assert intent is not None
        assert intent.intent == "media_previous"


class TestApplicationCommands:
    def test_open_notepad(self, engine):
        intent = engine.parse("open notepad")
        assert intent is not None
        assert intent.intent == "open_application"
        assert intent.parameters["app_name"] == "notepad"

    def test_launch_chrome(self, engine):
        intent = engine.parse("launch chrome")
        assert intent is not None
        assert intent.intent == "open_application"
        assert intent.parameters["app_name"] == "chrome"

    def test_close_app(self, engine):
        intent = engine.parse("close notepad")
        assert intent is not None
        assert intent.intent == "close_application"

    def test_quit_app(self, engine):
        intent = engine.parse("quit firefox")
        assert intent is not None
        assert intent.intent == "close_application"


class TestSystemInfoCommands:
    def test_battery(self, engine):
        intent = engine.parse("battery")
        assert intent is not None
        assert intent.intent == "get_battery"

    def test_system_info(self, engine):
        intent = engine.parse("system info")
        assert intent is not None
        assert intent.intent == "get_system_info"

    def test_what_time(self, engine):
        intent = engine.parse("what time is it")
        assert intent is not None
        assert intent.intent == "get_time"

    def test_date(self, engine):
        intent = engine.parse("what's the date")
        assert intent is not None
        assert intent.intent == "get_date"


class TestNetworkCommands:
    def test_wifi_status(self, engine):
        intent = engine.parse("wifi status")
        assert intent is not None
        assert intent.intent == "get_wifi_status"

    def test_enable_wifi(self, engine):
        intent = engine.parse("turn on wifi")
        assert intent is not None
        assert intent.intent == "enable_wifi"

    def test_disable_wifi(self, engine):
        intent = engine.parse("turn off wi-fi")
        assert intent is not None
        assert intent.intent == "disable_wifi"


class TestDesktopCommands:
    def test_type_text(self, engine):
        intent = engine.parse("type hello world")
        assert intent is not None
        assert intent.intent == "type_text"
        assert intent.parameters["text"] == "hello world"

    def test_press_key(self, engine):
        intent = engine.parse("press enter")
        assert intent is not None
        assert intent.intent == "press_key"
        assert intent.parameters["key"] == "enter"

    def test_screenshot(self, engine):
        intent = engine.parse("take a screenshot")
        assert intent is not None
        assert intent.intent == "screenshot"

    def test_lock_screen(self, engine):
        intent = engine.parse("lock the screen")
        assert intent is not None
        assert intent.intent == "lock_screen"


class TestMetaCommands:
    def test_help(self, engine):
        intent = engine.parse("help")
        assert intent is not None
        assert intent.intent == "help"

    def test_emergency_stop(self, engine):
        intent = engine.parse("emergency stop")
        assert intent is not None
        assert intent.intent == "emergency_stop"

    def test_stop_listening(self, engine):
        intent = engine.parse("stop listening")
        assert intent is not None
        assert intent.intent == "stop_listening"

    def test_history(self, engine):
        intent = engine.parse("history")
        assert intent is not None
        assert intent.intent == "history"


class TestEdgeCases:
    def test_empty_string(self, engine):
        assert engine.parse("") is None

    def test_none_input(self, engine):
        assert engine.parse(None) is None

    def test_whitespace_only(self, engine):
        assert engine.parse("   ") is None

    def test_unrecognized_command(self, engine):
        assert engine.parse("make me a sandwich") is None

    def test_source_is_rules(self, engine):
        intent = engine.parse("mute")
        assert intent is not None
        assert intent.source == "rules"

    def test_raw_text_preserved(self, engine):
        intent = engine.parse("set volume to 30")
        assert intent is not None
        assert intent.raw_text == "set volume to 30"
