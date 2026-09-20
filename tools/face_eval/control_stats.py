#!/usr/bin/env python3
"""Score an unlabelled (healthy) face folder to measure false-positive rates.

Reports the distribution of the corrected asymmetry features and the fraction of
faces exceeding the app's severity thresholds. Reuses features_v2.py.
"""
import argparse
import json
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from tqdm import tqdm

from features_v2 import build, feats

MOUTH_FULL = 0.10
CHEEK_FULL = 0.08
EYE_FULL = 0.25


def severity(f):
    return (0.70 * min(f["mouth_perp_abs"] / MOUTH_FULL, 1.0)
            + 0.20 * min(f["cheek_perp_abs"] / CHEEK_FULL, 1.0)
            + 0.10 * min(f["eye_open_asym"] / EYE_FULL, 1.0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--model", default=str(Path.home() / "face_eval/face_landmarker.task"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    root = Path(args.dataset)
    imgs = [p for ext in ("*.jpg", "*.jpeg", "*.png", "*.bmp") for p in root.rglob(ext)]
    print(f"{root.name}: {len(imgs)} images")

    lmk = build(args.model)
    detected = []
    for p in tqdm(imgs, desc=root.name):
        img = cv2.imread(str(p))
        if img is None:
            continue
        rgb = np.ascontiguousarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        res = lmk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        if not res.face_landmarks:
            continue
        lm = np.array([[q.x, q.y] for q in res.face_landmarks[0]])
        detected.append(feats(lm))

    report = {"dataset": str(root), "n_total": len(imgs), "n_detected": len(detected),
              "detection_rate": len(detected) / max(1, len(imgs))}
    sev = np.array([severity(f) for f in detected])
    report["severity"] = {
        "mean": float(sev.mean()), "p50": float(np.percentile(sev, 50)),
        "p90": float(np.percentile(sev, 90)), "p95": float(np.percentile(sev, 95)),
        "p99": float(np.percentile(sev, 99)),
        "frac_gt_0.33": float((sev > 0.33).mean()),
        "frac_gt_0.50": float((sev > 0.50).mean()),
    }
    for k in ("mouth_perp_abs", "eye_open_asym", "cheek_perp_abs", "brow_perp_abs"):
        x = np.array([f[k] for f in detected])
        report[k] = {
            "mean": float(x.mean()), "p50": float(np.percentile(x, 50)),
            "p90": float(np.percentile(x, 90)), "p95": float(np.percentile(x, 95)),
            "p99": float(np.percentile(x, 99)),
            "frac_gt_0.02": float((x > 0.02).mean()),
            "frac_gt_0.05": float((x > 0.05).mean()),
        }
    text = json.dumps(report, indent=2)
    if args.out:
        Path(args.out).write_text(text)
    print(text)


if __name__ == "__main__":
    main()
