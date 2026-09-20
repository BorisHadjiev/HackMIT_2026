"""Gating/window logic of the WavLM-only slur detector (no torch needed)."""
from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from gateway import slur  # noqa: E402


def test_silence_is_never_scored():
    x = np.zeros(16_000 * 4, dtype=np.float32)
    assert slur.rms(x) == 0.0
    assert slur.speech_fraction(x) == 0.0
    assert slur.select_windows(x) == []


def test_speech_fraction_counts_speech():
    x = np.zeros(16_000 * 4, dtype=np.float32)
    x[16_000 : 16_000 * 3] = 0.1  # 2 s of "speech"
    assert slur.speech_fraction(x) > 0.4
    wins = slur.select_windows(x)
    assert wins and all(w.size >= slur.MIN_SAMPLES for w in wins)


def test_short_clip_returned_whole():
    x = np.full(16_000 * 2, 0.05, dtype=np.float32)
    wins = slur.select_windows(x)
    assert len(wins) == 1 and wins[0].size == x.size


def test_windows_are_capped():
    x = np.full(16_000 * 30, 0.05, dtype=np.float32)
    wins = slur.select_windows(x)
    assert 1 <= len(wins) <= slur.MAX_WINDOWS


def test_balanced_defaults():
    assert slur.DEFAULT_PERSONAL_THRESHOLD == 0.40
    assert slur.DEFAULT_OOD_MAX > 1.0
