"""Unified intent parser — combines rule engine and LLM fallback.

Tries deterministic rules first, falls back to LLM if configured,
and provides a clear "unknown" response if neither can parse the input.
"""

from __future__ import annotations

import logging
from typing import Optional, TYPE_CHECKING

from chinar.reasoning.rules import RuleEngine
from chinar.reasoning.llm import LLMClient
from chinar.schemas import CommandIntent

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.reasoning.parser")


class IntentParser:
    """Two-tier intent parsing: deterministic rules → optional LLM."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._rules = RuleEngine()
        self._llm = LLMClient(config)

    @property
    def llm_available(self) -> bool:
        return self._llm.is_available

    async def initialize(self) -> None:
        """Initialize the parser and check LLM availability."""
        if self._config.llm.enabled:
            await self._llm.initialize()

    async def parse(self, text: str) -> Optional[CommandIntent]:
        """Parse transcribed text into a structured CommandIntent.

        Strategy:
        1. Try deterministic rules first (fast, no external dependency).
        2. If rules don't match and LLM is available, try LLM.
        3. If both fail, return None.

        Args:
            text: The transcribed speech text.

        Returns:
            A validated CommandIntent, or None if no interpretation found.
        """
        if not text or not text.strip():
            return None

        # Step 1: Deterministic rules
        intent = self._rules.parse(text)
        if intent is not None:
            logger.info("Parsed by rules: '%s' → %s", text, intent.intent)
            return intent

        # Step 2: LLM fallback
        if self._llm.is_available:
            logger.debug("Rules didn't match — trying LLM for: '%s'", text)
            intent = await self._llm.interpret(text)
            if intent is not None:
                logger.info("Parsed by LLM: '%s' → %s", text, intent.intent)
                return intent

        logger.info("Could not parse: '%s'", text)
        return None

    async def close(self) -> None:
        """Clean up resources."""
        await self._llm.close()
