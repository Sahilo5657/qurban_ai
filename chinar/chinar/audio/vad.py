"""Voice Activity Detection (VAD) using energy-based thresholding.

A simple, CPU-efficient VAD that detects whether an audio chunk
contains speech based on RMS energy. This avoids sending silence
to the STT engine.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.audio.vad")


class VoiceActivityDetector:
    """Energy-based voice activity detection."""

    def __init__(self, config: ChinarConfig) -> None:
        self._enabled = config.audio.voice_activity_detection
        self._threshold = config.audio.vad_energy_threshold
        # Auto-calibration state
        self._noise_floor: float = 0.0
        self._calibrated = False
        self._calibration_frames = 0
        self._calibration_target = 30  # frames to use for calibration

    @property
    def is_enabled(self) -> bool:
        return self._enabled

    def is_speech(self, audio_chunk: np.ndarray) -> bool:
        """Determine if an audio chunk likely contains speech.

        Args:
            audio_chunk: Audio data as int16 numpy array.

        Returns:
            True if the chunk appears to contain speech.
        """
        if not self._enabled:
            return True  # If VAD is disabled, treat everything as speech

        if audio_chunk is None or len(audio_chunk) == 0:
            return False

        # Normalize to float in [-1, 1]
        audio_float = audio_chunk.astype(np.float32) / 32768.0
        rms = float(np.sqrt(np.mean(audio_float ** 2)))

        # Auto-calibrate noise floor from initial silent frames
        if not self._calibrated:
            self._noise_floor = (
                (self._noise_floor * self._calibration_frames + rms)
                / (self._calibration_frames + 1)
            )
            self._calibration_frames += 1
            if self._calibration_frames >= self._calibration_target:
                self._calibrated = True
                # Set threshold slightly above noise floor
                adaptive_threshold = max(self._threshold, self._noise_floor * 2.5)
                self._threshold = adaptive_threshold
                logger.debug(
                    "VAD calibrated: noise_floor=%.5f, threshold=%.5f",
                    self._noise_floor,
                    self._threshold,
                )
            return False  # Don't detect speech during calibration

        return rms > self._threshold

    def reset_calibration(self) -> None:
        """Reset the noise floor calibration."""
        self._noise_floor = 0.0
        self._calibrated = False
        self._calibration_frames = 0

    def get_stats(self) -> dict[str, float]:
        """Return current VAD statistics."""
        return {
            "threshold": self._threshold,
            "noise_floor": self._noise_floor,
            "calibrated": float(self._calibrated),
        }
