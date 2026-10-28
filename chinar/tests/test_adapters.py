"""Tests for system adapters — uses mocks for hardware-dependent operations."""

import pytest
from unittest.mock import patch, MagicMock, AsyncMock

from chinar.schemas import ActionStatus
from chinar.adapters.applications import ApplicationAdapter
from chinar.adapters.desktop import DesktopAdapter
from chinar.adapters.filesystem import FilesystemAdapter
from chinar.adapters.network import NetworkAdapter
from chinar.adapters.bluetooth import BluetoothAdapter
from chinar.adapters.audio_control import AudioControlAdapter


class TestApplicationAdapter:
    @pytest.mark.asyncio
    async def test_open_empty_name(self):
        adapter = ApplicationAdapter()
        result = await adapter.execute("open_application", {"app_name": ""})
        assert result.status == ActionStatus.FAILURE
        assert "no application" in result.message.lower()

    @pytest.mark.asyncio
    async def test_close_empty_name(self):
        adapter = ApplicationAdapter()
        result = await adapter.execute("close_application", {"app_name": ""})
        assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_unknown_action(self):
        adapter = ApplicationAdapter()
        result = await adapter.execute("unknown", {})
        assert result.status == ActionStatus.UNSUPPORTED


class TestDesktopAdapter:
    @pytest.mark.asyncio
    async def test_get_time(self):
        adapter = DesktopAdapter()
        result = await adapter.execute("get_time", {})
        assert result.status == ActionStatus.SUCCESS
        assert "time" in result.message.lower()

    @pytest.mark.asyncio
    async def test_get_date(self):
        adapter = DesktopAdapter()
        result = await adapter.execute("get_date", {})
        assert result.status == ActionStatus.SUCCESS
        assert result.data.get("date") is not None

    @pytest.mark.asyncio
    async def test_help(self):
        adapter = DesktopAdapter()
        result = await adapter.execute("help", {})
        assert result.status == ActionStatus.SUCCESS
        assert "commands" in result.message.lower() or "volume" in result.message.lower()

    @pytest.mark.asyncio
    async def test_type_empty_text(self):
        adapter = DesktopAdapter()
        result = await adapter.execute("type_text", {"text": ""})
        assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_press_disallowed_key(self):
        adapter = DesktopAdapter()
        result = await adapter.execute("press_key", {"key": "win+r"})
        assert result.status == ActionStatus.DENIED

    @pytest.mark.asyncio
    async def test_press_allowed_key_no_pyautogui(self):
        # Mock pyautogui as unavailable
        adapter = DesktopAdapter()
        with patch("chinar.adapters.desktop.pyautogui", None):
            result = await adapter.execute("press_key", {"key": "enter"})
            assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_shell_empty_command(self):
        adapter = DesktopAdapter()
        result = await adapter.execute("execute_shell", {"command": ""})
        assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_unknown_action(self):
        adapter = DesktopAdapter()
        result = await adapter.execute("unknown", {})
        assert result.status == ActionStatus.UNSUPPORTED

    @pytest.mark.asyncio
    async def test_battery_without_psutil(self):
        adapter = DesktopAdapter()
        with patch("chinar.adapters.desktop.psutil", None):
            result = await adapter.execute("get_battery", {})
            assert result.status == ActionStatus.FAILURE


class TestFilesystemAdapter:
    @pytest.mark.asyncio
    async def test_open_file_empty_path(self):
        adapter = FilesystemAdapter()
        result = await adapter.execute("open_file", {"path": ""})
        assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_open_nonexistent_file(self):
        adapter = FilesystemAdapter()
        result = await adapter.execute("open_file", {"path": "C:\\nonexistent_xyz_123.txt"})
        assert result.status == ActionStatus.FAILURE
        assert "not found" in result.message.lower()

    @pytest.mark.asyncio
    async def test_open_directory_empty(self):
        adapter = FilesystemAdapter()
        result = await adapter.execute("open_directory", {"path": ""})
        assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_search_empty_query(self):
        adapter = FilesystemAdapter()
        result = await adapter.execute("search_files", {"query": ""})
        assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_unknown_action(self):
        adapter = FilesystemAdapter()
        result = await adapter.execute("unknown", {})
        assert result.status == ActionStatus.UNSUPPORTED


class TestNetworkAdapter:
    @pytest.mark.asyncio
    async def test_unknown_action(self):
        adapter = NetworkAdapter()
        result = await adapter.execute("unknown", {})
        assert result.status == ActionStatus.UNSUPPORTED


class TestBluetoothAdapter:
    @pytest.mark.asyncio
    async def test_unknown_action(self):
        adapter = BluetoothAdapter()
        result = await adapter.execute("unknown", {})
        assert result.status == ActionStatus.UNSUPPORTED


class TestAudioControlAdapter:
    @pytest.mark.asyncio
    async def test_unknown_action(self):
        adapter = AudioControlAdapter()
        result = await adapter.execute("unknown", {})
        assert result.status == ActionStatus.UNSUPPORTED


class TestSchemaValidation:
    """Test intent schema validation."""

    def test_valid_intent(self):
        from chinar.schemas import validate_intent
        result = validate_intent({
            "intent": "set_volume",
            "parameters": {"level": 50},
        })
        assert hasattr(result, "intent")
        assert result.intent == "set_volume"

    def test_invalid_intent_name(self):
        from chinar.schemas import validate_intent
        result = validate_intent({"intent": "destroy_everything"})
        assert isinstance(result, str)
        assert "unknown" in result.lower()

    def test_missing_required_param(self):
        from chinar.schemas import validate_intent
        result = validate_intent({
            "intent": "set_volume",
            "parameters": {},
        })
        assert isinstance(result, str)
        assert "missing" in result.lower()

    def test_wrong_param_type(self):
        from chinar.schemas import validate_intent
        result = validate_intent({
            "intent": "set_volume",
            "parameters": {"level": "loud"},
        })
        assert isinstance(result, str)

    def test_out_of_range_param(self):
        from chinar.schemas import validate_intent
        result = validate_intent({
            "intent": "set_volume",
            "parameters": {"level": 200},
        })
        assert isinstance(result, str)
        assert "between" in result.lower()

    def test_unexpected_param(self):
        from chinar.schemas import validate_intent
        result = validate_intent({
            "intent": "get_volume",
            "parameters": {"extra": True},
        })
        assert isinstance(result, str)
        assert "unexpected" in result.lower()

    def test_parse_llm_invalid_json(self):
        from chinar.schemas import parse_llm_response
        result = parse_llm_response("not json at all")
        assert isinstance(result, str)
        assert "invalid json" in result.lower()

    def test_parse_llm_valid_json(self):
        from chinar.schemas import parse_llm_response
        result = parse_llm_response('{"intent": "get_time", "parameters": {}}')
        assert hasattr(result, "intent")
        assert result.intent == "get_time"
        assert result.source == "llm"

    def test_non_dict_input(self):
        from chinar.schemas import validate_intent
        result = validate_intent("not a dict")
        assert isinstance(result, str)

    def test_missing_intent_field(self):
        from chinar.schemas import validate_intent
        result = validate_intent({"parameters": {}})
        assert isinstance(result, str)
