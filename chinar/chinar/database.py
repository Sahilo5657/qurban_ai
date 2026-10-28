"""SQLite database for command history and application state.

Uses aiosqlite for async access. Provides schema versioning,
bounded history retention, and parameterized queries.
"""

from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import Any, Optional, TYPE_CHECKING

try:
    import aiosqlite
except ImportError:
    aiosqlite = None  # type: ignore[assignment]

if TYPE_CHECKING:
    from chinar.config import ChinarConfig

logger = logging.getLogger("chinar.database")

SCHEMA_VERSION = 1

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS command_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp REAL NOT NULL,
    raw_text TEXT NOT NULL DEFAULT '',
    intent TEXT NOT NULL,
    parameters TEXT NOT NULL DEFAULT '{}',
    source TEXT NOT NULL DEFAULT 'rules',
    status TEXT NOT NULL,
    result_message TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS system_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp REAL NOT NULL,
    event_type TEXT NOT NULL,
    details TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_history_timestamp ON command_history(timestamp);
CREATE INDEX IF NOT EXISTS idx_history_intent ON command_history(intent);
CREATE INDEX IF NOT EXISTS idx_events_type ON system_events(event_type);
"""


class Database:
    """Async SQLite database manager."""

    def __init__(self, config: ChinarConfig) -> None:
        self._config = config
        self._db_path = config.db_path
        self._db: Optional[aiosqlite.Connection] = None
        self._max_history = config.general.max_history

    async def initialize(self) -> bool:
        """Open the database and ensure schema is up to date.

        Returns:
            True if initialization succeeded.
        """
        if aiosqlite is None:
            logger.error("aiosqlite is not installed — database disabled")
            return False

        try:
            self._db_path.parent.mkdir(parents=True, exist_ok=True)
            self._db = await aiosqlite.connect(str(self._db_path))

            if self._config.database.wal_mode:
                await self._db.execute("PRAGMA journal_mode=WAL")

            await self._db.executescript(SCHEMA_SQL)

            # Check and set schema version
            async with self._db.execute(
                "SELECT version FROM schema_version ORDER BY version DESC LIMIT 1"
            ) as cursor:
                row = await cursor.fetchone()
                if row is None:
                    await self._db.execute(
                        "INSERT INTO schema_version (version) VALUES (?)",
                        (SCHEMA_VERSION,),
                    )
                    await self._db.commit()

            logger.info("Database initialized at %s", self._db_path)
            return True

        except Exception as exc:
            logger.error("Failed to initialize database: %s", exc)
            return False

    async def close(self) -> None:
        """Close the database connection."""
        if self._db:
            try:
                await self._db.close()
                logger.debug("Database connection closed")
            except Exception as exc:
                logger.warning("Error closing database: %s", exc)
            finally:
                self._db = None

    async def record_command(
        self,
        raw_text: str,
        intent: str,
        parameters: str,
        source: str,
        status: str,
        result_message: str,
    ) -> None:
        """Record a command execution in history."""
        if not self._db or not self._config.general.enable_history:
            return

        try:
            await self._db.execute(
                """INSERT INTO command_history
                   (timestamp, raw_text, intent, parameters, source, status, result_message)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (time.time(), raw_text, intent, parameters, source, status, result_message),
            )
            await self._db.commit()

            # Enforce retention limit
            if self._max_history > 0:
                await self._enforce_retention()

        except Exception as exc:
            logger.warning("Failed to record command: %s", exc)

    async def record_event(self, event_type: str, details: str = "") -> None:
        """Record a system event."""
        if not self._db:
            return
        try:
            await self._db.execute(
                "INSERT INTO system_events (timestamp, event_type, details) VALUES (?, ?, ?)",
                (time.time(), event_type, details),
            )
            await self._db.commit()
        except Exception as exc:
            logger.warning("Failed to record event: %s", exc)

    async def get_history(self, count: int = 20) -> list[dict[str, Any]]:
        """Retrieve recent command history entries."""
        if not self._db:
            return []
        try:
            async with self._db.execute(
                """SELECT id, timestamp, raw_text, intent, parameters, source, status,
                          result_message, created_at
                   FROM command_history
                   ORDER BY timestamp DESC LIMIT ?""",
                (count,),
            ) as cursor:
                rows = await cursor.fetchall()
                columns = [desc[0] for desc in cursor.description]
                return [dict(zip(columns, row)) for row in rows]
        except Exception as exc:
            logger.warning("Failed to retrieve history: %s", exc)
            return []

    async def _enforce_retention(self) -> None:
        """Delete oldest entries beyond the max_history limit."""
        if not self._db or self._max_history <= 0:
            return
        try:
            await self._db.execute(
                """DELETE FROM command_history WHERE id NOT IN (
                       SELECT id FROM command_history
                       ORDER BY timestamp DESC LIMIT ?
                   )""",
                (self._max_history,),
            )
            await self._db.commit()
        except Exception as exc:
            logger.warning("Failed to enforce history retention: %s", exc)

    async def health_check(self) -> dict[str, Any]:
        """Return database health information."""
        info: dict[str, Any] = {
            "connected": self._db is not None,
            "path": str(self._db_path),
        }
        if self._db:
            try:
                async with self._db.execute("SELECT COUNT(*) FROM command_history") as cur:
                    row = await cur.fetchone()
                    info["history_count"] = row[0] if row else 0
                async with self._db.execute("PRAGMA integrity_check") as cur:
                    row = await cur.fetchone()
                    info["integrity"] = row[0] if row else "unknown"
            except Exception as exc:
                info["error"] = str(exc)
        return info
