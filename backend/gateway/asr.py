"""Transcription providers: local GPU Whisper or Deepgram (switchable).

The gateway exposes `POST /v1/asr/transcribe` which honors the configured
`ASR_PROVIDER` (deepgram | whisper | auto). Whisper runs locally on the GB10 GPU
(private, no per-minute cost); Deepgram is the cloud fallback. Both return the
same normalized shape so the app can switch without changing code.
"""
from __future__ import annotations

import logging
import threading
import time

import numpy as np

from .slur import wav_to_pcm

log = logging.getLogger("strokesense.gateway.asr")

FILLERS = {"uh", "um", "er", "ah", "hmm", "like", "you", "know", "well", "okay"}


def _lexical(text: str, duration_s: float) -> tuple[int, float, float]:
    words = [w for w in text.lower().split() if any(c.isalnum() for c in w)]
    n = len(words)
    wpm = (n / duration_s * 60.0) if duration_s > 0 else 0.0
    fillers = sum(1 for w in words if w.strip(".,!?") in FILLERS)
    filler_ratio = fillers / n if n else 0.0
    return n, wpm, filler_ratio


class WhisperAsr:
    """Local transcription via openai-whisper on the GB10 GPU."""

    def __init__(self, model_size: str = "large-v3", device: str = "cuda") -> None:
        self._model_size = model_size
        self._device = device
        self._lock = threading.Lock()
        self._model = None

    def _ensure(self):
        if self._model is None:
            import whisper

            t = time.time()
            self._model = whisper.load_model(self._model_size, device=self._device)
            log.info("whisper loaded (%s, %s) in %.1fs", self._model_size, self._device, time.time() - t)

    def transcribe(self, body: bytes) -> dict:
        with self._lock:
            self._ensure()
            x = wav_to_pcm(body)
            if x.size == 0:
                return {"provider": "whisper", "error": "bad wav"}
            duration_s = float(x.size / 16000)
            t = time.time()
            result = self._model.transcribe(x, fp16=True, word_timestamps=True)
            latency_ms = int((time.time() - t) * 1000)
            text = result.get("text", "").strip()
            lang = result.get("language", "") or ""
            words: list[dict] = []
            confs: list[float] = []
            for seg in result.get("segments", []):
                for w in seg.get("words", []):
                    p = float(w.get("probability", 0.0))
                    confs.append(p)
                    words.append(
                        {
                            "word": w.get("word", "").strip(),
                            "start": round(float(w.get("start", 0.0)), 3),
                            "end": round(float(w.get("end", 0.0)), 3),
                            "confidence": round(p, 3),
                        }
                    )
            confidence = (sum(confs) / len(confs)) if confs else 0.0
            n, wpm, filler_ratio = _lexical(text, duration_s)
            log.info(
                "whisper transcribe dur=%.2fs latency=%dms words=%d conf=%.2f",
                duration_s,
                latency_ms,
                n,
                confidence,
            )
            return {
                "provider": "whisper",
                "text": text,
                "language": lang,
                "duration_s": round(duration_s, 2),
                "latency_ms": latency_ms,
                "word_count": n,
                "wpm": round(wpm, 1),
                "confidence": round(confidence, 3),
                "filler_ratio": round(filler_ratio, 3),
                "words": words,
            }


class DeepgramAsr:
    """Batch transcription via the Deepgram REST API (fallback provider)."""

    def __init__(self, api_key: str, base_url: str = "https://api.deepgram.com/v1/listen") -> None:
        self._api_key = api_key
        self._base_url = base_url

    async def transcribe(self, body: bytes, client) -> dict:
        import httpx

        try:
            resp = await client.post(
                self._base_url,
                params={"model": "nova-3", "punctuate": "true", "words": "true"},
                headers={"Authorization": f"Token {self._api_key}", "Content-Type": "audio/wav"},
                content=body,
            )
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            log.warning("deepgram transcribe failed: %s", exc)
            return {"provider": "deepgram", "error": str(exc)}

        data = resp.json()
        ch = (data.get("results", {}).get("channels") or [{}])[0]
        alt = (ch.get("alternatives") or [{}])[0]
        text = alt.get("transcript", "")
        conf = float(alt.get("confidence", 0.0))
        words = [
            {
                "word": w.get("word", ""),
                "start": round(float(w.get("start", 0.0)), 3),
                "end": round(float(w.get("end", 0.0)), 3),
                "confidence": round(float(w.get("confidence", 0.0)), 3),
            }
            for w in alt.get("words", [])
        ]
        duration_s = float(ch.get("duration", 0.0))
        n, wpm, filler_ratio = _lexical(text, duration_s)
        return {
            "provider": "deepgram",
            "text": text,
            "language": "",
            "duration_s": round(duration_s, 2),
            "latency_ms": 0,
            "word_count": n,
            "wpm": round(wpm, 1),
            "confidence": round(conf, 3),
            "filler_ratio": round(filler_ratio, 3),
            "words": words,
        }


class Asr:
    """Provider-agnostic facade for the transcribe endpoint."""

    def __init__(self, settings) -> None:
        self._provider = (settings.asr_provider or "auto").lower()
        self._whisper = WhisperAsr(settings.whisper_model, settings.whisper_device)
        self._deepgram = DeepgramAsr(settings.deepgram_api_key, settings.deepgram_base_url)

    async def transcribe(self, body: bytes, client) -> dict:
        import asyncio

        provider = self._provider
        if provider == "whisper":
            return await asyncio.to_thread(self._whisper.transcribe, body)
        if provider == "deepgram":
            return await self._deepgram.transcribe(body, client)
        # auto: whisper, fall back to deepgram if it fails
        try:
            return await asyncio.to_thread(self._whisper.transcribe, body)
        except Exception as exc:  # noqa: BLE001
            log.warning("whisper failed, falling back to deepgram: %s", exc)
            return await self._deepgram.transcribe(body, client)