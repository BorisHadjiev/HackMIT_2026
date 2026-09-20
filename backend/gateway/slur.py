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
WINDOW_SAMPLES = 16_000 * 4  # 4 s scoring window (matches calibration)
HOP_SAMPLES = 16_000 * 2
MAX_WINDOWS = 4
MIN_CALIB_WINDOWS = 2
SSL_MODEL_ID = "microsoft/wavlm-base-plus"

# Quality gate: silence / near-silence must never be scored as slur.
RMS_MIN = 0.006
MIN_SPEECH_FRACTION = 0.15

# Personal-mode LR scores live on a different scale than the corpus (0.945).
# TORGO same-speaker personal: healthy ~0.00, dysarthric ~1.0; real phone slur
# often peaks ~0.45-0.8, so the corpus threshold misses it. Balanced default.
DEFAULT_PERSONAL_THRESHOLD = 0.40
# Corpus mode is only trusted when the embedding is in-distribution (enrolled users
# use personal mode instead). Phone audio without calibration is OOD.
DEFAULT_OOD_MAX = 1.2


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
        x = torchaudio_resample(x, sr)
    return x


def torchaudio_resample(x: np.ndarray, sr: int) -> np.ndarray:
    import torch
    import torchaudio.functional as TAF

    t = torch.from_numpy(x)
    return TAF.resample(t, sr, 16000).numpy()


def rms(x: np.ndarray) -> float:
    if x.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(x.astype(np.float64) ** 2)))


def _frame_rms(x: np.ndarray, sr: int = 16000, frame_ms: int = 30) -> np.ndarray:
    frame = int(sr * frame_ms / 1000)
    if x.size < frame:
        return np.array([rms(x)], dtype=np.float64)
    n = x.size // frame
    return np.sqrt(np.mean(x[: n * frame].reshape(n, frame).astype(np.float64) ** 2, axis=1))


def speech_fraction(x: np.ndarray, sr: int = 16000) -> float:
    if x.size == 0:
        return 0.0
    fr = _frame_rms(x, sr)
    if fr.size == 0:
        return 0.0
    thr = max(RMS_MIN, 0.4 * float(np.median(fr)))
    return float(np.mean(fr > thr))


def select_windows(x: np.ndarray) -> list[np.ndarray]:
    """Highest-energy 4 s speech windows (peak-pooled later). Never zero-pad."""
    if x.size < MIN_SAMPLES:
        return []
    if rms(x) < RMS_MIN:
        return []
    if x.size <= WINDOW_SAMPLES:
        return [x]

    cands: list[tuple[float, np.ndarray]] = []
    start = 0
    while start < x.size:
        end = min(start + WINDOW_SAMPLES, x.size)
        if end - start >= MIN_SAMPLES:
            w = x[start:end]
            lvl = rms(w)
            if lvl >= RMS_MIN:
                cands.append((lvl, w))
        if end >= x.size:
            break
        start += HOP_SAMPLES
    cands.sort(key=lambda t: t[0], reverse=True)
    wins = [w for _, w in cands[:MAX_WINDOWS]]
    return wins or [x]


class SlurServer:
    """Server-side slur classifier: WavLM embeddings + SSL LR (WavLM-only).

    Two operating modes:
      - corpus  : cross-sectional LR on raw WavLM embeddings; only trusted when the
                  embedding is in-distribution (guarded by an OOD check).
      - personal: domain-shifted scoring for the enrolled user
                  (z' = z - user_centroid + healthy_centroid), with a personal
                  decision threshold. This is the trusted path.
    Windows are VAD-selected, peak-pooled, never averaged.
    """

    def __init__(
        self,
        model_json: str,
        profile_path: str = "",
        personal_threshold: float | None = None,
        ood_max: float | None = None,
    ) -> None:
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
        self._personal_threshold = float(
            personal_threshold
            if personal_threshold is not None
            else self._params.get("personal_threshold", DEFAULT_PERSONAL_THRESHOLD)
        )
        self._ood_max = float(
            ood_max if ood_max is not None else self._params.get("ood_max", DEFAULT_OOD_MAX)
        )
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
                "slur server ready (model=%s, threshold=%.4f, personal_thr=%.4f, enrolled=%s)",
                SSL_MODEL_ID,
                self._params["threshold"],
                self._personal_threshold,
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

    def _empty(self, reason: str, extra: dict | None = None) -> dict:
        out = {
            "score": 0.0,
            "score_corpus": 0.0,
            "score_personal": 0.0,
            "score_cal": 0.0,
            "mode": "personal" if self._centroid is not None else "corpus",
            "enrolled": self._centroid is not None,
            "ood": 0.0,
            "temperature": round(self._temperature, 3),
            "threshold": float(self._params["threshold"]),
            "detected": False,
            "confidence": "low",
            "reason": reason,
            "speech_rms": 0.0,
            "speech_fraction": 0.0,
            "windows": 0,
        }
        if extra:
            out.update(extra)
        return out

    def enroll(self, body: bytes) -> dict:
        with self._lock:
            self._ensure()
            x = wav_to_pcm(body)
            wins = select_windows(x)
            if len(wins) < MIN_CALIB_WINDOWS:
                return {
                    "status": "error",
                    "error": f"need >= {MIN_CALIB_WINDOWS * 4}s of audible speech for calibration",
                }
            embs = np.stack([self._standardize(self._embed(w)) for w in wins[:8]])
            # Median-of-windows is robust to one odd (e.g. noisy) window.
            self._centroid = np.median(embs, axis=0).astype(np.float32)
            self._windows = int(embs.shape[0])
            self._enrolled_at = int(time.time() * 1000)
            self._save_profile()
            log.info("slur enrolled windows=%d", self._windows)
            return {
                "status": "enrolled",
                "windows": self._windows,
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
                "personal_threshold": self._personal_threshold,
                "ood_max": self._ood_max,
            }

    def analyze(self, body: bytes) -> dict:
        with self._lock:
            self._ensure()
            self._refresh_profile()
            x = wav_to_pcm(body)
            if x.size == 0:
                return self._empty("bad wav")
            level = rms(x)
            frac = speech_fraction(x)
            if level < RMS_MIN or frac < MIN_SPEECH_FRACTION:
                log.info("slur analyze gated: rms=%.4f frac=%.2f", level, frac)
                return self._empty(
                    "no_speech",
                    extra={"speech_rms": round(level, 4), "speech_fraction": round(frac, 3)},
                )
            wins = select_windows(x)
            if not wins:
                return self._empty("no_speech")

            enrolled = self._centroid is not None and self._healthy_centroid is not None
            score_corpus = 0.0
            score_personal = 0.0
            oods: list[float] = []
            best_cal = 0.0
            for w in wins:
                z = self._standardize(self._embed(w))
                sc, ood = self._corpus_score(z)
                oods.append(ood)
                score_corpus = max(score_corpus, sc)
                if enrolled:
                    zp = z - self._centroid + self._healthy_centroid
                    sp, _ = self._corpus_score(zp)
                    score_personal = max(score_personal, sp)
                    if sp >= score_personal:
                        cal, _ = self._corpus_score(zp, self._temperature)
                        best_cal = max(best_cal, cal)
            ood = float(np.mean(oods)) if oods else 0.0

            if enrolled:
                score = score_personal
                thr = self._personal_threshold
                score_cal = best_cal
                detected = bool(score >= thr)
                confidence = "ok"
            else:
                score = score_corpus
                thr = float(self._params["threshold"])
                score_cal = max(best_cal, score)
                if ood > self._ood_max:
                    # Uncalibrated + out-of-distribution: do not assert slur.
                    detected = False
                    confidence = "low"
                else:
                    detected = bool(score >= thr)
                    confidence = "ok"

            log.info(
                "slur analyze: mode=%s score=%.3f corpus=%.3f personal=%.3f ood=%.2f "
                "thr=%.3f rms=%.4f frac=%.2f wins=%d detected=%s conf=%s",
                "personal" if enrolled else "corpus",
                score,
                score_corpus,
                score_personal,
                ood,
                thr,
                level,
                frac,
                len(wins),
                detected,
                confidence,
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
                "detected": detected,
                "confidence": confidence,
                "reason": "ok",
                "speech_rms": round(level, 4),
                "speech_fraction": round(frac, 3),
                "windows": len(wins),
            }