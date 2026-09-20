"""Auto-caller: drives the caller side of the simulated 911 call with an LLM.

The Deepgram Voice Agent plays the dispatcher. Here a local Ollama model plays the
caller/patient-side assistant: it reads the dispatcher's turns and replies using the
StrokeSense report, and we speak those replies into the agent via Kokoro TTS (PCM16
16 kHz). Fully LLM-to-LLM — no human microphone needed.
"""
from __future__ import annotations

import asyncio
import json
import logging

import httpx
import numpy as np

from .agent_voice import format_context

log = logging.getLogger("strokesense.gateway.agent_auto")

SAMPLE_RATE = 16000

CALLER_SYSTEM = (
    "You are the on-scene caller on an automated StrokeSense alert call to 911. "
    "You are the caller, NOT the dispatcher. You have the patient's StrokeSense "
    "screening report below. Answer the dispatcher's questions accurately and briefly "
    "(one or two short sentences), using only the known facts; if something is unknown, "
    "say you don't know. Never diagnose. Keep the call moving and do not ask the "
    "dispatcher what they can tell you. This is a simulation."
)


def pcm16_16k(samples: np.ndarray, rate: int) -> bytes:
    if rate != SAMPLE_RATE:
        import torch
        import torchaudio

        samples = torchaudio.functional.resample(
            torch.from_numpy(np.asarray(samples, dtype=np.float32)), rate, SAMPLE_RATE
        ).numpy()
    return (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16).tobytes()


async def _caller_reply(settings, report: str, history: list[dict]) -> str:
    system = CALLER_SYSTEM + ("\n\nStrokeSense report: " + report if report else "")
    messages = [{"role": "system", "content": system}] + history
    async with httpx.AsyncClient(timeout=60.0) as client:
        resp = await client.post(
            f"{settings.ollama_url.rstrip('/')}/v1/chat/completions",
            json={
                "model": settings.agent_llm_model,
                "messages": messages,
                "stream": False,
                "temperature": 0.3,
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
    max_turns: int = 5,
) -> None:
    """Play the caller side until [max_turns] dispatcher turns (or the call ends)."""
    report = format_context(context)
    history: list[dict] = []
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
        # Keep the agent's audio stream alive between caller turns.
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
            await websocket.send_text(msg)

            etype = event.get("type")
            if etype == "ConversationText" and event.get("role") == "assistant":
                pending_assistant = (pending_assistant + " " + event.get("content", "")).strip()
            elif etype == "AgentAudioDone":
                if pending_assistant:
                    history.append({"role": "user", "content": pending_assistant})
                    pending_assistant = ""
                if turns >= max_turns:
                    break
                turns += 1
                try:
                    reply = await _caller_reply(settings, report, history)
                except Exception as exc:  # noqa: BLE001
                    log.warning("auto caller LLM failed: %s", exc)
                    break
                history.append({"role": "assistant", "content": reply})
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
                log.info("auto caller turn %d: %s", turns, reply[:120])
            elif etype == "Error":
                break
    finally:
        pump.cancel()
        await asyncio.gather(pump, return_exceptions=True)