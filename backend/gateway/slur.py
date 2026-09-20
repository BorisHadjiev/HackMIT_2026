from __future__ import annotations

import io
import json
import logging
import os
import threading
import time
import wave

import numpy as np

log = logging.getLogger("strokesense.gateway.slur")

MIN_SAMPLES = 8000  # 0.5 s @ 16 kHz
WINDOW_SAMPLES = 16_000 * 4  # 4 s scoring window (matches the app's AI uploads)
MIN_CALIB_WINDOWS = 2
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
    """Server-side slur classifier: WavLM embeddings + SSL LR.

    Two operating modes:
      - corpus  : the trained SSL-only LR on raw WavLM embeddings (cross-sectional).
      - personal: domain-shifted scoring for the enrolled user. Live speech is
                  standardized, shifted onto the corpus healthy centroid
                  (z' = z - user_centroid + healthy_centroid), then scored with the
                  same LR. This anchors the user's phone-mic domain to the healthy
                  region (killing the OOD false-positive mode) while preserving the
                  LR's content-robust discrimination.
    Personal mode is used whenever a valid profile exists.
    """

    def __init__(self, model_json: str, profile_path: str = "") -> None:
        self._params = json.load(open(model_json))
        self._profile_path = profile_path
        self._lock = threading.Lock()
        self._model = None
        self._feature_extractor = None
        self._centroid = None
        self._windows = 0
        self._enrolled_at = None
        self._profile_mtime = None
        h = self._params.get("healthy_centroid_z")
        self._healthy_centroid = np.asarray(h, dtype=np.float32) if h else None
        self._temperature = float(self._params.get("temperature", 1.0))
        self._load_profile()

    def _load_profile(self) -> None:
        if not self._profile_path or not os.path.exists(self._profile_path):
            return
        try:
            self._profile_mtime = os.path.getmtime(self._profile_path)
            p = json.load(open(self._profile_path))
            self._centroid = np.asarray(p["centroid"], dtype=np.float32)
            self._windows = p.get("windows", 0)
            self._enrolled_at = p.get("enrolled_at")
            log.info("slur profile loaded (dims=%d windows=%s)", self._centroid.size, self._windows)
        except Exception as exc:  # noqa: BLE001
            log.warning("could not load slur profile: %s", exc)

    def _refresh_profile(self) -> None:
        """Reload the on-disk profile if a sibling worker updated it (multi-worker safe)."""
        if not self._profile_path or not os.path.exists(self._profile_path):
            if self._centroid is not None:
                self._centroid = None
                self._windows = 0
                self._enrolled_at = None
                self._profile_mtime = None
            return
        mtime = os.path.getmtime(self._profile_path)
        if mtime != self._profile_mtime:
            self._load_profile()

    def _save_profile(self) -> None:
        if not self._profile_path:
            return
        os.makedirs(os.path.dirname(self._profile_path) or ".", exist_ok=True)
        with open(self._profile_path, "w") as f:
            json.dump(
                {
                    "centroid": self._centroid.tolist(),
                    "windows": self._windows,
                    "enrolled_at": self._enrolled_at,
                },
                f,
            )
        self._profile_mtime = os.path.getmtime(self._profile_path)

    def _ensure(self):
        if self._model is None:
            from transformers import AutoFeatureExtractor, AutoModel

            self._feature_extractor = AutoFeatureExtractor.from_pretrained(SSL_MODEL_ID)
            self._model = AutoModel.from_pretrained(SSL_MODEL_ID).eval()
            if __import__("torch").cuda.is_available():
                self._model = self._model.cuda()
            log.info(
                "slur server ready (model=%s, threshold=%.4f, enrolled=%s)",
                SSL_MODEL_ID,
                self._params["threshold"],
                self._centroid is not None,
            )

    def _embed(self, x: np.ndarray) -> np.ndarray:
        """Mean+std pooled last-hidden-state embedding (1536-d float32)."""
        import torch

        inputs = self._feature_extractor([x], sampling_rate=16000, return_tensors="pt")
        device = next(self._model.parameters()).device
        with torch.no_grad():
            out = self._model(inputs["input_values"].to(device)).last_hidden_state[0]
            mean = out.mean(0).cpu().numpy()
            std = out.std(0).cpu().numpy()
        return np.concatenate([mean, std]).astype(np.float32)

    def _standardize(self, v: np.ndarray) -> np.ndarray:
        p = self._params
        return (v - np.asarray(p["mean"], dtype=np.float32)) / np.asarray(p["std"], dtype=np.float32)

    def _corpus_score(self, z: np.ndarray, temperature: float = 1.0) -> tuple[float, float]:
        p = self._params
        ood = float(np.mean(np.abs(z)))
        logit = float(np.dot(np.asarray(p["coef"], dtype=np.float32), z)) + p["intercept"]
        logit = logit / temperature
        return 1.0 / (1.0 + np.exp(-logit)), ood

    def enroll(self, body: bytes) -> dict:
        with self._lock:
            self._ensure()
            x = wav_to_pcm(body)
            if x.size < MIN_SAMPLES:
                return {"status": "error", "error": "too short for calibration"}
            n = x.size // WINDOW_SAMPLES
            if n < MIN_CALIB_WINDOWS:
                return {
                    "status": "error",
                    "error": f"need >= {MIN_CALIB_WINDOWS * 4}s of speech for calibration",
                }
            embs = np.stack(
                [
                    self._standardize(
                        self._embed(x[i * WINDOW_SAMPLES : (i + 1) * WINDOW_SAMPLES])
                    )
                    for i in range(n)
                ]
            )
            self._centroid = embs.mean(axis=0).astype(np.float32)
            self._windows = n
            self._enrolled_at = int(time.time() * 1000)
            self._save_profile()
            log.info("slur enrolled windows=%d", n)
            return {
                "status": "enrolled",
                "windows": n,
                "dims": int(self._centroid.size),
                "mode": "personal",
            }

    def clear(self) -> dict:
        with self._lock:
            self._centroid = None
            self._windows = 0
            self._enrolled_at = None
            if self._profile_path and os.path.exists(self._profile_path):
                os.remove(self._profile_path)
            return {"status": "cleared"}

    def status(self) -> dict:
        with self._lock:
            self._refresh_profile()
            return {
                "enrolled": self._centroid is not None,
                "enrolled_at": self._enrolled_at,
                "windows": self._windows,
                "mode": "personal" if self._centroid is not None else "corpus",
                "threshold": float(self._params["threshold"]),
            }

    def analyze(self, body: bytes) -> dict:
        with self._lock:
            self._ensure()
            self._refresh_profile()
            x = wav_to_pcm(body)
            if x.size == 0:
                return {"detected": False, "error": "bad wav"}
            # Score the first 4 s window so analysis matches calibration embeddings.
            x = x[:WINDOW_SAMPLES] if x.size >= WINDOW_SAMPLES else np.pad(x, (0, WINDOW_SAMPLES - x.size))
            rms = float(np.sqrt(np.mean(x.astype(np.float64) ** 2)))
            log.info(
                "slur analyze raw: bytes=%d dur=%.2fs rms=%.4f",
                len(body),
                x.size / 16000,
                rms,
            )
            v = self._embed(x)
            z = self._standardize(v)
            score_corpus, ood = self._corpus_score(z)

            enrolled = self._centroid is not None and self._healthy_centroid is not None
            score_personal = score_corpus
            if enrolled:
                zp = z - self._centroid + self._healthy_centroid
                score_personal, _ = self._corpus_score(zp)

            score = score_personal if enrolled else score_corpus
            # Calibrated probability (temperature scaling) for downstream fusion.
            if enrolled:
                score_cal, _ = self._corpus_score(zp, self._temperature)
            else:
                score_cal, _ = self._corpus_score(z, self._temperature)
            thr = float(self._params["threshold"])
            log.info(
                "slur analyze: mode=%s score=%.3f corpus=%.3f ood=%.2f threshold=%.3f detected=%s",
                "personal" if enrolled else "corpus",
                score,
                score_corpus,
                ood,
                thr,
                score >= thr,
            )
            return {
                "score": round(score, 4),
                "score_corpus": round(score_corpus, 4),
                "score_personal": round(score_personal, 4),
                "score_cal": round(score_cal, 4),
                "mode": "personal" if enrolled else "corpus",
                "enrolled": enrolled,
                "ood": round(ood, 3),
                "temperature": round(self._temperature, 3),
                "threshold": thr,
                "detected": bool(score >= thr),
            }