#!/usr/bin/env python3
"""Refined geometric droop features + honest evaluation on the stroke dataset.

Fixes vs the app's AsymmetryCalculator:
  - eye line (33-263) is the symmetry axis, not the nose tip
  - asymmetry = perpendicular distance difference, normalized by interocular
  - eyelid opening asymmetry added
  - residualize out face scale (log interocular) to expose/deny confounds
"""
import json
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.metrics import roc_auc_score, roc_curve
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from tqdm import tqdm

EYE_L, EYE_R = 33, 263
MOUTH_L, MOUTH_R = 61, 291
BROW_L, BROW_R = 105, 334
CHEEK_L, CHEEK_R = 234, 454
NOSE, CHIN, FOREHEAD = 1, 152, 10
LID_UP_L, LID_LO_L = 159, 145
LID_UP_R, LID_LO_R = 386, 374
NOSE_L, NOSE_R = 129, 358

APP = [("app_mouth", MOUTH_L, MOUTH_R), ("app_eye", EYE_L, EYE_R),
       ("app_brow", BROW_L, BROW_R), ("app_cheek", CHEEK_L, CHEEK_R)]


def app_ratio(lm, l, r):
    ax, ay = lm[NOSE]
    dl = np.hypot(*(lm[l] - [ax, ay]))
    dr = np.hypot(*(lm[r] - [ax, ay]))
    return 0.0 if dl + dr == 0 else abs(dl - dr) / (dl + dr)


def feats(lm):
    mid = (lm[EYE_L] + lm[EYE_R]) / 2.0
    ev = lm[EYE_R] - lm[EYE_L]
    iod = float(np.hypot(*ev))
    u = ev / iod if iod else np.array([1.0, 0.0])
    v = np.array([-u[1], u[0]])
    f = {"iod": iod, "face_h": float(np.hypot(*(lm[CHIN] - lm[FOREHEAD])) / iod)}
    for name, l, r in APP:
        f[name] = app_ratio(lm, l, r)

    def perp(idx):
        return float(np.dot(lm[idx] - mid, v) / iod)

    f["mouth_perp_abs"] = abs(perp(MOUTH_L) - perp(MOUTH_R))
    f["brow_perp_abs"] = abs(perp(BROW_L) - perp(BROW_R))
    f["cheek_perp_abs"] = abs(perp(CHEEK_L) - perp(CHEEK_R))
    f["nose_perp_abs"] = abs(perp(NOSE))
    f["mouth_horiz"] = float(np.hypot(*(lm[MOUTH_L] - lm[MOUTH_R])) / iod)

    open_l = float(np.hypot(*(lm[LID_UP_L] - lm[LID_LO_L])) / iod)
    open_r = float(np.hypot(*(lm[LID_UP_R] - lm[LID_LO_R])) / iod)
    f["eye_open_l"], f["eye_open_r"] = open_l, open_r
    f["eye_open_asym"] = abs(open_l - open_r) / (open_l + open_r) if open_l + open_r else 0.0
    return f


def build(model):
    opts = vision.FaceLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=model),
        running_mode=vision.RunningMode.IMAGE, num_faces=1,
        min_face_detection_confidence=0.3, min_face_presence_confidence=0.3,
        min_tracking_confidence=0.3)
    return vision.FaceLandmarker.create_from_options(opts)


def auc_stats(y, x):
    auc = float(roc_auc_score(y, x))
    fpr, tpr, thr = roc_curve(y, x)
    if auc < 0.5:
        x = -x
        fpr, tpr, thr = roc_curve(y, x)
        auc_o = 1 - auc
    else:
        auc_o = auc
    j = tpr - fpr
    b = int(np.argmax(j))
    return {"auc": auc, "auc_oriented": auc_o,
            "mean_stroke": float(x[y == 1].mean()),
            "mean_nonstroke": float(x[y == 0].mean()),
            "sens": float(tpr[b]), "spec": float(1 - fpr[b]),
            "acc": float((tpr[b] + 1 - fpr[b]) / 2), "thr": float(thr[b])}


def cv_auc(X, y):
    clf = make_pipeline(StandardScaler(),
                        LogisticRegression(max_iter=3000, class_weight="balanced"))
    cv = StratifiedKFold(5, shuffle=True, random_state=0)
    p = cross_val_predict(clf, X, y, cv=cv, method="predict_proba")[:, 1]
    return float(roc_auc_score(y, p))


def main():
    home = Path.home() / "face_eval"
    ds = home / "dataset"
    lmk = build(str(home / "face_landmarker.task"))
    files = []
    for cls, label in [("Stroke", 1), ("NonStroke", 0)]:
        files += [(p, label) for p in sorted((ds / cls).glob("*.jpg"))]

    rows = []
    for jpg, label in tqdm(files, desc="v2"):
        img = cv2.imread(str(jpg))
        if img is None:
            continue
        rgb = np.ascontiguousarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        res = lmk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        if not res.face_landmarks:
            continue
        lm = np.array([[p.x, p.y] for p in res.face_landmarks[0]])
        rows.append({"label": label, **feats(lm)})

    y = np.array([r["label"] for r in rows])
    keys = [k for k in rows[0] if k != "label"]
    report = {"n": len(rows), "n_stroke": int((y == 1).sum()),
              "n_nonstroke": int((y == 0).sum()), "features": {}}

    # residualize out log(iod) to remove the acquisition/scale confound
    log_iod = np.log(np.array([r["iod"] for r in rows])).reshape(-1, 1)
    report["features_raw"] = {}
    report["features_scale_residualized"] = {}
    for k in keys:
        x = np.array([r[k] for r in rows], dtype=float)
        if not np.isfinite(x).all():
            continue
        report["features_raw"][k] = auc_stats(y, x)
        if k == "iod":
            continue
        resid = x - LinearRegression().fit(log_iod, x).predict(log_iod)
        report["features_scale_residualized"][k] = auc_stats(y, resid)

    good = ["mouth_perp_abs", "eye_open_asym", "brow_perp_abs", "cheek_perp_abs"]
    appf = [n for n, _, _ in APP]
    Xg = np.array([[r[k] for k in good] for r in rows])
    Xa = np.array([[r[k] for k in appf] for r in rows])
    report["cv_logreg_auc_corrected_geometry"] = cv_auc(Xg, y)
    report["cv_logreg_auc_app_ratios"] = cv_auc(Xa, y)
    report["cv_logreg_auc_all"] = cv_auc(
        np.array([[r[k] for k in keys] for r in rows]), y)

    out = home / "results_v2"
    out.mkdir(exist_ok=True)
    (out / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
