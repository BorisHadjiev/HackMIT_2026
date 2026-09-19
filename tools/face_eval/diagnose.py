#!/usr/bin/env python3
"""Diagnose WHY the app's ratio fails: separate true droop from pose/scale/crop confounds.

Adds, per image:
  - image + annotated bbox size (crop/scale confound)
  - interocular distance (face scale)
  - roll angle of the eye line
  - yaw proxy (nose tip x offset from eye midpoint)
  - app pair ratios (nose-tip axis)
  - roll-invariant perpendicular-droop asymmetry (eye-line axis) for mouth/brow/cheek/eye
  - mouth-corner vertical asymmetry normalized by interocular
"""
import argparse
import json
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision
from sklearn.metrics import roc_auc_score
from tqdm import tqdm

NOSE = 1
EYE_L, EYE_R = 33, 263
MOUTH_L, MOUTH_R = 61, 291
BROW_L, BROW_R = 105, 334
CHEEK_L, CHEEK_R = 234, 454
APP_PAIRS = [("mouth", MOUTH_L, MOUTH_R), ("eye", EYE_L, EYE_R),
             ("brow", BROW_L, BROW_R), ("cheek", CHEEK_L, CHEEK_R)]


def app_pair_ratio(lm, l, r, axis=NOSE):
    ax, ay = lm[axis]
    dl = np.hypot(*(lm[l] - [ax, ay]))
    dr = np.hypot(*(lm[r] - [ax, ay]))
    return 0.0 if dl + dr == 0 else abs(dl - dr) / (dl + dr)


def read_yolo_bbox(txt_path):
    if not txt_path.exists():
        return None
    p = txt_path.read_text().split()
    return tuple(float(x) for x in p[1:5]) if len(p) >= 5 else None


def build_landmarker(model_path):
    opts = vision.FaceLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=model_path),
        running_mode=vision.RunningMode.IMAGE, num_faces=1,
        min_face_detection_confidence=0.3, min_face_presence_confidence=0.3,
        min_tracking_confidence=0.3)
    return vision.FaceLandmarker.create_from_options(opts)


def detect(landmarker, img):
    rgb = np.ascontiguousarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    res = landmarker.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
    if not res.face_landmarks:
        return None
    return np.array([[p.x, p.y] for p in res.face_landmarks[0]], dtype=np.float64)


def features(lm):
    mid = (lm[EYE_L] + lm[EYE_R]) / 2.0
    eye_vec = lm[EYE_R] - lm[EYE_L]
    iod = float(np.hypot(*eye_vec))
    u = eye_vec / iod if iod else np.array([1.0, 0.0])
    v = np.array([-u[1], u[0]])  # perpendicular (down/up in image coords)
    roll = float(np.degrees(np.arctan2(eye_vec[1], eye_vec[0])))
    yaw = float((lm[NOSE][0] - mid[0]) / iod) if iod else 0.0

    f = {"interocular": iod, "roll_deg": roll, "yaw": yaw}
    for name, l, r in APP_PAIRS:
        f[f"app_{name}"] = app_pair_ratio(lm, l, r)
    f["app_combined"] = float(np.mean([f[f"app_{n}"] for n, _, _ in APP_PAIRS]))

    for name, l, r in APP_PAIRS:
        pl = float(np.dot(lm[l] - mid, v))
        pr = float(np.dot(lm[r] - mid, v))
        f[f"perp_{name}_abs"] = abs(pl - pr) / iod if iod else 0.0
        f[f"perp_{name}_signed"] = (pl - pr) / iod if iod else 0.0
    return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default=str(Path.home() / "face_eval/dataset"))
    ap.add_argument("--model", default=str(Path.home() / "face_eval/face_landmarker.task"))
    ap.add_argument("--out", default=str(Path.home() / "face_eval/results_diag"))
    args = ap.parse_args()
    ds, out = Path(args.dataset), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    lmk = build_landmarker(args.model)

    files = []
    for cls, label in [("Stroke", 1), ("NonStroke", 0)]:
        for jpg in sorted((ds / cls).glob("*.jpg")):
            files.append((jpg, label))

    rows = []
    for jpg, label in tqdm(files, desc="diag"):
        img = cv2.imread(str(jpg))
        if img is None:
            continue
        h, w = img.shape[:2]
        lm = detect(lmk, img)
        row = {"file": jpg.name, "label": label, "img_w": w, "img_h": h}
        bbox = read_yolo_bbox(jpg.with_suffix(".txt"))
        row["bbox_w"] = bbox[2] if bbox else np.nan
        row["bbox_h"] = bbox[3] if bbox else np.nan
        if lm is not None:
            row.update(detected=True, **features(lm))
        else:
            row["detected"] = False
        rows.append(row)

    d = [r for r in rows if r.get("detected")]
    y = np.array([r["label"] for r in d])
    keys = [k for k in d[0] if k not in ("file", "label", "detected")]
    report = {"n_detected": len(d), "n_stroke": int((y == 1).sum()),
              "n_nonstroke": int((y == 0).sum()), "features": {}}
    for k in keys:
        x = np.array([r[k] for r in d], dtype=float)
        if not np.isfinite(x).all() or len(np.unique(y)) < 2:
            continue
        auc = float(roc_auc_score(y, x))
        report["features"][k] = {
            "auc": auc, "auc_oriented": max(auc, 1 - auc),
            "mean_stroke": float(x[y == 1].mean()),
            "mean_nonstroke": float(x[y == 0].mean()),
            "direction": "higher_in_stroke" if auc >= 0.5 else "lower_in_stroke",
        }

    with (out / "diag.json").open("w") as f:
        json.dump(report, f, indent=2)
    with (out / "diag.csv").open("w") as f:
        cols = list(rows[0].keys())
        f.write(",".join(cols) + "\n")
        for r in rows:
            f.write(",".join(str(r.get(c, "")) for c in cols) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
