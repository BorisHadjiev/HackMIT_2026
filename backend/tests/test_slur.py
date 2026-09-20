"""Unit tests for slur scoring helpers (no torch / WavLM required)."""
from __future__ import annotations

import io
import os
import sys
import wave

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from gateway.slur import (  # noqa: E402
    DEFAULT_PERSONAL_THRESHOLD,
    RMS_MIN,
    SlurServer,
    pick_personal,
    rms,
    select_windows,
    speech_fraction,
)


HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(
    os.path.dirname(HERE), "..", "tools", "slur_eval", "slur_classifier_ssl_pathological.json"
)
MODEL = os.path.normpath(MODEL)


def _pcm_wav(samples: np.ndarray, sr: int = 16000) -> bytes:
    x = np.clip(samples, -1.0, 1.0)
    pcm = (x * 32767.0).astype(np.int16).tobytes()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm)
    return buf.getvalue()


def test_rms_silence_and_tone():
    silence = np.zeros(16000, dtype=np.float32)
    tone = 0.1 * np.sin(2 * np.pi * 180 * np.arange(16000) / 16000).astype(np.float32)
    assert rms(silence) < 1e-6
    assert rms(tone) > RMS_MIN


def test_select_windows_drops_silence():
    silence = np.zeros(16000 * 4, dtype=np.float32)
    assert select_windows(silence) == []
    assert speech_fraction(silence) == 0.0


def test_select_windows_keeps_speech():
    t = np.arange(16000 * 4) / 16000
    speech = (0.08 * np.sin(2 * np.pi * 140 * t)).astype(np.float32)
    wins = select_windows(speech)
    assert len(wins) == 1
    assert wins[0].size == speech.size


def test_pick_personal_prefers_nearer_centroid():
    healthy = np.zeros(8, dtype=np.float32)
    user = np.ones(8, dtype=np.float32) * 4
    near_user = np.ones(8, dtype=np.float32) * 3.8
    near_healthy = np.ones(8, dtype=np.float32) * 0.2
    assert pick_personal(near_user, user, healthy) is True
    assert pick_personal(near_healthy, user, healthy) is False
    assert pick_personal(near_healthy, None, healthy) is False


def test_silence_wav_is_not_slurred():
    if not os.path.exists(MODEL):
        pytest.skip("slur model json not in repo")
    server = SlurServer(MODEL, profile_path="")
    wav = _pcm_wav(np.zeros(16000 * 4, dtype=np.float32))
    out = server.analyze(wav)
    assert out["detected"] is False
    assert out["score"] == 0.0
    assert out["reason"] == "silence"
    assert out["windows"] == 0


def test_personal_threshold_default():
    if not os.path.exists(MODEL):
        pytest.skip("slur model json not in repo")
    server = SlurServer(MODEL, profile_path="")
    assert server._personal_threshold == DEFAULT_PERSONAL_THRESHOLD
    st = server.status()
    assert st["mode"] == "corpus"
    assert st["personal_threshold"] == DEFAULT_PERSONAL_THRESHOLD
