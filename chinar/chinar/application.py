"""Chinar application orchestrator.

Coordinates the audio capture, STT, pipeline, and TTS subsystems.
Provides the interactive push-to-talk loop and CLI interface.
"""

from __future__ import annotations

import asyncio
import logging
import signal
import sys
from typing import Optional, TYPE_CHECKING

from chinar.audio.capture import AudioCapture
from chinar.audio.stt import STTEngine
from chinar.audio.vad import VoiceActivityDetector
from chinar.pipeline import Pipeline

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.application")


class ChinarApp:
    """Main application class managing the voice assistant lifecycle."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._pipeline = Pipeline(config)
        self._audio = AudioCapture(config)
        self._stt = STTEngine(config)
        self._vad = VoiceActivityDetector(config)
        self._running = False
        self._shutdown_event = asyncio.Event()

    async def initialize(self) -> bool:
        """Initialize all subsystems.

        Returns:
            True if core systems initialized successfully.
        """
        logger.info("Initializing Chinar v0.1.0...")

        # Pipeline (config, db, parser, adapters)
        await self._pipeline.initialize()

        # STT
        stt_ok = await self._stt.load_model()
        if not stt_ok:
            logger.warning(
                "STT model not loaded — voice commands unavailable. "
                "Text input mode will be used instead."
            )

        # Audio
        if not self._audio.is_available:
            logger.warning(
                "Audio capture unavailable — check microphone and sounddevice installation."
            )

        # TTS greeting
        await self._pipeline.tts.speak_if_enabled("Chinar is ready.")

        logger.info("Initialization complete")
        logger.info("  STT: %s", "available" if stt_ok else "unavailable")
        logger.info("  Audio: %s", "available" if self._audio.is_available else "unavailable")
        logger.info("  LLM: %s", "available" if self._pipeline.parser.llm_available else "unavailable")
        logger.info("  TTS: %s", "available" if self._pipeline.tts.is_available else "unavailable")

        return True

    async def run_interactive(self) -> None:
        """Run the interactive command loop.

        Supports two modes:
        1. Voice input (push-to-talk) when STT and audio are available
        2. Text input fallback when they are not
        """
        self._running = True
        print("\n╔══════════════════════════════════════════╗")
        print("║       🌿 Chinar Voice Assistant 🌿       ║")
        print("╠══════════════════════════════════════════╣")
        print("║  Commands:                               ║")
        print("║    Type a command or say it aloud         ║")
        print("║    'r' + Enter = Record voice (5s)        ║")
        print("║    'q' or 'exit' = Quit                   ║")
        print("║    'help' = Show commands                 ║")
        print("╚══════════════════════════════════════════╝\n")

        voice_available = self._stt.is_available and self._audio.is_available

        while self._running:
            try:
                prompt = "🎤 [r=record/type command/q=quit] > " if voice_available else "⌨️  [type command/q=quit] > "
                # Read input in a thread to keep event loop responsive
                loop = asyncio.get_running_loop()
                user_input = await loop.run_in_executor(None, lambda: input(prompt))
                user_input = user_input.strip()

                if not user_input:
                    continue

                if user_input.lower() in ("q", "quit", "exit"):
                    print("Goodbye!")
                    await self._pipeline.tts.speak_if_enabled("Goodbye!")
                    break

                if user_input.lower() == "r" and voice_available:
                    # Push-to-talk: record for 5 seconds
                    await self._record_and_process()
                    continue

                # Text command
                result = await self._pipeline.process_text(user_input)
                self._print_result(result)

                # Check for stop_listening
                if result.intent == "stop_listening":
                    break

            except (EOFError, KeyboardInterrupt):
                print("\nShutting down...")
                break
            except Exception as exc:
                logger.error("Loop error: %s", exc)
                print(f"Error: {exc}")

    async def _record_and_process(self) -> None:
        """Record audio via push-to-talk and process the transcription."""
        print("🔴 Recording... (5 seconds)")
        audio = await self._audio.record_for_duration(5.0)

        if audio is None:
            print("❌ No audio captured.")
            return

        print("⏳ Transcribing...")
        text = await self._stt.transcribe(audio)

        if not text:
            print("❌ Could not transcribe audio.")
            await self._pipeline.tts.speak_if_enabled("I didn't catch that.")
            return

        print(f"📝 Heard: \"{text}\"")
        result = await self._pipeline.process_text(text)
        self._print_result(result)

    def _print_result(self, result) -> None:
        """Print an action result to the console."""
        status_icons = {
            "success": "✅",
            "failure": "❌",
            "denied": "🚫",
            "timeout": "⏰",
            "confirmation_required": "⚠️",
            "unsupported": "❓",
        }
        icon = status_icons.get(result.status.value, "❓")
        print(f"{icon} {result.message}")

    async def run_single_command(self, text: str) -> None:
        """Process a single text command and exit."""
        result = await self._pipeline.process_text(text)
        self._print_result(result)

    async def run_diagnostic(self) -> None:
        """Run diagnostic checks and report status."""
        print("\n🔍 Chinar Diagnostic Report")
        print("=" * 50)

        # Python
        print(f"  Python: {sys.version.split()[0]}")
        print(f"  Platform: {sys.platform}")

        # Config
        print(f"  Config root: {self._config.project_root}")

        # Audio
        devices = self._audio.list_devices()
        print(f"  Audio devices: {len(devices)} input device(s)")
        for d in devices[:3]:
            print(f"    - [{d['index']}] {d['name']}")

        # STT
        print(f"  STT engine: {self._config.stt.engine}")
        print(f"  STT model: {'✅ loaded' if self._stt.is_available else '❌ not found'}")
        print(f"  STT model path: {self._config.stt_model_path}")

        # LLM
        print(f"  LLM provider: {self._config.llm.provider}")
        print(f"  LLM available: {'✅' if self._pipeline.parser.llm_available else '❌ not connected'}")

        # TTS
        print(f"  TTS engine: {self._config.tts.engine}")
        print(f"  TTS available: {'✅' if self._pipeline.tts.is_available else '❌'}")

        # Database
        db_health = await self._pipeline.db.health_check()
        print(f"  Database: {'✅ connected' if db_health.get('connected') else '❌'}")
        print(f"  Database path: {db_health.get('path')}")
        if "history_count" in db_health:
            print(f"  History entries: {db_health['history_count']}")
        if "integrity" in db_health:
            print(f"  DB integrity: {db_health['integrity']}")

        print("=" * 50)

    async def shutdown(self) -> None:
        """Gracefully shut down all subsystems."""
        logger.info("Shutting down Chinar...")
        self._running = False
        self._audio.cleanup()
        self._stt.cleanup()
        await self._pipeline.close()
        logger.info("Shutdown complete")
