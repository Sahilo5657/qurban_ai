"""Local LLM client for Ollama-based intent parsing.

Sends transcribed text to a locally running Ollama instance and
parses the structured JSON response into a validated CommandIntent.
The LLM is only used when deterministic rules fail to match.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Optional, TYPE_CHECKING

try:
    import httpx
except ImportError:
    httpx = None  # type: ignore[assignment]

from chinar.schemas import CommandIntent, KNOWN_INTENTS, parse_llm_response

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.reasoning.llm")

# System prompt instructing the model to return structured JSON
SYSTEM_PROMPT = """You are Chinar, a local voice assistant. Your task is to interpret
user commands and return a structured JSON object.

You MUST respond with ONLY a valid JSON object in this exact format:
{
    "intent": "<intent_name>",
    "parameters": {<key>: <value>},
    "requires_confirmation": false
}

Available intents and their parameters:
- open_application: {"app_name": "<name>"}
- close_application: {"app_name": "<name>"}
- set_volume: {"level": <0-100>}
- get_volume: {}
- mute_audio: {}
- unmute_audio: {}
- media_play_pause: {}
- media_next: {}
- media_previous: {}
- open_file: {"path": "<file_path>"}
- open_directory: {"path": "<directory_path>"}
- search_files: {"query": "<search_term>"}
- get_battery: {}
- get_system_info: {}
- get_time: {}
- get_date: {}
- get_wifi_status: {}
- enable_wifi: {}
- disable_wifi: {}
- type_text: {"text": "<text_to_type>"}
- press_key: {"key": "<key_name>"}
- screenshot: {}
- lock_screen: {}
- execute_shell: {"command": "<shell_command>"} (set requires_confirmation: true)
- get_bluetooth_status: {}
- help: {}
- history: {}

If you cannot determine the intent, respond with:
{"intent": "help", "parameters": {}, "requires_confirmation": false}

IMPORTANT: Return ONLY the JSON object. No explanations, no markdown, no extra text."""


class LLMClient:
    """Client for local Ollama inference."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._endpoint = config.llm.endpoint.rstrip("/")
        self._model = config.llm.model
        self._timeout = config.llm.request_timeout_seconds
        self._enabled = config.llm.enabled and config.llm.provider == "ollama"
        self._available = False
        self._client: Optional[httpx.AsyncClient] = None

    @property
    def is_enabled(self) -> bool:
        return self._enabled

    @property
    def is_available(self) -> bool:
        return self._available

    async def initialize(self) -> bool:
        """Check Ollama availability and auto-detect model if needed.

        Returns:
            True if Ollama is reachable and a model is available.
        """
        if not self._enabled:
            logger.info("LLM is disabled in configuration")
            return False

        if httpx is None:
            logger.warning("httpx not installed — LLM client unavailable (pip install httpx)")
            self._enabled = False
            return False

        self._client = httpx.AsyncClient(timeout=self._timeout)

        try:
            # Check if Ollama is running
            resp = await self._client.get(f"{self._endpoint}/api/tags")
            if resp.status_code != 200:
                logger.warning("Ollama returned status %d", resp.status_code)
                return False

            data = resp.json()
            models = data.get("models", [])

            if not models:
                logger.warning("No models installed in Ollama")
                return False

            # Auto-detect model if not configured
            if not self._model:
                # Prefer small models suitable for command parsing
                preferred = ["phi3:mini", "phi3", "llama3.2:1b", "llama3.2", "tinyllama", "gemma2:2b"]
                model_names = [m.get("name", "") for m in models]
                for pref in preferred:
                    if pref in model_names:
                        self._model = pref
                        break
                if not self._model:
                    self._model = model_names[0]

                logger.info("Auto-selected model: %s", self._model)

            self._available = True
            logger.info(
                "LLM initialized: endpoint=%s, model=%s", self._endpoint, self._model
            )
            return True

        except httpx.ConnectError:
            logger.info("Ollama is not running at %s — LLM features disabled", self._endpoint)
            return False
        except Exception as exc:
            logger.warning("Failed to initialize LLM: %s", exc)
            return False

    async def interpret(self, text: str) -> Optional[CommandIntent]:
        """Send text to the LLM and parse the structured response.

        Args:
            text: Transcribed user speech to interpret.

        Returns:
            A validated CommandIntent, or None on failure.
        """
        if not self._available or not self._client:
            return None

        try:
            payload = {
                "model": self._model,
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": text},
                ],
                "stream": False,
                "format": "json",
                "options": {
                    "temperature": 0.1,
                    "num_predict": 200,
                },
            }

            resp = await asyncio.wait_for(
                self._client.post(f"{self._endpoint}/api/chat", json=payload),
                timeout=self._timeout,
            )

            if resp.status_code != 200:
                logger.warning("Ollama returned status %d: %s", resp.status_code, resp.text[:200])
                return None

            data = resp.json()
            content = data.get("message", {}).get("content", "")

            if not content:
                logger.warning("Empty response from LLM")
                return None

            # Try to extract JSON from response
            result = parse_llm_response(content)
            if isinstance(result, str):
                logger.warning("LLM response validation failed: %s", result)
                return None

            logger.info("LLM interpreted: '%s' → %s", text, result.intent)
            return result

        except asyncio.TimeoutError:
            logger.warning("LLM request timed out after %ds", self._timeout)
            return None
        except Exception as exc:
            logger.warning("LLM interpretation error: %s", exc)
            return None

    async def close(self) -> None:
        """Close the HTTP client."""
        if self._client:
            await self._client.aclose()
            self._client = None
