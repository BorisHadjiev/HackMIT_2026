#!/usr/bin/env python3
"""Synthesize acute-style slurred speech from a healthy clip.

Approximates articulation slurring by slowing speech (time-stretch) while
restoring pitch, then low-pass filtering to reduce consonant clarity — a
stroke-like acute proxy, used for the in-app demo and supplementary eval.
"""
from __future__ import annotations

import argparse
import os
import sys

import librosa
import numpy as np
import soundfile as sf

SR = 16000


def slur(y: np.ndarray, sr: int, rate: float = 0.72, lowpass_hz: float = 3200.0) -> np.ndarray:
    stretched = librosa.effects.time_stretch(y, rate=rate)
    # Restore pitch: slowing by `rate` drops pitch by 12*log2(1/rate) semitones.
    n_steps = 12.0 * np.log2(1.0 / rate)
    shifted = librosa.effects.pitch_shift(stretched, sr=sr, n_steps=float(n_steps))
    return librosa.effects.preemphasis(shifted, coef=0.0)  # no-op, keep API shape
    # (real lowpass below)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--rate", type=float, default=0.72)
    ap.add_argument("--lowpass", type=float, default=3200.0)
    args = ap.parse_args()

    y, sr = librosa.load(args.input, sr=SR)
    out = slur(y, sr, rate=args.rate)
    # low-pass to smear consonants (must run after pitch restoration)
    out = librosa.effects.preemphasis(out, coef=0.97)
    from scipy.signal import butter, sosfilt

    sos = butter(4, args.lowpass, fs=sr, output="sos")
    out = sosfilt(sos, out)
    sf.write(args.output, out.astype(np.float32), SR)
    print(f"wrote {args.output} ({len(out) / SR:.1f}s)")


if __name__ == "__main__":
    sys.exit(main())