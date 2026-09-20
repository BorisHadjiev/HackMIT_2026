#!/usr/bin/env python3
"""Extract SSL speech embeddings (WavLM / wav2vec2 / HuBERT) for every clip.

Run with the gateway venv (has torch/transformers/torchaudio). Writes
ssl_emb.npz: paths, embeddings (mean+std pooled last hidden state), model tag.
"""
from __future__ import annotations

import csv
import os
import sys
import wave

import numpy as np
import torch
import torchaudio.functional as TAF
from transformers import AutoFeatureExtractor, AutoModel

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "manifest.csv")
OUT = os.path.join(HERE, "ssl_emb.npz")
MIN_SAMPLES = 8000  # 0.5 s @ 16 kHz
BATCH = 1


def load_wav(path: str) -> np.ndarray:
    """Read a PCM WAV with the stdlib (no torchaudio/torchcodec dependency)."""
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        data = w.readframes(w.getnframes())
    x = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    if nch > 1:
        x = x.reshape(-1, nch).mean(axis=1)
    if sr != 16000:
        t = torch.from_numpy(x)
        x = TAF.resample(t, sr, 16000).numpy()
    return x


def main() -> None:
    model_id = os.environ.get("SSL_MODEL", "microsoft/wavlm-base-plus")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"model={model_id} device={device}", flush=True)

    rows = list(csv.DictReader(open(MANIFEST)))
    print(f"rows={len(rows)}", flush=True)

    feat = AutoFeatureExtractor.from_pretrained(model_id)
    model = AutoModel.from_pretrained(model_id).to(device).eval()

    raw: list[np.ndarray] = []
    paths: list[str] = []
    skipped = 0
    for r in rows:
        path = os.path.join(HERE, "wav", r["path"])
        try:
            arr = load_wav(path)
        except Exception:
            skipped += 1
            continue
        if arr.size == 0:
            skipped += 1
            continue
        if arr.size < MIN_SAMPLES:
            arr = np.pad(arr, (0, MIN_SAMPLES - arr.size))
        paths.append(r["path"])
        raw.append(arr)

    print(f"extracting embeddings for {len(raw)} clips (skipped {skipped})", flush=True)
    vecs = []
    with torch.no_grad():
        for i, arr in enumerate(raw):
            inputs = feat([arr], sampling_rate=16000, return_tensors="pt")
            out = model(inputs["input_values"].to(device)).last_hidden_state  # (1, T, D)
            o = out[0]
            mean = o.mean(0)
            std = o.std(0)
            vecs.append(torch.cat([mean, std], dim=0).cpu().numpy())
            if i % 200 == 0:
                print(f"  {i}/{len(raw)}", flush=True)

    V = np.vstack(vecs)
    np.savez(OUT, paths=np.array(paths), emb=V, model=model_id)
    print(f"saved {OUT} shape={V.shape}", flush=True)


if __name__ == "__main__":
    sys.exit(main())