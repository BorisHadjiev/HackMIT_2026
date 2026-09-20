#!/usr/bin/env python3
"""Grid-search the FaceAnalyzer normalizers/weights against the cached datasets.

Goal: keep healthy-face (LFW) false positives near 5% at the app's 0.33 threshold
while preserving sensitivity on the stroke and palsy positives.
"""
import argparse
import json
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from tqdm import tqdm

from eval_folder import label_for
from features_v2 import build, feats

DATASETS = {
    "stroke": (str(Path.home() / "face_eval/dataset"), "folder"),
    "palsynet": (str(Path.home() / "face_eval/palsynet/frames"), "folder"),
    "tiny": (str(Path.home() / "face_eval/face_stroke_tiny"), "folder"),
    "lfw": (str(Path.home() / "face_eval/lfw"), "control"),
}
FEATURE_KEYS = ["mouth_perp_abs", "eye_open_asym", "cheek_perp_abs", "brow_perp_abs"]


def imgs_of(root):
    return [p for ext in ("*.jpg", "*.jpeg", "*.png", "*.bmp") for p in root.rglob(ext)]


def extract(name, root, mode, model, cache_dir, refresh):
    cache = cache_dir / f"{name}.npz"
    if cache.exists() and not refresh:
        z = np.load(cache)
        return z["labels"], {k: z[k] for k in FEATURE_KEYS}
    root = Path(root)
    if mode == "folder":
        items = []
        for d in sorted(root.iterdir()):
            if d.is_dir():
                items += [(p, label_for(d.name)) for p in sorted(imgs_of(d))]
    else:
        items = [(p, -1) for p in sorted(imgs_of(root))]
    lmk = build(model)
    labels, acc = [], {k: [] for k in FEATURE_KEYS}
    for p, lab in tqdm(items, desc=name):
        img = cv2.imread(str(p))
        if img is None:
            continue
        rgb = np.ascontiguousarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        res = lmk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        if not res.face_landmarks:
            continue
        f = feats(np.array([[q.x, q.y] for q in res.face_landmarks[0]]))
        labels.append(lab)
        for k in FEATURE_KEYS:
            acc[k].append(f[k])
    labels = np.array(labels)
    data = {k: np.array(v) for k, v in acc.items()}
    cache_dir.mkdir(parents=True, exist_ok=True)
    np.savez(cache, labels=labels, **data)
    return labels, data


def severity(d, mf, cf, ef, wm, wc, we):
    return (wm * np.clip(d["mouth_perp_abs"] / mf, 0, 1)
            + wc * np.clip(d["cheek_perp_abs"] / cf, 0, 1)
            + we * np.clip(d["eye_open_asym"] / ef, 0, 1))


def fp_at(sev, thr=0.33):
    return float((sev > thr).mean())


def sens_at(sev, labels, thr=0.33):
    pos = labels == 1
    return float((sev[pos] > thr).mean()) if pos.any() else float("nan")


def neg_fp_at(sev, labels, thr=0.33):
    neg = labels == 0
    return float((sev[neg] > thr).mean()) if neg.any() else float("nan")


def auc(sev, labels):
    from sklearn.metrics import roc_auc_score
    if len(np.unique(labels)) < 2:
        return float("nan")
    return float(roc_auc_score(labels, sev))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(Path.home() / "face_eval/face_landmarker.task"))
    ap.add_argument("--cache", default=str(Path.home() / "face_eval/feat_cache"))
    ap.add_argument("--refresh", action="store_true")
    args = ap.parse_args()
    cache = Path(args.cache)

    data = {name: extract(name, root, mode, args.model, cache, args.refresh)
            for name, (root, mode) in DATASETS.items()}

    results = []
    for mf in (0.10, 0.12, 0.15, 0.18):
        for cf in (0.08, 0.10, 0.12):
            for ef in (0.25, 0.35, 0.45):
                for (wm, wc, we) in ((0.70, 0.20, 0.10), (0.75, 0.15, 0.10),
                                     (0.80, 0.15, 0.05), (0.70, 0.25, 0.05)):
                    cfg = dict(mf=mf, cf=cf, ef=ef, wm=wm, wc=wc, we=we)
                    row = dict(cfg, lfw_fp=fp_at(severity(data["lfw"][1], **cfg)))
                    for name in ("stroke", "palsynet", "tiny"):
                        labels, d = data[name]
                        s = severity(d, **cfg)
                        row[f"{name}_auc"] = auc(s, labels)
                        row[f"{name}_sens"] = sens_at(s, labels)
                        row[f"{name}_fp"] = neg_fp_at(s, labels)
                    results.append(row)

    def show(title, rows):
        print(title)
        for r in rows[:10]:
            print(f"  mf={r['mf']:.2f} cf={r['cf']:.2f} ef={r['ef']:.2f} "
                  f"w=({r['wm']:.2f},{r['wc']:.2f},{r['we']:.2f}) | "
                  f"LFW_FP={r['lfw_fp']:.3f} | stroke AUC={r['stroke_auc']:.3f} "
                  f"sens={r['stroke_sens']:.2f} negFP={r['stroke_fp']:.2f} | "
                  f"palsy AUC={r['palsynet_auc']:.3f} sens={r['palsynet_sens']:.2f} "
                  f"negFP={r['palsynet_fp']:.2f} | tiny AUC={r['tiny_auc']:.3f}")

    low_fp = [r for r in results if r["lfw_fp"] <= 0.06]
    low_fp.sort(key=lambda r: r["stroke_sens"], reverse=True)
    show("best sensitivity at LFW FP <= 6% (sorted by stroke sens):", low_fp)
    by_auc = sorted(results, key=lambda r: r["stroke_auc"] + r["palsynet_auc"],
                    reverse=True)
    show("best combined AUC (any FP):", by_auc)
    (cache / "tune_results.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
