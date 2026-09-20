#!/usr/bin/env python3
"""Compare local whisper.cpp vs Deepgram on TORGO clips (WER + latency).

Runs whisper-cli (built on gx10, CUDA) and the Deepgram prerecorded API on the
same clips and reports WER against the reference transcripts.
"""
from __future__ import annotations

import csv
import json
import os
import subprocess
import sys
import time
import urllib.request

import jiwer

HERE = os.path.dirname(os.path.abspath(__file__))
WHISPER = os.path.expanduser("~/whisper/whisper.cpp/build/bin/whisper-cli")
MODEL = os.path.expanduser("~/whisper/whisper.cpp/models/ggml-large-v3-turbo.bin")
DEEPGRAM_KEY = os.environ["DEEPGRAM_API_KEY"]


def rows(n: int = 12):
    out = []
    with open(os.path.join(HERE, "manifest.csv"), newline="") as f:
        for r in csv.DictReader(f):
            if r["dataset"] == "torgo" and len(r["text"].split()) >= 4:
                out.append(r)
            if len(out) >= n:
                break
    return out


def whisper_text(path: str) -> str:
    t0 = time.time()
    subprocess.run(
        [WHISPER, "-m", MODEL, "-f", path, "-l", "en", "-otxt", "-of", "/tmp/wcmp", "--no-timestamps", "-nt"],
        check=True, capture_output=True,
    )
    lat = time.time() - t0
    return open("/tmp/wcmp.txt").read().strip().lower(), lat


def deepgram_text(path: str) -> tuple[str, float]:
    with open(path, "rb") as f:
        data = f.read()
    req = urllib.request.Request(
        "https://api.deepgram.com/v1/listen?model=nova-3&punctuate=false",
        data=data,
        headers={"Authorization": f"Token {DEEPGRAM_KEY}", "Content-Type": "audio/wav"},
    )
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.load(resp)
    lat = time.time() - t0
    return body["results"]["channels"][0]["alternatives"][0]["transcript"].strip().lower(), lat


def main() -> None:
    print(f"{'clip':<28}{'whisper_WER':>12}{'whisper_s':>10}{'dg_WER':>9}{'dg_s':>7}")
    for r in rows(12):
        path = os.path.join(HERE, "wav", r["path"])
        ref = r["text"].lower()
        wt, wl = whisper_text(path)
        dt, dl = deepgram_text(path)
        w_wer = jiwer.wer(ref, wt)
        d_wer = jiwer.wer(ref, dt)
        print(f"{r['path'][:27]:<28}{w_wer:>12.3f}{wl:>10.2f}{d_wer:>9.3f}{dl:>7.2f}")


if __name__ == "__main__":
    sys.exit(main())