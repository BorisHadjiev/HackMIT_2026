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
HOP_SAMPLES = 16_000 * 2
MIN_CALIB_WINDOWS = 2
MAX_WINDOWS = 6
SSL_MODEL_ID = "microsoft/wavlm-base-plus"

# Energy / VAD: match the app's recentHasEnergy (~0.01) but slightly more
# sensitive so quiet speech still scores. Silence must never look "slurred".
RMS_MIN = 0.008
SPEECH_FRACTION_MIN = 0.15
# Personal-mode LR scores sit on a different scale than the corpus (0.945)
# operating point. TORGO same-speaker personal: healthy ≈ 0.00, dysarthric ≈ 1.0.
DEFAULT_PERSONAL_THRESHOLD = 0.40


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


def speech_segments(
    samples: np.ndarray,
    sr: int = 16000,
    frame_ms: int = 30,
    threshold: float = RMS_MIN,
    min_speech_s: float = 0.2,
    min_silence_s: float = 0.25,
) -> list[tuple[int, int]]:
    """Energy VAD. Silence windows must not be scored as slurred."""
    frame = int(sr * frame_ms / 1000)
    if samples.size < frame:
        return []
    n = samples.size // frame
    frames = samples[: n * frame].reshape(n, frame)
    frame_rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    speech = frame_rms > threshold
    min_speech = int(min_speech_s * 1000 / frame_ms)
    min_silence = int(min_silence_s * 1000 / frame_ms)

    segments: list[tuple[int, int]] = []
    start = None
    silence_run = 0
    for i, is_sp in enumerate(speech):
        if is_sp and start is None:
            start = i
            silence_run = 0
        elif is_sp and start is not None:
            silence_run = 0
        elif not is_sp and start is not None:
            silence_run += 1
            if silence_run >= min_silence:
                if i - start >= min_speech:
                    segments.append((start * frame, i * frame))
                start = None
                silence_run = 0
    if start is not None and (len(samples) - start * frame) >= min_speech * frame:
        segments.append((start * frame, len(samples)))
    return segments


def speech_fraction(x: np.ndarray, sr: int = 16000) -> float:
    if x.size == 0:
        return 0.0
    segs = speech_segments(x, sr, threshold=RMS_MIN, min_speech_s=0.2, min_silence_s=0.25)
    voiced = sum(e - s for s, e in segs)
    return float(voiced) / float(x.size)


def select_windows(x: np.ndarray) -> list[np.ndarray]:
    """Speech-bearing windows. Never zero-pad silence onto a short clip."""
    if x.size < MIN_SAMPLES:
        return []
    if rms(x) < RMS_MIN:
        return []

    if x.size <= WINDOW_SAMPLES:
        return [x] if speech_fraction(x) >= SPEECH_FRACTION_MIN or rms(x) >= RMS_MIN else []

    out: list[np.ndarray] = []
    start = 0
    while start + MIN_SAMPLES <= x.size and len(out) < MAX_WINDOWS:
        end = min(start + WINDOW_SAMPLES, x.size)
        w = x[start:end]
        if rms(w) >= RMS_MIN and speech_fraction(w) >= SPEECH_FRACTION_MIN:
            out.append(w)
        if end >= x.size:
            break
        start += HOP_SAMPLES
    return out


def pick_personal(z: np.ndarray, user: np.ndarray | None, healthy: np.ndarray | None) -> bool:
    """Use personal scoring only when the clip is closer to the enrolled user than to corpus healthy.

    Phone-mic of the enrolled speaker sits near `user`. TORGO / bundled demo clips sit
    near `healthy` (or elsewhere in-corpus) and must keep the corpus LR — personal
    centroid-shift otherwise cancels real slur (synthetic slurred.wav: 0.997 → 0.000).
    """
    if user is None or healthy is None:
        return False
    d_user = float(np.linalg.norm(z - user))
    d_healthy = float(np.linalg.norm(z - healthy))
    return d_user < d_healthy


class SlurServer:
    """Server-side slur classifier: WavLM embeddings + SSL LR.

    Two operating modes, chosen per clip (not just by enrollment):
      - corpus  : trained SSL-only LR on raw WavLM embeddings (cross-sectional).
      - personal: domain-shifted scoring for the enrolled user.
                  z' = z - user_centroid + healthy_centroid, then the same LR.

    Personal is used only when a profile exists AND the embedding is closer to the
    user centroid than to the corpus healthy centroid. That keeps phone-mic
    live speech in personal mode (killing the OOD false-positive) while bundled
    / in-domain clips keep the corpus score (so slur is not shifted away).
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
        self._personal_threshold = float(
            self._params.get("personal_threshold", DEFAULT_PERSONAL_THRESHOLD)
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
        thr = float(self._params["threshold"])
        out = {
            "score": 0.0,
            "score_corpus": 0.0,
            "score_personal": 0.0,
            "score_cal": 0.0,
            "mode": "corpus",
            "enrolled": self._centroid is not None,
            "ood": 0.0,
            "temperature": round(self._temperature, 3),
            "threshold": thr,
            "detected": False,
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
            self._centroid = embs.mean(axis=0).astype(np.float32)
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
            }

    def analyze(self, body: bytes, mode_override: str | None = None) -> dict:
        with self._lock:
            x = wav_to_pcm(body)
            if x.size == 0:
                return self._empty("bad wav")
            r = rms(x)
            frac = speech_fraction(x)
            if r < RMS_MIN or frac < SPEECH_FRACTION_MIN:
                log.info("slur analyze gated: rms=%.4f speech_frac=%.2f", r, frac)
                return self._empty(
                    "silence",
                    extra={"speech_rms": round(r, 4), "speech_fraction": round(frac, 3)},
                )

            wins = select_windows(x)
            if not wins:
                return self._empty(
                    "no speech windows",
                    extra={"speech_rms": round(r, 4), "speech_fraction": round(frac, 3)},
                )

            self._ensure()
            self._refresh_profile()

            zs = [self._standardize(self._embed(w)) for w in wins]
            z_mean = np.mean(np.stack(zs), axis=0)

            enrolled = self._centroid is not None and self._healthy_centroid is not None
            override = (mode_override or "").strip().lower()
            if override == "corpus":
                use_personal = False
            elif override == "personal":
                use_personal = enrolled
            else:
                use_personal = enrolled and pick_personal(
                    z_mean, self._centroid, self._healthy_centroid
                )

            corpus_scores = []
            personal_scores = []
            oods = []
            for z in zs:
                sc, ood = self._corpus_score(z)
                corpus_scores.append(sc)
                oods.append(ood)
                if enrolled:
                    zp = z - self._centroid + self._healthy_centroid
                    sp, _ = self._corpus_score(zp)
                    personal_scores.append(sp)
                else:
                    personal_scores.append(sc)

            score_corpus = float(np.max(corpus_scores))
            score_personal = float(np.max(personal_scores))
            ood = float(np.mean(oods))
            score = score_personal if use_personal else score_corpus

            if use_personal and enrolled:
                zp_mean = z_mean - self._centroid + self._healthy_centroid
                score_cal, _ = self._corpus_score(zp_mean, self._temperature)
                thr = self._personal_threshold
            else:
                score_cal, _ = self._corpus_score(z_mean, self._temperature)
                thr = float(self._params["threshold"])

            d_user = (
                float(np.linalg.norm(z_mean - self._centroid)) if self._centroid is not None else None
            )
            d_healthy = (
                float(np.linalg.norm(z_mean - self._healthy_centroid))
                if self._healthy_centroid is not None
                else None
            )
            detected = bool(score >= thr)
            log.info(
                "slur analyze: mode=%s score=%.3f corpus=%.3f personal=%.3f ood=%.2f "
                "d_user=%s d_healthy=%s thr=%.3f rms=%.4f frac=%.2f wins=%d detected=%s",
                "personal" if use_personal else "corpus",
                score,
                score_corpus,
                score_personal,
                ood,
                f"{d_user:.1f}" if d_user is not None else "-",
                f"{d_healthy:.1f}" if d_healthy is not None else "-",
                thr,
                r,
                frac,
                len(wins),
                detected,
            )
            return {
                "score": round(score, 4),
                "score_corpus": round(score_corpus, 4),
                "score_personal": round(score_personal, 4),
                "score_cal": round(score_cal, 4),
                "mode": "personal" if use_personal else "corpus",
                "enrolled": enrolled,
                "ood": round(ood, 3),
                "temperature": round(self._temperature, 3),
                "threshold": thr,
                "detected": detected,
                "reason": "ok",
                "speech_rms": round(r, 4),
                "speech_fraction": round(frac, 3),
                "windows": len(wins),
                "d_user": round(d_user, 3) if d_user is not None else None,
                "d_healthy": round(d_healthy, 3) if d_healthy is not None else None,
            }
