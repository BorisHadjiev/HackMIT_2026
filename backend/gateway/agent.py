from __future__ import annotations

import hashlib
import logging
import time
from collections import OrderedDict

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

# In-memory answer cache keyed by (question, task_context) so repeated questions
# return instantly.
_CACHE: "OrderedDict[str, str]" = OrderedDict()
_CACHE_TTL_S = 600
_CACHE_MAX = 200


class AgentClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def answer(
        self,
        question: str,
        task_context: str = "",
        history: list[dict] | None = None,
    ) -> dict:
        cache_key = hashlib.sha256(f"{question}\n{task_context}".encode()).hexdigest()
        cached = _cache_get(cache_key)
        if cached is not None:
            return {"answer": cached, "model": self._settings.ollama_model, "cached": True}

        messages: list[dict] = [
            {"role": "system", "content": BASE_SYSTEM.format(task_context=task_context or "No active task.")},
        ]
        for turn in history or []:
            messages.append({"role": turn.get("role", "user"), "content": turn.get("content", "")})
        messages.append({"role": "user", "content": question})

        try:
            async with httpx.AsyncClient(timeout=45.0) as client:
                resp = await client.post(
                    f"{self._settings.ollama_url}/api/chat",
                    json={
                        "model": self._settings.ollama_model,
                        "messages": messages,
                        "stream": False,
                        "think": False,  # skip reasoning tokens on qwen3 for speed
                        "options": {"num_predict": 200},
                    },
                )
                resp.raise_for_status()
        except Exception as exc:
            log.warning("ollama error: %s", exc)
            return {"answer": "The local assistant is unavailable right now.", "model": ""}

        data = resp.json()
        answer = data.get("message", {}).get("content", "").strip()
        _cache_put(cache_key, answer)
        return {"answer": answer, "model": data.get("model", "")}


def _cache_get(key: str) -> str | None:
    entry = _CACHE.get(key)
    if entry is None:
        return None
    at, value = entry
    if time.time() - at > _CACHE_TTL_S:
        _CACHE.pop(key, None)
        return None
    return value


def _cache_put(key: str, value: str) -> None:
    _CACHE[key] = (time.time(), value)
    _CACHE.move_to_end(key)
    while len(_CACHE) > _CACHE_MAX:
        _CACHE.popitem(last=False)