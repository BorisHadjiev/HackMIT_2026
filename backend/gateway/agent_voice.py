"""Simulated-911 voice agent: builds the Deepgram Voice Agent ``Settings`` message.

The dispatcher "brain" is the local Ollama model exposed through the gateway's
OpenAI-compatible passthrough (``/v1/llm/chat/completions``); the voice is Deepgram
Aura; speech-to-text is Deepgram. No telephony is involved — this is an in-app
simulation and the agent is instructed to say so.
"""
from __future__ import annotations

from .config import Settings

SAMPLE_RATE = 16000


def format_context(context: dict | None) -> str:
    """Turn the caller's live screening snapshot into one report sentence."""
    if not context:
        return ""
    bits: list[str] = []
    location = context.get("location")
    if location:
        bits.append(f"location {location}")
    if context.get("p_stroke") is not None:
        try:
            bits.append(f"stroke likelihood {round(float(context['p_stroke']) * 100)}%")
        except (TypeError, ValueError):
            pass
    if context.get("severity") is not None:
        try:
            bits.append(f"severity {round(float(context['severity']) * 100)}%")
        except (TypeError, ValueError):
            pass
    if context.get("action"):
        bits.append(f"suggested action {context['action']}")
    signs = context.get("signs")
    if signs:
        bits.append("signs: " + ", ".join(str(s) for s in signs))
    onset = context.get("onset_minutes")
    if onset not in (None, ""):
        try:
            bits.append(f"onset about {int(onset)} minutes ago")
        except (TypeError, ValueError):
            pass
    if not bits:
        return ""
    return "StrokeSense automated alert: " + "; ".join(bits) + ". (Screening aid, not a diagnosis.)"


def build_settings(settings: Settings, context: dict | None = None, seed_report: bool = True) -> dict:
    report = format_context(context) if seed_report else ""

    think: dict = {
        "provider": {"type": "open_ai", "model": settings.agent_llm_model, "temperature": 0.4},
    }
    if settings.agent_llm_secret:
        think["endpoint"] = {
            # Public gateway base Deepgram's cloud can reach.
            "url": f"{(settings.agent_public_base_url or settings.linq_public_base_url).rstrip('/')}/v1/llm/chat/completions",
            "headers": {"authorization": f"Bearer {settings.agent_llm_secret}"},
        }

    agent: dict = {
        "greeting": settings.agent_greeting,
        "listen": {
            "provider": {
                "type": "deepgram",
                "model": settings.agent_listen_model,
                "language": "en",
                "smart_format": True,
                "keyterms": ["stroke", "face drooping", "slurred speech", "ambulance"],
            }
        },
        "think": think,
        "speak": {
            "provider": {
                "type": "deepgram",
                "version": "v1",
                "model": settings.agent_voice,
                "speed": 1.0,
            }
        },
    }
    if report:
        # Seed the conversation as the automated caller's first (already spoken) message,
        # so the dispatcher has the symptoms + location from the start.
        agent["context"] = {"messages": [{"type": "History", "role": "user", "content": report}]}

    return {
        "type": "Settings",
        "audio": {
            "input": {"encoding": "linear16", "sample_rate": SAMPLE_RATE},
            "output": {"encoding": "linear16", "sample_rate": SAMPLE_RATE, "container": "none"},
        },
        "agent": agent,
    }