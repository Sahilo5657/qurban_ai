"""Audio capture using sounddevice with push-to-talk support.

Captures mono 16-bit PCM audio from the system microphone.
Supports configurable device selection, bounded buffering,
and graceful disconnect/reconnect handling.
"""

from __future__ import annotations

import asyncio
import logging
import queue
import threading
from typing import Optional, TYPE_CHECKING

import numpy as np

try:
    import sounddevice as sd
except ImportError:
    sd = None  # type: ignore[assignment]

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.audio.capture")

# Maximum frames to buffer before dropping (prevent memory growth)
MAX_BUFFER_FRAMES = 500


class AudioCapture:
    """Manages microphone audio capture with push-to-talk."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._sample_rate = config.audio.sample_rate
        self._channels = config.audio.channels
        self._chunk_ms = config.audio.chunk_ms
        self._device = config.audio.input_device
        self._max_seconds = config.audio.max_recording_seconds

        self._stream: Optional[sd.InputStream] = None
        self._audio_queue: queue.Queue[np.ndarray] = queue.Queue(maxsize=MAX_BUFFER_FRAMES)
        self._is_recording = False
        self._lock = threading.Lock()

    @property
    def is_available(self) -> bool:
        """Check if audio capture is available."""
        if sd is None:
            return False
        try:
            devices = sd.query_devices()
            return len(devices) > 0
        except Exception:
            return False

    @property
    def is_recording(self) -> bool:
        return self._is_recording

    def list_devices(self) -> list[dict]:
        """List available audio input devices."""
        if sd is None:
            return []
        try:
            devices = sd.query_devices()
            result = []
            for i, dev in enumerate(devices):
                if dev.get("max_input_channels", 0) > 0:
                    result.append({
                        "index": i,
                        "name": dev["name"],
                        "channels": dev["max_input_channels"],
                        "sample_rate": dev["default_samplerate"],
                    })
            return result
        except Exception as exc:
            logger.warning("Failed to list audio devices: %s", exc)
            return []

    def _audio_callback(
        self,
        indata: np.ndarray,
        frames: int,
        time_info: object,
        status: sd.CallbackFlags,
    ) -> None:
        """Called by sounddevice for each audio chunk."""
        if status:
            logger.debug("Audio status: %s", status)
        if self._is_recording:
            try:
                self._audio_queue.put_nowait(indata.copy())
            except queue.Full:
                logger.warning("Audio buffer full — dropping frame")

    async def start_recording(self) -> bool:
        """Start capturing audio from the microphone.

        Returns:
            True if recording started successfully.
        """
        if sd is None:
            logger.error("sounddevice is not installed — audio capture unavailable")
            return False

        with self._lock:
            if self._is_recording:
                logger.debug("Already recording")
                return True

            # Clear any stale data
            while not self._audio_queue.empty():
                try:
                    self._audio_queue.get_nowait()
                except queue.Empty:
                    break

            try:
                block_size = int(self._sample_rate * self._chunk_ms / 1000)
                self._stream = sd.InputStream(
                    samplerate=self._sample_rate,
                    channels=self._channels,
                    dtype="int16",
                    blocksize=block_size,
                    device=self._device,
                    callback=self._audio_callback,
                )
                self._stream.start()
                self._is_recording = True
                logger.info("Recording started (device=%s, rate=%d)", self._device, self._sample_rate)
                return True
            except Exception as exc:
                logger.error("Failed to start recording: %s", exc)
                self._is_recording = False
                return False

    async def stop_recording(self) -> Optional[np.ndarray]:
        """Stop recording and return the captured audio buffer.

        Returns:
            A numpy array of int16 PCM audio, or None if nothing was captured.
        """
        with self._lock:
            self._is_recording = False

            if self._stream is not None:
                try:
                    self._stream.stop()
                    self._stream.close()
                except Exception as exc:
                    logger.warning("Error stopping audio stream: %s", exc)
                finally:
                    self._stream = None

        # Collect all buffered audio
        frames: list[np.ndarray] = []
        while not self._audio_queue.empty():
            try:
                frames.append(self._audio_queue.get_nowait())
            except queue.Empty:
                break

        if not frames:
            logger.debug("No audio captured")
            return None

        audio = np.concatenate(frames, axis=0)
        duration = len(audio) / self._sample_rate
        logger.info("Captured %.2f seconds of audio (%d samples)", duration, len(audio))

        # Safety: trim to max duration
        max_samples = self._max_seconds * self._sample_rate
        if len(audio) > max_samples:
            audio = audio[:max_samples]
            logger.warning("Audio trimmed to %d seconds", self._max_seconds)

        return audio

    async def record_for_duration(self, seconds: float) -> Optional[np.ndarray]:
        """Record for a fixed duration (convenience for push-to-talk).

        Args:
            seconds: Duration to record.

        Returns:
            Captured audio array, or None on failure.
        """
        clamped = min(seconds, self._max_seconds)
        if not await self.start_recording():
            return None
        await asyncio.sleep(clamped)
        return await self.stop_recording()

    def cleanup(self) -> None:
        """Release audio resources."""
        with self._lock:
            self._is_recording = False
            if self._stream is not None:
                try:
                    self._stream.stop()
                    self._stream.close()
                except Exception:
                    pass
                self._stream = None
