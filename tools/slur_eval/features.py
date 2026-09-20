"""Windowed feature extraction matching the app's on-device Dsp (Dsp.kt).

Faithful Python port of the app's autocorrelation pitch, peak jitter/shimmer,
f0 std, envelope-modulation and pause ratio, so the offline validation exercises
the exact same algorithm the phone runs.
"""
from __future__ import annotations

import numpy as np

SR = 16000
WINDOW = 2.0
HOP = 2.0

FEATURE_KEYS = [
    "f0Mean", "f0Std", "jitter", "shimmer", "hnr",
    "rms", "zcr", "centroid", "ems4hz",
    "wpm", "confidence", "pauseRatio", "fillerRatio",
]

VAD_FRAME = 512  # samples (32 ms)
VAD_FLOOR = 0.004


def _rms(x: np.ndarray) -> float:
    if x.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(x.astype(np.float64) ** 2)))


def _zcr(x: np.ndarray) -> float:
    if x.size < 2:
        return 0.0
    return float(np.mean(np.abs(np.diff(np.sign(x)))) / 2)


def _pitch(x: np.ndarray, sr: int):
    """Port of Dsp.pitch (autocorrelation 60-500 Hz, first strong local max).

    Uses FFT autocorrelation for speed; the selection logic matches the app.
    """
    n = x.size
    if n < sr // 25:
        return 0.0, 0.0, False
    x = x - x.mean()
    r0 = float(np.dot(x, x))
    if r0 <= 1e-9:
        return 0.0, 0.0, False
    min_lag = max(int(sr / 500.0), 2)
    max_lag = min(int(sr / 60.0), n - 2)
    if max_lag <= min_lag + 1:
        return 0.0, 0.0, False
    # FFT autocorrelation (raw sums), then normalize by n-lag.
    xf = np.fft.rfft(x, n=n * 2)
    ac = np.fft.irfft(xf * np.conj(xf))[:n]
    lags = np.arange(min_lag, max_lag + 1)
    r = ac[lags] / (n - lags)
    power = r0 / n
    target = 0.5 * power
    chosen = -1
    for i in range(1, len(r) - 1):
        if r[i] > target and r[i] >= r[i - 1] and r[i] >= r[i + 1]:
            chosen = i
            break
    if chosen < 0:
        chosen = int(np.argmax(r))
    chosen = min(max(chosen, 1), len(r) - 2)
    best_r = r[chosen]
    norm = min(max(best_r / power, 0.0), 1.0)
    if norm < 0.3:
        return 0.0, 0.0, False
    refined = lags[chosen]
    ym, y0, yp = r[chosen - 1], r[chosen], r[chosen + 1]
    denom = ym - 2 * y0 + yp
    if abs(denom) > 1e-12:
        refined += 0.5 * (ym - yp) / denom
    f0 = sr / refined
    hnr = 10.0 * np.log10(norm / max(1.0 - norm, 1e-6))
    return f0, float(min(max(hnr, -20.0), 40.0)), True


def _f0_std(x: np.ndarray, sr: int) -> float:
    frame = sr * 40 // 1000
    if x.size < frame * 2:
        return 0.0
    values = []
    start = 0
    while start + frame <= x.size:
        f0, _, voiced = _pitch(x[start:start + frame], sr)
        if voiced:
            values.append(f0)
        start += frame // 2
    if len(values) < 2:
        return 0.0
    return float(np.std(values))


def _jitter_shimmer(x: np.ndarray, sr: int, f0: float):
    if f0 <= 0:
        return 0.0, 0.0
    period = int(sr / f0)
    if period < 2:
        return 0.0, 0.0
    peaks, amps = [], []
    i = 0
    while i < x.size:
        end = min(x.size, i + period)
        seg = x[i:end]
        idx = int(np.argmax(seg))
        mv = float(seg[idx])
        if mv > 1e-4:
            peaks.append(i + idx)
            amps.append(mv)
        i += period
    if len(peaks) < 3:
        return 0.0, 0.0
    periods = np.diff(np.asarray(peaks))
    if periods.size < 2:
        return 0.0, 0.0
    jitter = float(np.mean(np.abs(np.diff(periods))) / periods.mean())
    shimmer = float(np.mean(np.abs(np.diff(np.asarray(amps)))) / np.mean(amps))
    return jitter, shimmer


def _envelope_modulation_4hz(x: np.ndarray, sr: int) -> float:
    if x.size < sr // 2:
        return 0.0
    env_rate = 200
    step = max(1, sr // env_rate)
    n_env = x.size // step
    env = np.mean(np.abs(x[: n_env * step].reshape(n_env, step)), axis=1)
    n = 1 << (env.size - 1).bit_length()
    if env.size != n:
        env = np.pad(env, (0, n - env.size))
    if n < 32:
        return 0.0
    spec = np.fft.rfft(env - env.mean())
    power = np.abs(spec) ** 2
    freqs = np.fft.rfftfreq(n, d=1.0 / env_rate)
    total = power.sum()
    if total <= 0:
        return 0.0
    band = power[(freqs >= 3.0) & (freqs <= 5.0)].sum()
    return float(band / total)


def _pause_ratio(x: np.ndarray) -> float:
    """Adaptive energy VAD: speech = frame RMS > 1.5x the clip's median RMS."""
    if x.size == 0:
        return 1.0
    frames = x[: (x.size // VAD_FRAME) * VAD_FRAME].reshape(-1, VAD_FRAME)
    if frames.size == 0:
        return 1.0
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    thresh = max(1.5 * float(np.median(rms)), VAD_FLOOR)
    return float(1.0 - np.mean(rms > thresh))


def extract_windows(y: np.ndarray, sr: int, wpm: float = 0.0,
                    confidence: float = 1.0) -> list[dict[str, float]]:
    if y.size < sr // 2:
        return []
    window_len = int(sr * WINDOW)
    hop_len = int(sr * HOP)
    out: list[dict[str, float]] = []
    for start in range(0, y.size - window_len + 1, hop_len):
        win = y[start:start + window_len]

        # Voiced-gated 40 ms sub-frames (matches AcousticFeatureExtractor.compute).
        frame = sr * 40 // 1000
        f0s, hnrs, voiced = [], [], []
        s0 = 0
        while s0 + frame <= win.size:
            f0, h, v = _pitch(win[s0:s0 + frame], sr)
            if v:
                f0s.append(f0)
                hnrs.append(h)
                voiced.append(win[s0:s0 + frame])
            s0 += frame // 2

        rms = _rms(win)
        zcr = _zcr(win)
        ems4hz = _envelope_modulation_4hz(win, sr)
        if len(f0s) < 2:
            out.append({
                "f0Mean": 0.0, "f0Std": 0.0, "jitter": 0.0, "shimmer": 0.0, "hnr": 0.0,
                "rms": rms, "zcr": zcr, "centroid": 0.0, "ems4hz": ems4hz,
                "wpm": wpm, "confidence": confidence, "pauseRatio": _pause_ratio(win), "fillerRatio": 0.0,
            })
            continue

        f0_mean = float(np.mean(f0s))
        f0_std = float(np.std(f0s))
        hnr = float(np.mean(hnrs))
        jitters, shimmers = [], []
        for sub, f0 in zip(voiced, f0s):
            j, s = _jitter_shimmer(sub, sr, f0)
            if j > 0 and s > 0:
                jitters.append(j)
                shimmers.append(s)
        jitter = float(np.mean(jitters)) if jitters else 0.0
        shimmer = float(np.mean(shimmers)) if shimmers else 0.0

        out.append({
            "f0Mean": f0_mean,
            "f0Std": f0_std,
            "jitter": jitter,
            "shimmer": shimmer,
            "hnr": hnr,
            "rms": rms,
            "zcr": zcr,
            "centroid": 0.0,
            "ems4hz": ems4hz,
            "wpm": wpm,
            "confidence": confidence,
            "pauseRatio": _pause_ratio(win),
            "fillerRatio": 0.0,
        })
    return out


def clip_wpm(text: str, duration_s: float) -> float:
    words = len(text.split())
    if duration_s <= 0 or words == 0:
        return 0.0
    return float(words / (duration_s / 60.0))