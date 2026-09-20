from __future__ import annotations

import logging

import httpx

from .config import Settings

log = logging.getLogger("strokesense.gateway.agent")

BASE_SYSTEM = (
    "You are StrokeSense, an on-device health-assistant for stroke screening. "
    "You help users perform a FAST-style assessment (Face, Arms, Speech, Time) "
    "and answer their questions about the current task. Be calm, brief, and "
    "direct. You are NOT a medical device; always remind them that if a stroke "
    "is suspected they should call emergency services immediately. "
    "Relevant task instructions: {task_context}"
)


class AgentClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def answer(
        self,
        question: str,
        task_context: str = "",
        history: list[dict] | None = None,
    ) -> dict:
        messages: list[dict] = [
            {"role": "system", "content": BASE_SYSTEM.format(task_context=task_context or "No active task.")},
        ]
        for turn in history or []:
            messages.append({"role": turn.get("role", "user"), "content": turn.get("content", "")})
        messages.append({"role": "user", "content": question})

        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.post(
                    f"{self._settings.ollama_url}/api/chat",
                    json={
                        "model": self._settings.ollama_model,
                        "messages": messages,
                        "stream": False,
                    },
                )
                resp.raise_for_status()
        except Exception as exc:
            log.warning("ollama error: %s", exc)
            return {"answer": "The local assistant is unavailable right now.", "model": ""}
        data = resp.json()
        return {"answer": data.get("message", {}).get("content", "").strip(), "model": data.get("model", "")}