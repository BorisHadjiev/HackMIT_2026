"""Python port of the app's SlurDetector (z-score + EWMA/CUSUM) and baseline builder.

Mirrors app/src/main/java/com/hackmit/app/audio/SlurDetector.kt and
BaselineStore.fromSamples so the offline validation exercises the real algorithm.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

from features import FEATURE_KEYS

MIN_BASELINE_SAMPLES = 5
ALERT_COOLDOWN_MS = 300_000
EWMA_ALPHA = 0.3
MAX_Z = 4.0
SCORE_Z_AT_MAX = 2.5

MIN_STD = {
    "jitter": 0.01,
    "shimmer": 0.03,
    "hnr": 3.0,
    "confidence": 0.05,
    "pauseRatio": 0.05,
    "wpm": 12.0,
    "ems4hz": 0.02,
    "f0Std": 5.0,
}

# key, weight, direction (HIGH=abnormal high, LOW=abnormal low, TWO=either)
SPECS = [
    ("jitter", 1.6, "HIGH"),
    ("shimmer", 1.6, "HIGH"),
    ("hnr", 1.2, "LOW"),
    ("confidence", 1.6, "LOW"),
    ("pauseRatio", 1.0, "HIGH"),
    ("wpm", 0.8, "TWO"),
    ("ems4hz", 0.6, "TWO"),
    ("f0Std", 0.5, "TWO"),
]


@dataclass
class Baseline:
    sample_count: int
    means: dict[str, float]
    stds: dict[str, float]


def build_baseline(samples: list[dict[str, float]]) -> Baseline:
    if not samples:
        return Baseline(0, {}, {})
    means: dict[str, float] = {}
    stds: dict[str, float] = {}
    for key in FEATURE_KEYS:
        values = [s.get(key, 0.0) for s in samples]
        mean = sum(values) / len(values)
        var = sum((v - mean) ** 2 for v in values) / len(values)
        means[key] = mean
        stds[key] = math.sqrt(var)
    return Baseline(len(samples), means, stds)


class SlurDetector:
    def __init__(self, baseline: Baseline | None, sensitivity: float = 0.55) -> None:
        self.baseline = baseline
        self.sensitivity = sensitivity
        self.warn_threshold = 0.0
        self.alert_threshold = 0.0
        self.ewma = 0.0
        self.cusum = 0.0
        self.above_count = 0
        self._recompute_thresholds()

    def _recompute_thresholds(self) -> None:
        self.warn_threshold = min(0.7, max(0.2, 0.75 - 0.5 * self.sensitivity))
        self.alert_threshold = min(0.92, max(0.4, 1.0 - 0.5 * self.sensitivity))

    def update(self, features: dict[str, float]) -> dict:
        baseline = self.baseline
        if baseline is None or baseline.sample_count < MIN_BASELINE_SAMPLES:
            return {"score": 0.0, "level": "NORMAL", "reasons": ["No baseline yet"]}

        weighted = 0.0
        weight_sum = 0.0
        contributions: list[tuple[str, float]] = []
        for key, weight, direction in SPECS:
            if key not in features:
                continue
            mean = baseline.means.get(key)
            std = baseline.stds.get(key)
            if mean is None or std is None:
                continue
            std = max(std, MIN_STD.get(key, 1.0))
            z = (features[key] - mean) / std if std else 0.0
            if not math.isfinite(z):
                continue
            directed = z if direction == "HIGH" else (-z if direction == "LOW" else abs(z))
            positive = min(max(directed, 0.0), MAX_Z)
            weighted += positive * weight
            weight_sum += weight
            contributions.append((key, positive))

        if weight_sum == 0:
            return {"score": 0.0, "level": "NORMAL", "reasons": ["Baseline incomplete"]}

        z_mean = weighted / weight_sum
        raw = min(max(z_mean / SCORE_Z_AT_MAX, 0.0), 1.0)
        self.ewma = EWMA_ALPHA * raw + (1 - EWMA_ALPHA) * self.ewma
        self.cusum = max(self.cusum + (self.ewma - self.warn_threshold), 0.0)
        self.above_count = self.above_count + 1 if self.ewma >= self.warn_threshold else 0

        level = "NORMAL"
        if self.ewma >= self.alert_threshold and self.above_count >= 2:
            level = "ALERT"
            self.cusum = 0.0
            self.above_count = 0
        elif self.ewma >= self.warn_threshold:
            level = "WARNING"

        reasons = [
            key for key, z in sorted(contributions, key=lambda kv: -kv[1])[:3] if z >= 1.0
        ] or ["Speech pattern within baseline"]

        return {"score": float(self.ewma), "level": level, "reasons": reasons, "raw": raw}


def reset_state(detector: SlurDetector) -> None:
    detector.ewma = 0.0
    detector.cusum = 0.0
    detector.above_count = 0