"""Command execution pipeline.

Orchestrates the flow from transcribed text through intent parsing,
policy authorization, adapter execution, feedback, and history recording.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Optional, TYPE_CHECKING

from chinar.schemas import ActionResult, ActionStatus, CommandIntent, validate_intent
from chinar.policy import PolicyEngine
from chinar.reasoning.parser import IntentParser
from chinar.database import Database
from chinar.speech.tts import TTSEngine

from chinar.adapters.applications import ApplicationAdapter
from chinar.adapters.audio_control import AudioControlAdapter
from chinar.adapters.network import NetworkAdapter
from chinar.adapters.bluetooth import BluetoothAdapter
from chinar.adapters.filesystem import FilesystemAdapter
from chinar.adapters.desktop import DesktopAdapter

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.pipeline")


# Map intents to their adapter
INTENT_ADAPTER_MAP: dict[str, str] = {
    "open_application": "app",
    "close_application": "app",
    "set_volume": "audio",
    "get_volume": "audio",
    "mute_audio": "audio",
    "unmute_audio": "audio",
    "media_play_pause": "audio",
    "media_next": "audio",
    "media_previous": "audio",
    "open_file": "fs",
    "open_directory": "fs",
    "search_files": "fs",
    "get_wifi_status": "net",
    "enable_wifi": "net",
    "disable_wifi": "net",
    "get_bluetooth_status": "bt",
    "type_text": "desktop",
    "press_key": "desktop",
    "screenshot": "desktop",
    "lock_screen": "desktop",
    "get_battery": "desktop",
    "get_system_info": "desktop",
    "get_time": "desktop",
    "get_date": "desktop",
    "execute_shell": "desktop",
    "help": "desktop",
    "history": "desktop",
}


class Pipeline:
    """End-to-end command execution pipeline."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._parser = IntentParser(config)
        self._policy = PolicyEngine(config)
        self._db = Database(config)
        self._tts = TTSEngine(config)

        # Adapters
        self._adapters: dict[str, Any] = {
            "app": ApplicationAdapter(),
            "audio": AudioControlAdapter(),
            "net": NetworkAdapter(),
            "bt": BluetoothAdapter(),
            "fs": FilesystemAdapter(),
            "desktop": DesktopAdapter(),
        }

    @property
    def parser(self) -> IntentParser:
        return self._parser

    @property
    def tts(self) -> TTSEngine:
        return self._tts

    @property
    def db(self) -> Database:
        return self._db

    async def initialize(self) -> None:
        """Initialize all pipeline components."""
        await self._db.initialize()
        await self._parser.initialize()
        await self._db.record_event("pipeline_started")
        logger.info("Pipeline initialized")

    async def process_text(self, text: str) -> ActionResult:
        """Process transcribed text through the full pipeline.

        Args:
            text: Transcribed speech text.

        Returns:
            The action result after processing.
        """
        start_time = time.monotonic()

        # Step 1: Parse intent
        intent = await self._parser.parse(text)
        if intent is None:
            result = ActionResult(
                status=ActionStatus.FAILURE,
                message="I didn't understand that. Say 'help' for available commands.",
                intent="unknown",
            )
            await self._tts.speak_if_enabled(result.message)
            return result

        # Handle emergency stop
        if intent.intent == "emergency_stop":
            self._config.general.emergency_stop = True
            result = ActionResult(
                status=ActionStatus.SUCCESS,
                message="Emergency stop activated. All automation disabled.",
                intent="emergency_stop",
            )
            await self._tts.speak_if_enabled(result.message)
            await self._db.record_event("emergency_stop", "User activated emergency stop")
            return result

        # Handle stop_listening (internal control)
        if intent.intent == "stop_listening":
            result = ActionResult(
                status=ActionStatus.SUCCESS,
                message="Stopping voice input.",
                intent="stop_listening",
            )
            await self._tts.speak_if_enabled(result.message)
            return result

        # Step 2: Policy check
        policy_result = self._policy.authorize(intent)
        if policy_result is not None:
            await self._record_execution(intent, policy_result, start_time)
            if policy_result.status == ActionStatus.CONFIRMATION_REQUIRED:
                await self._tts.speak_if_enabled(
                    f"This action requires confirmation: {intent.intent}"
                )
            else:
                await self._tts.speak_if_enabled(policy_result.message)
            return policy_result

        # Step 3: Execute via adapter
        result = await self._execute_intent(intent)

        # Step 4: Record and speak result
        await self._record_execution(intent, result, start_time)

        if result.is_success:
            await self._tts.speak_if_enabled(result.message)
        else:
            await self._tts.speak_if_enabled(f"Error: {result.message}")

        elapsed = time.monotonic() - start_time
        logger.info(
            "Pipeline completed: %s → %s (%.2fs)",
            intent.intent, result.status.value, elapsed,
        )

        return result

    async def _execute_intent(self, intent: CommandIntent) -> ActionResult:
        """Route an intent to the appropriate adapter."""
        adapter_key = INTENT_ADAPTER_MAP.get(intent.intent)
        if adapter_key is None:
            return ActionResult(
                status=ActionStatus.UNSUPPORTED,
                message=f"No adapter for intent '{intent.intent}'.",
                intent=intent.intent,
            )

        adapter = self._adapters.get(adapter_key)
        if adapter is None:
            return ActionResult(
                status=ActionStatus.UNSUPPORTED,
                message=f"Adapter '{adapter_key}' not available.",
                intent=intent.intent,
            )

        # Handle history specially (needs DB access)
        if intent.intent == "history":
            return await self._handle_history(intent)

        try:
            return await adapter.execute(intent.intent, intent.parameters)
        except Exception as exc:
            logger.error("Adapter execution error for %s: %s", intent.intent, exc)
            return ActionResult(
                status=ActionStatus.FAILURE,
                message=f"Execution error: {exc}",
                intent=intent.intent,
            )

    async def _handle_history(self, intent: CommandIntent) -> ActionResult:
        """Retrieve and format command history."""
        count = intent.parameters.get("count", 10)
        history = await self._db.get_history(count=count)
        if not history:
            return ActionResult(
                status=ActionStatus.SUCCESS,
                message="No command history.",
                intent="history",
            )

        lines = []
        for entry in history[:10]:  # Limit spoken output
            lines.append(f"  {entry.get('intent', '?')} — {entry.get('status', '?')}")

        msg = f"Recent commands ({len(history)}):\n" + "\n".join(lines)
        return ActionResult(
            status=ActionStatus.SUCCESS,
            message=msg,
            data={"entries": history},
            intent="history",
        )

    async def _record_execution(
        self,
        intent: CommandIntent,
        result: ActionResult,
        start_time: float,
    ) -> None:
        """Record command execution to the database."""
        try:
            await self._db.record_command(
                raw_text=intent.raw_text,
                intent=intent.intent,
                parameters=json.dumps(intent.parameters),
                source=intent.source,
                status=result.status.value,
                result_message=result.message[:500],
            )
        except Exception as exc:
            logger.warning("Failed to record command: %s", exc)

    async def close(self) -> None:
        """Shut down the pipeline and release resources."""
        logger.info("Shutting down pipeline")
        await self._db.record_event("pipeline_stopped")
        await self._parser.close()
        await self._db.close()
        self._tts.cleanup()
