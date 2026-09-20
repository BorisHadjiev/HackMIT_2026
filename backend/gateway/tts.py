from __future__ import annotations

import logging
import os
import threading

import numpy as np

from .config import Settings

log = logging.getLogger("strokesense.gateway.tts")


class TtsError(Exception):
    pass


class TtsEngine:
    """Local TTS wrapper. Primary: kokoro (torch, public weights); fallback: piper."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._kokoro = None
        self._piper = None
        self._lock = threading.Lock()

    def _ensure_kokoro(self):
        if self._kokoro is not None:
            return
        try:
            from kokoro import KPipeline
        except Exception as exc:
            raise TtsError(f"kokoro not installed: {exc}") from exc
        self._kokoro = KPipeline(lang_code="a")  # American English

    def _ensure_piper(self):
        if self._piper is not None:
            return
        try:
            from piper_tts import PiperVoice
        except Exception as exc:
            raise TtsError(f"piper not installed: {exc}") from exc
        model_dir = self._settings.tts_models_dir
        voice_path = os.path.join(model_dir, "en_US-lessac-medium.onnx")
        if not os.path.exists(voice_path):
            raise TtsError(f"piper voice missing: {voice_path}")
        self._piper = PiperVoice.load(voice_path, config_path=voice_path + ".json")

    def synthesize(self, text: str, voice: str | None = None) -> tuple[np.ndarray, int]:
        engine = self._settings.tts_engine.lower()
        with self._lock:
            if engine == "kokoro":
                try:
                    return self._kokoro_synth(text, voice)
                except TtsError:
                    log.warning("kokoro failed, falling back to piper")
            return self._piper_synth(text)

    def _kokoro_synth(self, text: str, voice: str | None) -> tuple[np.ndarray, int]:
        self._ensure_kokoro()
        chunks = []
        for result in self._kokoro(text, voice=voice or self._settings.tts_voice, speed=1.0):
            chunks.append(result.audio)
        if not chunks:
            raise TtsError("kokoro produced no audio")
        return np.concatenate(chunks), 24000

    def _piper_synth(self, text: str) -> tuple[np.ndarray, int]:
        self._ensure_piper()
        import io

        import wave

        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            self._piper.synthesize(text, w)
        with wave.open(io.BytesIO(buf.getvalue())) as w:
            rate = w.getframerate()
            frames = w.readframes(w.getnframes())
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
        return samples, rate


def wav_bytes(samples: np.ndarray, rate: int) -> bytes:
    import io

    import wave

    buf = io.BytesIO()
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()