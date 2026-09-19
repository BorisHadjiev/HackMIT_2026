#!/usr/bin/env python3
"""Evaluate the app's MediaPipe asymmetry-ratio approach on the
"Annotated stroke and non stroke Dataset" (YOLO layout: class 0=Stroke, 1=NonStroke).

Mirrors app/src/main/java/com/hackmit/app/video/FaceAnalyzer.kt:
  - landmark 1 (nose tip) is the symmetry axis
  - pairRatio = |dL - dR| / (dL + dR)
  - asymmetryIndex = (mouth + eyes + brows + cheeks) / 4 * 4  (coerced to [0, 1])

The app compares a frame to a *personal* baseline; this script can only test
*population* separation (Stroke vs NonStroke), which is the harder problem and
therefore a lower bound on whether the signal is usable.
"""
import argparse
import json
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score, roc_curve
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from tqdm import tqdm

NOSE = 1
PAIRS = [
    ("mouth", 61, 291),
    ("eye", 33, 263),
    ("brow", 105, 334),
    ("cheek", 234, 454),
]


def pair_ratio(lm: np.ndarray, left: int, right: int, axis: int = NOSE) -> float:
    lx, ly = lm[left]
    rx, ry = lm[right]
    ax, ay = lm[axis]
    dl = float(np.hypot(lx - ax, ly - ay))
    dr = float(np.hypot(rx - ax, ry - ay))
    denom = dl + dr
    return 0.0 if denom == 0 else abs(dl - dr) / denom


def read_yolo_bbox(txt_path: Path):
    if not txt_path.exists():
        return None
    parts = txt_path.read_text().split()
    if len(parts) < 5:
        return None
    _, cx, cy, bw, bh = (float(p) for p in parts[:5])
    return cx, cy, bw, bh


def crop_with_margin(img, bbox, margin=0.15):
    h, w = img.shape[:2]
    cx, cy, bw, bh = bbox
    bw *= 1 + margin
    bh *= 1 + margin
    x1 = int(max(0, (cx - bw / 2) * w))
    y1 = int(max(0, (cy - bh / 2) * h))
    x2 = int(min(w, (cx + bw / 2) * w))
    y2 = int(min(h, (cy + bh / 2) * h))
    if x2 <= x1 or y2 <= y1:
        return img
    return img[y1:y2, x1:x2]


def build_landmarker(model_path: str):
    options = vision.FaceLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=model_path),
        running_mode=vision.RunningMode.IMAGE,
        num_faces=1,
        min_face_detection_confidence=0.3,
        min_face_presence_confidence=0.3,
        min_tracking_confidence=0.3,
        output_face_blendshapes=False,
        output_facial_transformation_matrixes=False,
    )
    return vision.FaceLandmarker.create_from_options(options)


def process(landmarker, img):
    rgb = np.ascontiguousarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
    res = landmarker.detect(mp_img)
    if not res.face_landmarks:
        return None
    lms = res.face_landmarks[0]
    return np.array([[p.x, p.y] for p in lms], dtype=np.float64)


def evaluate(rows):
    y = np.array([r["label"] for r in rows])
    detected = np.array([r["detected"] for r in rows])
    out = {
        "n_total": int(len(rows)),
        "n_stroke": int((y == 1).sum()),
        "n_nonstroke": int((y == 0).sum()),
        "detected_total": int(detected.sum()),
        "detected_stroke": int(((y == 1) & detected).sum()),
        "detected_nonstroke": int(((y == 0) & detected).sum()),
        "detection_rate": float(detected.mean()),
    }
    d = detected
    yd = y[d]
    out["features"] = {}
    for name in ["mouth", "eye", "brow", "cheek", "combined_mean", "combined_sum"]:
        x = np.array([r[name] for r in rows])[d]
        if len(np.unique(yd)) < 2:
            continue
        auc = float(roc_auc_score(yd, x))
        fpr, tpr, thr = roc_curve(yd, x)
        j = tpr - fpr
        best = int(np.argmax(j))
        out["features"][name] = {
            "auc": auc,
            "auc_oriented": max(auc, 1 - auc),
            "direction": "higher_in_stroke" if auc >= 0.5 else "lower_in_stroke",
            "best_threshold": float(thr[best]),
            "sensitivity": float(tpr[best]),
            "specificity": float(1 - fpr[best]),
            "accuracy": float((tpr[best] + (1 - fpr[best])) / 2),
            "mean_stroke": float(x[yd == 1].mean()) if (yd == 1).any() else None,
            "mean_nonstroke": float(x[yd == 0].mean()) if (yd == 0).any() else None,
        }
    X = np.array([[r["mouth"], r["eye"], r["brow"], r["cheek"]] for r in rows])[d]
    if len(np.unique(yd)) == 2 and len(yd) >= 50:
        clf = make_pipeline(
            StandardScaler(),
            LogisticRegression(max_iter=2000, class_weight="balanced"),
        )
        cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=0)
        proba = cross_val_predict(clf, X, yd, cv=cv, method="predict_proba")[:, 1]
        out["logreg_cv_auc"] = float(roc_auc_score(yd, proba))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default=str(Path.home() / "face_eval/dataset"))
    ap.add_argument("--model", default=str(Path.home() / "face_eval/face_landmarker.task"))
    ap.add_argument("--out", default=str(Path.home() / "face_eval/results"))
    ap.add_argument("--crop", action="store_true", help="crop to annotated face bbox")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    ds = Path(args.dataset)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    landmarker = build_landmarker(args.model)

    files = []
    for cls, label in [("Stroke", 1), ("NonStroke", 0)]:
        for jpg in sorted((ds / cls).glob("*.jpg")):
            files.append((jpg, label))
    if args.limit:
        files = files[: args.limit]

    rows = []
    for jpg, label in tqdm(files, desc="faces"):
        img = cv2.imread(str(jpg))
        if img is None:
            continue
        if args.crop:
            bbox = read_yolo_bbox(jpg.with_suffix(".txt"))
            if bbox:
                img = crop_with_margin(img, bbox)
        lm = process(landmarker, img)
        row = {"file": jpg.name, "class": jpg.parent.name, "label": label}
        if lm is None:
            row.update(detected=False, mouth=np.nan, eye=np.nan, brow=np.nan,
                       cheek=np.nan, combined_mean=np.nan, combined_sum=np.nan)
        else:
            ratios = {n: pair_ratio(lm, l, r) for n, l, r in PAIRS}
            mean = float(np.mean(list(ratios.values())))
            row.update(
                detected=True,
                **ratios,
                combined_mean=mean,
                combined_sum=float(min(sum(ratios.values()), 1.0)),
            )
        rows.append(row)

    metrics = evaluate(rows)
    metrics["config"] = {"crop": args.crop, "limit": args.limit,
                         "model": Path(args.model).name, "dataset": str(ds)}

    csv_path = out_dir / "per_image.csv"
    with csv_path.open("w") as f:
        cols = ["file", "class", "label", "detected", "mouth", "eye", "brow",
                "cheek", "combined_mean", "combined_sum"]
        f.write(",".join(cols) + "\n")
        for r in rows:
            f.write(",".join(str(r[c]) for c in cols) + "\n")

    json_path = out_dir / "metrics.json"
    json_path.write_text(json.dumps(metrics, indent=2))
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
