from __future__ import annotations

import time
from typing import Any

import aiosqlite

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA busy_timeout=5000;

CREATE TABLE IF NOT EXISTS idempotency (
    key           TEXT PRIMARY KEY,
    response_json TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS alerts (
    alert_id       TEXT PRIMARY KEY,
    created_at_ms  INTEGER NOT NULL,
    source         TEXT NOT NULL,
    kind           TEXT NOT NULL,
    severity       TEXT NOT NULL,
    title          TEXT NOT NULL,
    body           TEXT NOT NULL,
    recipient_name TEXT NOT NULL,
    recipient_phone TEXT NOT NULL,
    status         TEXT NOT NULL,          -- sent | failed
    trace_id       TEXT,
    linq_chat_id   TEXT,
    linq_message_id TEXT,
    response_json  TEXT,
    sent_at_ms     INTEGER
);

CREATE TABLE IF NOT EXISTS webhook_events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id      TEXT,
    event_type    TEXT,
    linq_chat_id  TEXT,
    linq_message_id TEXT,
    delivery_status TEXT,
    payload_json  TEXT NOT NULL,
    received_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_webhook_chat_msg
    ON webhook_events (linq_chat_id, linq_message_id);
"""


class Database:
    """Async SQLite store shared across uvicorn workers (WAL mode)."""

    def __init__(self, path: str) -> None:
        self._path = path
        self._conn: aiosqlite.Connection | None = None

    async def connect(self) -> None:
        self._conn = await aiosqlite.connect(self._path)
        await self._conn.execute("PRAGMA journal_mode=WAL")
        await self._conn.execute("PRAGMA busy_timeout=5000")
        await self._conn.executescript(SCHEMA)
        await self._conn.commit()

    async def close(self) -> None:
        if self._conn is not None:
            await self._conn.close()
            self._conn = None

    async def ping(self) -> bool:
        try:
            await self._conn.execute("SELECT 1")  # type: ignore[union-attr]
            return True
        except Exception:
            return False

    async def idem_get(self, key: str) -> dict[str, Any] | None:
        cur = await self._conn.execute(  # type: ignore[union-attr]
            "SELECT response_json FROM idempotency WHERE key = ?", (key,)
        )
        row = await cur.fetchone()
        await cur.close()
        if row is None:
            return None
        return {"key": key, "response_json": row[0]}

    async def idem_put(self, key: str, response_json: str) -> bool:
        """Insert idempotency row; returns False if the key already exists."""
        cur = await self._conn.execute(  # type: ignore[union-attr]
            "INSERT OR IGNORE INTO idempotency (key, response_json, created_at_ms) VALUES (?, ?, ?)",
            (key, response_json, int(time.time() * 1000)),
        )
        await self._conn.commit()  # type: ignore[union-attr]
        await cur.close()
        return cur.rowcount > 0

    async def record_alert(self, row: dict[str, Any]) -> None:
        await self._conn.execute(  # type: ignore[union-attr]
            """INSERT OR REPLACE INTO alerts
               (alert_id, created_at_ms, source, kind, severity, title, body,
                recipient_name, recipient_phone, status, trace_id,
                linq_chat_id, linq_message_id, response_json, sent_at_ms)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                row["alert_id"],
                row["created_at_ms"],
                row["source"],
                row["kind"],
                row["severity"],
                row["title"],
                row["body"],
                row["recipient_name"],
                row["recipient_phone"],
                row["status"],
                row.get("trace_id"),
                row.get("linq_chat_id"),
                row.get("linq_message_id"),
                row.get("response_json"),
                row.get("sent_at_ms"),
            ),
        )
        await self._conn.commit()  # type: ignore[union-attr]

    async def record_webhook_event(self, event: dict[str, Any]) -> None:
        await self._conn.execute(  # type: ignore[union-attr]
            """INSERT INTO webhook_events
               (event_id, event_type, linq_chat_id, linq_message_id,
                delivery_status, payload_json, received_at_ms)
               VALUES (?,?,?,?,?,?,?)""",
            (
                event.get("event_id"),
                event.get("event_type"),
                event.get("linq_chat_id"),
                event.get("linq_message_id"),
                event.get("delivery_status"),
                event.get("payload_json", "{}"),
                int(time.time() * 1000),
            ),
        )
        await self._conn.commit()  # type: ignore[union-attr]

    async def count_rows(self, table: str) -> int:
        cur = await self._conn.execute(f"SELECT COUNT(*) FROM {table}")  # type: ignore[union-attr]
        row = await cur.fetchone()
        await cur.close()
        return int(row[0]) if row else 0