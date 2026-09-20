"""Auto-caller: drives the caller side of the simulated 911 call with an LLM.

The Deepgram Voice Agent plays the dispatcher. Here a local Ollama model plays the
caller/patient-side assistant. To keep the exchange a clean back-and-forth, the
gateway decides *which single fact* to reveal each turn (location, symptoms, onset,
responsiveness, breathing) and the LLM only phrases that one fact. Kokoro TTS speaks
it into the agent as PCM16 16 kHz. Fully LLM-to-LLM — no human microphone needed.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re

import httpx
import numpy as np

from .agent_voice import format_context

log = logging.getLogger("strokesense.gateway.agent_auto")

_MD = re.compile(r"[*_`#>]+")


def plain(text: str) -> str:
    """Strip markdown so TTS never reads 'star star' and no lists survive."""
    text = _MD.sub("", text)
    text = re.sub(r"(?m)^\s*\d+[.)]\s*", "", text)
    return " ".join(text.split())

SAMPLE_RATE = 16000

FACT_ORDER = ["location", "symptoms", "onset", "responsive", "breathing"]

# Matching priority (more specific intents first, so "what time did the symptoms
# start?" maps to onset rather than the generic "symptoms").
MATCH_ORDER = ["location", "onset", "responsive", "breathing", "symptoms"]

FACT_KEYWORDS = {
    "location": ["where", "location", "address", "street", "place"],
    "onset": ["how long", "when", "start", "onset", "ago", "time", "notice"],
    "responsive": ["awake", "responsive", "conscious", "alert", "respond", "talking"],
    "breathing": ["breath", "breathing", "airway"],
    "symptoms": ["symptom", "sign", "happening", "wrong", "describe", "look", "doing"],
}

PHRASE_SYSTEM = (
    "You are the on-scene caller on an automated StrokeSense 911 call. You will be given one "
    "fact; say it as ONE short, natural spoken sentence and nothing else. Plain text only: no "
    "markdown, no asterisks, no lists. Do not add any other fact. Never diagnose."
)


def build_facts(context: dict | None) -> dict[str, str]:
    ctx = context or {}
    location = (ctx.get("location") or "").strip()
    signs = ctx.get("signs") or []
    if isinstance(signs, str):
        signs = [s for s in signs.split(",") if s]
    symptoms = ("He has " + ", ".join(signs) + ".") if signs else "His face looks uneven and his speech is off."
    onset = ctx.get("onset_minutes")
    onset_text = f"It started about {int(onset)} minutes ago." if str(onset).isdigit() else "I'm not sure exactly when it started."
    return {
        "location": f"Yes, the patient is at {location}." if location else "I don't know the exact address.",
        "symptoms": symptoms,
        "onset": onset_text,
        "responsive": "He is awake and answers me.",
        "breathing": "His breathing seems normal.",
    }


def pick_fact(facts: dict[str, str], revealed: set[str], dispatcher_question: str) -> str:
    q = (dispatcher_question or "").lower()
    for key in MATCH_ORDER:
        if any(word in q for word in FACT_KEYWORDS[key]):
            return key
    for key in FACT_ORDER:
        if key not in revealed:
            return key
    return "done"


def pcm16_16k(samples: np.ndarray, rate: int) -> bytes:
    if rate != SAMPLE_RATE:
        import torch
        import torchaudio

        samples = torchaudio.functional.resample(
            torch.from_numpy(np.asarray(samples, dtype=np.float32)), rate, SAMPLE_RATE
        ).numpy()
    return (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16).tobytes()


async def _phrase_fact(settings, fact: str) -> str:
    messages = [
        {"role": "system", "content": PHRASE_SYSTEM},
        {"role": "user", "content": f"Fact: {fact}"},
    ]
    async with httpx.AsyncClient(timeout=60.0) as client:
        resp = await client.post(
            f"{settings.ollama_url.rstrip('/')}/v1/chat/completions",
            json={
                "model": settings.agent_caller_model,
                "messages": messages,
                "stream": False,
                "temperature": 0.2,
            },
        )
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"].strip()


async def run_auto_caller(
    tts_engine,
    settings,
    dg,
    websocket,
    context: dict | None,
    max_turns: int = 7,
) -> None:
    """Play the caller side, revealing one fact per dispatcher turn."""
    facts = build_facts(context)
    revealed: set[str] = set()
    pending_assistant = ""
    turns = 0
    audio_lock = asyncio.Lock()
    silence = b"\x00\x00" * (SAMPLE_RATE // 10)  # 100 ms

    async def to_agent(pcm: bytes) -> None:
        async with audio_lock:
            for i in range(0, len(pcm), len(silence)):
                await dg.send(pcm[i : i + len(silence)])
                await asyncio.sleep(0.02)

    async def silence_pump() -> None:
        try:
            while True:
                await asyncio.sleep(0.1)
                if audio_lock.locked():
                    continue
                async with audio_lock:
                    await dg.send(silence)
        except asyncio.CancelledError:
            return
        except Exception:
            return

    pump = asyncio.create_task(silence_pump())
    try:
        async for msg in dg:
            if isinstance(msg, (bytes, bytearray)):
                await websocket.send_bytes(msg)  # dispatcher audio -> app
                continue
            try:
                event = json.loads(msg)
            except Exception:
                await websocket.send_text(msg)
                continue

            etype = event.get("type")
            # We emit the canonical caller line ourselves, so drop Deepgram's STT of it.
            if etype == "ConversationText" and event.get("role") == "user":
                continue
            await websocket.send_text(msg)

            if etype == "ConversationText" and event.get("role") == "assistant":
                pending_assistant = (pending_assistant + " " + event.get("content", "")).strip()
            elif etype == "AgentAudioDone":
                question = pending_assistant
                pending_assistant = ""
                key = pick_fact(facts, revealed, question)
                if key == "done" or turns >= max_turns:
                    await websocket.send_text(
                        json.dumps({"type": "ConversationText", "role": "user", "content": "That's everything I know."})
                    )
                    break
                revealed.add(key)
                turns += 1
                fact = facts.get(key, "I don't know.")
                try:
                    reply = await _phrase_fact(settings, fact)
                except Exception as exc:  # noqa: BLE001
                    log.warning("auto caller LLM failed: %s", exc)
                    reply = fact
                reply = plain(reply) or plain(fact)
                await websocket.send_text(
                    json.dumps({"type": "ConversationText", "role": "user", "content": reply})
                )
                try:
                    samples, rate = tts_engine.synthesize(reply, None)
                    pcm = pcm16_16k(samples, rate)
                except Exception as exc:  # noqa: BLE001
                    log.warning("auto caller TTS failed: %s", exc)
                    pcm = b""
                lead = b"\x00\x00" * int(SAMPLE_RATE * 0.3)
                trail = b"\x00\x00" * int(SAMPLE_RATE * 0.7)
                await to_agent(lead + pcm + trail)
                log.info("auto caller turn %d (%s): %s", turns, key, reply[:100])
            elif etype == "Error":
                break
    finally:
        pump.cancel()
        await asyncio.gather(pump, return_exceptions=True)