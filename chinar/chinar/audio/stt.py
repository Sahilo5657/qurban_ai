"""Speech-to-Text engine abstraction with Vosk backend.

Provides async transcription from PCM audio arrays using a locally
hosted Vosk model. The model is loaded once and reused.
"""

from __future__ import annotations

import asyncio
import json
import logging
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Optional, TYPE_CHECKING

import numpy as np

try:
    from vosk import Model as VoskModel, KaldiRecognizer
except ImportError:
    VoskModel = None  # type: ignore[assignment, misc]
    KaldiRecognizer = None  # type: ignore[assignment, misc]

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.audio.stt")


class STTEngine:
    """Local speech-to-text using Vosk."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._model: Optional[object] = None
        self._sample_rate = config.audio.sample_rate
        self._model_path = config.stt_model_path
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="stt")
        self._loaded = False

    @property
    def is_available(self) -> bool:
        """Check if the STT engine can be used."""
        if VoskModel is None:
            return False
        return self._model_path.exists() and self._model_path.is_dir()

    async def load_model(self) -> bool:
        """Load the Vosk model. This is CPU-intensive so runs in a thread.

        Returns:
            True if the model loaded successfully.
        """
        if VoskModel is None:
            logger.error("vosk package is not installed — STT unavailable")
            return False

        if not self._model_path.exists():
            logger.error("STT model not found at: %s", self._model_path)
            logger.info(
                "Download a Vosk model from https://alphacephei.com/vosk/models "
                "and extract it to: %s",
                self._model_path,
            )
            return False

        try:
            loop = asyncio.get_running_loop()
            self._model = await loop.run_in_executor(
                self._executor,
                lambda: VoskModel(str(self._model_path)),
            )
            self._loaded = True
            logger.info("Vosk model loaded from %s", self._model_path)
            return True
        except Exception as exc:
            logger.error("Failed to load Vosk model: %s", exc)
            return False

    async def transcribe(self, audio: np.ndarray) -> Optional[str]:
        """Transcribe audio data to text.

        Args:
            audio: PCM int16 mono audio as a numpy array.

        Returns:
            Transcribed text, or None if transcription failed.
        """
        if not self._loaded or self._model is None:
            logger.warning("STT model not loaded — cannot transcribe")
            return None

        if audio is None or len(audio) == 0:
            return None

        loop = asyncio.get_running_loop()
        try:
            result = await asyncio.wait_for(
                loop.run_in_executor(self._executor, self._do_transcribe, audio),
                timeout=30.0,
            )
            return result
        except asyncio.TimeoutError:
            logger.warning("STT transcription timed out")
            return None
        except Exception as exc:
            logger.error("STT transcription error: %s", exc)
            return None

    def _do_transcribe(self, audio: np.ndarray) -> Optional[str]:
        """Synchronous transcription (runs in thread pool)."""
        recognizer = KaldiRecognizer(self._model, self._sample_rate)

        # Convert to bytes
        audio_bytes = audio.tobytes()

        # Feed in chunks
        chunk_size = 4000
        for i in range(0, len(audio_bytes), chunk_size):
            chunk = audio_bytes[i : i + chunk_size]
            recognizer.AcceptWaveform(chunk)

        # Get final result
        result_json = recognizer.FinalResult()
        try:
            result = json.loads(result_json)
            text = result.get("text", "").strip()
            if text:
                logger.info("Transcribed: '%s'", text)
                return text
            else:
                logger.debug("Empty transcription result")
                return None
        except json.JSONDecodeError:
            logger.warning("Invalid JSON from Vosk: %s", result_json)
            return None

    def cleanup(self) -> None:
        """Release STT resources."""
        self._executor.shutdown(wait=False)
        self._model = None
        self._loaded = False
