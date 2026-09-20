from __future__ import annotations

import io
import json
import logging
import threading
import wave

import numpy as np

log = logging.getLogger("strokesense.gateway.slur")

MIN_SAMPLES = 8000  # 0.5 s @ 16 kHz
SSL_MODEL_ID = "microsoft/wavlm-base-plus"


def wav_to_pcm(body: bytes) -> np.ndarray:
    """Parse a PCM WAV (stdlib) -> float32 mono at 16 kHz."""
    with wave.open(io.BytesIO(body), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        data = w.readframes(w.getnframes())
    x = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    if nch > 1:
        x = x.reshape(-1, nch).mean(axis=1)
    if sr != 16000:
        import torch

        x = torchaudio_resample(x, sr)
    return x


def torchaudio_resample(x: np.ndarray, sr: int) -> np.ndarray:
    import torch
    import torchaudio.functional as TAF

    t = torch.from_numpy(x)
    return TAF.resample(t, sr, 16000).numpy()


class SlurServer:
    """Server-side slur classifier: WavLM embeddings + SSL-only logistic regression."""

    def __init__(self, model_json: str) -> None:
        self._params = json.load(open(model_json))
        self._lock = threading.Lock()
        self._model = None
        self._feature_extractor = None

    def _ensure(self):
        if self._model is None:
            from transformers import AutoFeatureExtractor, AutoModel

            self._feature_extractor = AutoFeatureExtractor.from_pretrained(SSL_MODEL_ID)
            self._model = AutoModel.from_pretrained(SSL_MODEL_ID).eval()
            if __import__("torch").cuda.is_available():
                self._model = self._model.cuda()
            log.info("slur server ready (model=%s, threshold=%.4f)", SSL_MODEL_ID, self._params["threshold"])

    def analyze(self, body: bytes) -> dict:
        with self._lock:
            self._ensure()
            x = wav_to_pcm(body)
            if x.size == 0:
                return {"detected": False, "error": "bad wav"}
            if x.size < MIN_SAMPLES:
                x = np.pad(x, (0, MIN_SAMPLES - x.size))
            rms = float(np.sqrt(np.mean(x.astype(np.float64) ** 2)))
            log.info("slur analyze raw: bytes=%d dur=%.2fs rms=%.4f", len(body), x.size / 16000, rms)
            inputs = self._feature_extractor([x], sampling_rate=16000, return_tensors="pt")
            import torch

            device = next(self._model.parameters()).device
            with torch.no_grad():
                out = self._model(inputs["input_values"].to(device)).last_hidden_state[0]
                mean = out.mean(0).cpu().numpy()
                std = out.std(0).cpu().numpy()
            v = np.concatenate([mean, std]).astype(np.float32)
            p = self._params
            z = (v - np.asarray(p["mean"], dtype=np.float32)) / np.asarray(p["std"], dtype=np.float32)
            logit = float(np.dot(np.asarray(p["coef"], dtype=np.float32), z)) + p["intercept"]
            score = 1.0 / (1.0 + np.exp(-logit))
            thr = float(p["threshold"])
            log.info("slur analyze: score=%.3f threshold=%.3f detected=%s", score, thr, score >= thr)
            return {"score": round(score, 4), "threshold": thr, "detected": bool(score >= thr)}