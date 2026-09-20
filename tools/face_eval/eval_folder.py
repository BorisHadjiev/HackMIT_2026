#!/usr/bin/env python3
"""Evaluate the corrected facial-asymmetry geometry on any folder-labelled dataset.

Expects <dataset>/<class>/*.jpg. Classes containing "affected", "stroke" or
"palsy" (but not "unaffected"/"nonstroke"/"healthy"/"normal") are the positive
class. Reuses the landmarker and feature definitions from features_v2.py.
"""
import argparse
import json
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from tqdm import tqdm

from features_v2 import build, cv_auc, feats, auc_stats

POSITIVE_HINTS = ("affected", "stroke", "palsy", "paralysis")
NEGATIVE_HINTS = ("unaffected", "nonstroke", "non_stroke", "nostroke", "no_stroke",
                  "healthy", "normal", "control")


def label_for(name: str) -> int:
    n = name.lower()
    if any(h in n for h in NEGATIVE_HINTS):
        return 0
    if any(h in n for h in POSITIVE_HINTS):
        return 1
    raise ValueError(f"cannot infer label for class dir {name!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--model", default=str(Path.home() / "face_eval/face_landmarker.task"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    ds = Path(args.dataset)
    class_dirs = [d for d in sorted(ds.iterdir()) if d.is_dir()]
    files = []
    for d in class_dirs:
        label = label_for(d.name)
        imgs = [p for ext in ("*.jpg", "*.jpeg", "*.png", "*.bmp") for p in d.rglob(ext)]
        files += [(p, label, d.name) for p in sorted(imgs)]
    print(f"{ds.name}: {len(files)} images across {[d.name for d in class_dirs]}")

    lmk = build(args.model)
    rows = []
    for jpg, label, cls in tqdm(files, desc=ds.name):
        img = cv2.imread(str(jpg))
        if img is None:
            continue
        rgb = np.ascontiguousarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        res = lmk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        if not res.face_landmarks:
            continue
        lm = np.array([[p.x, p.y] for p in res.face_landmarks[0]])
        rows.append({"label": label, "class": cls, **feats(lm)})

    y = np.array([r["label"] for r in rows])
    report = {
        "dataset": str(ds),
        "n": len(rows),
        "n_positive": int((y == 1).sum()),
        "n_negative": int((y == 0).sum()),
        "features": {},
    }
    keys = [k for k in rows[0] if k not in ("label", "class")]
    for k in keys:
        x = np.array([r[k] for r in rows], dtype=float)
        if not np.isfinite(x).all() or len(np.unique(y)) < 2:
            continue
        report["features"][k] = auc_stats(y, x)

    good = ["mouth_perp_abs", "eye_open_asym", "brow_perp_abs", "cheek_perp_abs"]
    appf = ["app_mouth", "app_eye", "app_brow", "app_cheek"]
    report["cv_logreg_auc_corrected_geometry"] = cv_auc(
        np.array([[r[k] for k in good] for r in rows]), y)
    report["cv_logreg_auc_app_ratios"] = cv_auc(
        np.array([[r[k] for k in appf] for r in rows]), y)

    text = json.dumps(report, indent=2)
    if args.out:
        Path(args.out).write_text(text)
    print(text)


if __name__ == "__main__":
    main()
