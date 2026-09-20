#!/usr/bin/env python3
"""Download public dysarthria datasets (no HF account needed) for validation.

Writes WAV files to ./wav/<dataset>/... plus a manifest CSV so the eval and ASR
comparison can iterate over labeled audio deterministically.
"""
from __future__ import annotations

import csv
import os
import sys

import numpy as np
import soundfile as sf
from datasets import load_dataset
from tqdm import tqdm

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wav")
MANIFEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "manifest.csv")
SR = 16000

DATASETS = [
    {
        "name": "torgo",
        "path": "abnerh/TORGO-database",
        "split": "train",
        "audio": "audio",
        "text": "transcription",
        "label": "speech_status",  # healthy | dysarthria
        "severity": None,
        "gender": "gender",
        "speaker": None,  # derived from original filename for TORGO
        "max_per_label": 2000,
    },
    {
        "name": "pathological",
        "path": "resproj007/pathological_speech",
        "split": "train",
        "audio": "audio",
        "text": "text",
        "label": "condition",  # Dysarthric | Control
        "severity": "severity",  # Normal | Mild | Moderate | Severe
        "gender": "gender",
        "speaker": "speaker_id",
        "max_per_label": 2000,
    },
]


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    existing = set()
    if os.path.exists(MANIFEST):
        with open(MANIFEST, newline="") as f:
            for row in csv.DictReader(f):
                existing.add((row["dataset"], row["path"]))

    with open(MANIFEST, "a", newline="") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=[
                "dataset", "path", "text", "label", "severity", "gender", "speaker", "duration_s",
            ],
        )
        if not existing:
            writer.writeheader()

        for ds in DATASETS:
            print(f"loading {ds['path']} ...", flush=True)
            data = load_dataset(ds["path"], split=ds["split"])
            written = 0
            counts: dict[str, int] = {}
            for i, row in enumerate(tqdm(data, total=len(data))):
                label = str(row.get(ds["label"], "") or "").lower()
                if counts.get(label, 0) >= ds["max_per_label"]:
                    continue
                aud = row[ds["audio"]]
                arr = np.asarray(aud["array"], dtype=np.float32)
                # Normalize sample rate (TORGO is already 16k).
                if aud["sampling_rate"] != SR:
                    import librosa

                    arr = librosa.resample(arr, orig_sr=aud["sampling_rate"], target_sr=SR)
                fname = f"{ds['name']}_{i:05d}.wav"
                rel = os.path.join(ds["name"], fname)
                key = (ds["name"], rel)
                if key not in existing:
                    os.makedirs(os.path.dirname(os.path.join(OUT, rel)), exist_ok=True)
                    sf.write(os.path.join(OUT, rel), arr, SR)
                    existing.add(key)
                    counts[label] = counts.get(label, 0) + 1
                    written += 1
                    severity = row.get(ds["severity"]) if ds["severity"] else ""
                    speaker = row.get(ds["speaker"]) if ds["speaker"] else ""
                    if not speaker:
                        # TORGO filenames encode speaker as the first token (e.g. FC01_...).
                        speaker = str(aud.get("path", "")).split("_")[0]
                    writer.writerow(
                        {
                            "dataset": ds["name"],
                            "path": rel,
                            "text": str(row.get(ds["text"], "") or ""),
                            "label": str(row.get(ds["label"], "") or "").lower(),
                            "severity": str(severity or "").lower(),
                            "gender": str(row.get(ds["gender"], "") or ""),
                            "speaker": str(speaker or ""),
                            "duration_s": f"{len(arr) / SR:.2f}",
                        }
                    )
                    written += 1
            print(f"  {ds['name']}: wrote {written} files", flush=True)
    print(f"manifest: {MANIFEST}")


if __name__ == "__main__":
    sys.exit(main())