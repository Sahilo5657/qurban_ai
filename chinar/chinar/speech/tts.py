"""Text-to-Speech using pyttsx3 (Windows SAPI5 backend).

Provides spoken feedback to the user. Runs speech synthesis
in a thread to avoid blocking the async event loop.
Prevents overlapping speech output.
"""

from __future__ import annotations

import asyncio
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Optional, TYPE_CHECKING

try:
    import pyttsx3
except ImportError:
    pyttsx3 = None  # type: ignore[assignment]

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.speech.tts")


class TTSEngine:
    """Local text-to-speech using Windows SAPI5 via pyttsx3."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._enabled = config.tts.enabled and not config.tts.muted
        self._rate = config.tts.rate
        self._volume = config.tts.volume
        self._voice_id = config.tts.voice_id
        self._engine: Optional[pyttsx3.Engine] = None
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="tts")
        self._lock = threading.Lock()
        self._initialized = False

    @property
    def is_available(self) -> bool:
        return pyttsx3 is not None and self._enabled

    def _init_engine(self) -> bool:
        """Initialize the TTS engine (must be called from the worker thread)."""
        if self._initialized:
            return True
        if pyttsx3 is None:
            return False

        try:
            self._engine = pyttsx3.init()
            self._engine.setProperty("rate", self._rate)
            self._engine.setProperty("volume", self._volume)

            if self._voice_id:
                self._engine.setProperty("voice", self._voice_id)

            self._initialized = True
            logger.info("TTS engine initialized (rate=%d, volume=%.1f)", self._rate, self._volume)
            return True
        except Exception as exc:
            logger.warning("Failed to initialize TTS: %s", exc)
            return False

    def _speak_sync(self, text: str) -> None:
        """Speak text synchronously (runs in thread pool)."""
        with self._lock:
            if not self._initialized:
                if not self._init_engine():
                    return
            try:
                self._engine.say(text)
                self._engine.runAndWait()
            except Exception as exc:
                logger.warning("TTS speech error: %s", exc)
                # Try to reinitialize
                self._initialized = False
                self._engine = None

    async def speak(self, text: str) -> None:
        """Speak text asynchronously (non-blocking).

        Args:
            text: The text to speak.
        """
        if not self._enabled or not text:
            return

        if pyttsx3 is None:
            logger.debug("TTS unavailable — skipping: %s", text[:80])
            return

        # Sanitize: don't speak file paths or overly long text
        if len(text) > 300:
            text = text[:300] + "..."

        loop = asyncio.get_running_loop()
        try:
            await asyncio.wait_for(
                loop.run_in_executor(self._executor, self._speak_sync, text),
                timeout=15.0,
            )
        except asyncio.TimeoutError:
            logger.warning("TTS timed out speaking: %s", text[:50])
        except Exception as exc:
            logger.warning("TTS error: %s", exc)

    async def speak_if_enabled(self, text: str) -> None:
        """Speak only if TTS is not muted."""
        if not self._config.tts.muted:
            await self.speak(text)

    def set_muted(self, muted: bool) -> None:
        """Set the mute state."""
        self._enabled = not muted

    def cleanup(self) -> None:
        """Release TTS resources."""
        self._executor.shutdown(wait=False)
        with self._lock:
            if self._engine:
                try:
                    self._engine.stop()
                except Exception:
                    pass
                self._engine = None
            self._initialized = False

    def list_voices(self) -> list[dict[str, str]]:
        """List available TTS voices."""
        if pyttsx3 is None:
            return []
        try:
            if not self._initialized:
                self._init_engine()
            if self._engine:
                voices = self._engine.getProperty("voices")
                return [
                    {"id": v.id, "name": v.name, "languages": str(v.languages)}
                    for v in voices
                ]
        except Exception:
            pass
        return []
