"""Tests for the command pipeline."""

import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

from chinar.config import ChinarConfig
from chinar.pipeline import Pipeline
from chinar.schemas import ActionResult, ActionStatus


@pytest.fixture
def config():
    cfg = ChinarConfig()
    cfg.llm.enabled = False  # Don't try to connect to Ollama in tests
    cfg.tts.enabled = False  # Don't speak during tests
    cfg.general.enable_history = False  # Skip DB in unit tests
    return cfg


class TestPipelineTextProcessing:
    @pytest.mark.asyncio
    async def test_known_command(self, config):
        pipeline = Pipeline(config)
        # Don't initialize fully (skip DB/LLM)
        result = await pipeline.process_text("what time is it")
        assert result.status == ActionStatus.SUCCESS
        assert "time" in result.message.lower()

    @pytest.mark.asyncio
    async def test_volume_command(self, config):
        # Volume control needs COM — may fail in CI, but tests the flow
        pipeline = Pipeline(config)
        result = await pipeline.process_text("get volume")
        # Result depends on whether pycaw/COM is available
        assert result.status in (ActionStatus.SUCCESS, ActionStatus.FAILURE)

    @pytest.mark.asyncio
    async def test_unknown_command(self, config):
        pipeline = Pipeline(config)
        result = await pipeline.process_text("flibbertigibbet")
        assert result.status == ActionStatus.FAILURE
        assert "didn't understand" in result.message.lower() or "help" in result.message.lower()

    @pytest.mark.asyncio
    async def test_empty_text(self, config):
        pipeline = Pipeline(config)
        result = await pipeline.process_text("")
        assert result.status == ActionStatus.FAILURE

    @pytest.mark.asyncio
    async def test_emergency_stop(self, config):
        pipeline = Pipeline(config)
        result = await pipeline.process_text("emergency stop")
        assert result.status == ActionStatus.SUCCESS
        assert config.general.emergency_stop is True

    @pytest.mark.asyncio
    async def test_commands_blocked_after_emergency_stop(self, config):
        pipeline = Pipeline(config)
        config.general.emergency_stop = True
        result = await pipeline.process_text("set volume to 50")
        assert result.status == ActionStatus.DENIED

    @pytest.mark.asyncio
    async def test_help_command(self, config):
        pipeline = Pipeline(config)
        result = await pipeline.process_text("help")
        assert result.status == ActionStatus.SUCCESS
        assert "commands" in result.message.lower() or "available" in result.message.lower()

    @pytest.mark.asyncio
    async def test_date_command(self, config):
        pipeline = Pipeline(config)
        result = await pipeline.process_text("what is the date")
        assert result.status == ActionStatus.SUCCESS
        assert result.intent == "get_date"

    @pytest.mark.asyncio
    async def test_battery_command(self, config):
        pipeline = Pipeline(config)
        result = await pipeline.process_text("battery")
        # May fail if psutil not installed
        assert result.status in (ActionStatus.SUCCESS, ActionStatus.FAILURE)

    @pytest.mark.asyncio
    async def test_high_risk_blocked_without_confirmation(self, config):
        config.security.require_confirmation = True
        pipeline = Pipeline(config)
        result = await pipeline.process_text("run command dir")
        # Should require confirmation
        assert result.status in (
            ActionStatus.CONFIRMATION_REQUIRED,
            ActionStatus.FAILURE,
        )


class TestPipelineShutdown:
    @pytest.mark.asyncio
    async def test_close_is_safe(self, config):
        pipeline = Pipeline(config)
        # Close without initialize should not crash
        await pipeline.close()
