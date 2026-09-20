#!/usr/bin/env python3
"""Train a StandardScaler + LogisticRegression on the corrected face geometry.

Uses the cached .npz feature files (mouth/eye/cheek/brow perpendicular offsets,
iod-normalized) from tune.py. Reports CV AUC, then picks an operating threshold
keeping LFW (healthy control) false positives ~5%, and prints the model params
as JSON to embed in the app's AsymmetryCalculator.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from scipy.optimize import minimize
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, roc_auc_score
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

KEYS = ["mouth_perp_abs", "eye_open_asym", "cheek_perp_abs", "brow_perp_abs"]
TRAIN_SETS = ["stroke", "palsynet", "tiny"]
CONTROL = "lfw"


def ece(y, p, n_bins=10):
    total, bins = 0.0, np.linspace(0, 1, n_bins + 1)
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (p >= lo) & (p < hi) | ((p == 1.0) & (hi == 1.0))
        if not m.any():
            continue
        total += m.sum() * abs(y[m].mean() - p[m].mean())
    return float(total / len(y))


def fit_temperature(logits, y):
    def nll(T):
        p = np.clip(1.0 / (1.0 + np.exp(-logits / T)), 1e-7, 1 - 1e-7)
        return float(-(y * np.log(p) + (1 - y) * np.log(1 - p)).mean())

    return float(minimize(nll, x0=1.0, method="Nelder-Mead").x[0])


def load(cache: Path, name: str):
    z = np.load(cache / f"{name}.npz")
    return np.array([z[k] for k in KEYS]).T, z["labels"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(Path.home() / "face_eval/feat_cache"))
    ap.add_argument("--lfw-fp-target", type=float, default=0.05)
    args = ap.parse_args()
    cache = Path(args.cache)

    Xs, ys = [], []
    for name in TRAIN_SETS:
        X, y = load(cache, name)
        Xs.append(X)
        ys.append(y)
    X = np.vstack(Xs)
    y = np.concatenate(ys)
    print(f"train n={len(y)} pos={int((y==1).sum())} neg={int((y==0).sum())}")

    clf = make_pipeline(StandardScaler(), LogisticRegression(max_iter=5000, class_weight="balanced"))
    cv = StratifiedKFold(5, shuffle=True, random_state=0)
    p_cv = cross_val_predict(clf, X, y, cv=cv, method="predict_proba")[:, 1]
    l_cv = cross_val_predict(clf, X, y, cv=cv, method="decision_function")
    print(f"5-fold CV AUC = {roc_auc_score(y, p_cv):.4f}")
    T = fit_temperature(l_cv, y)
    p_cal = 1.0 / (1.0 + np.exp(-l_cv / T))
    print(f"temperature T={T:.3f}  ECE raw={ece(y, p_cv):.4f} -> cal={ece(y, p_cal):.4f}  Brier raw={brier_score_loss(y, p_cv):.4f}")

    clf.fit(X, y)
    scaler = clf.named_steps["standardscaler"]
    lr = clf.named_steps["logisticregression"]
    mean = scaler.mean_.tolist()
    std = scaler.scale_.tolist()
    coef = lr.coef_[0].tolist()
    intercept = float(lr.intercept_[0])
    print("coef:", [round(c, 4) for c in coef], "intercept:", round(intercept, 4))

    # LFW false-positive rate vs threshold; pick a threshold near target.
    Xc, yc = load(cache, CONTROL)
    pc = clf.predict_proba(Xc)[:, 1]
    thr_grid = np.linspace(0.0, 1.0, 2001)
    fp = [(pc > t).mean() for t in thr_grid]
    idx = np.argmin([abs(f - args.lfw_fp_target) for f in fp])
    thr = float(thr_grid[idx])
    lfw_fp = float(fp[idx])

    train_p = clf.predict_proba(X)[:, 1]
    sens_train = float((train_p[y == 1] > thr).mean())
    spec_train = float((train_p[y == 0] <= thr).mean())
    print(f"threshold={thr:.4f} LFW_FP={lfw_fp:.4f} train_sens={sens_train:.4f} train_spec={spec_train:.4f}")

    out = {
        "features": KEYS,
        "mean": mean,
        "std": std,
        "coef": coef,
        "intercept": intercept,
        "threshold": thr,
        "temperature": T,
        "cv_auc": float(roc_auc_score(y, p_cv)),
        "ece": ece(y, p_cv),
        "brier": brier_score_loss(y, p_cv),
        "lfw_fp": lfw_fp,
        "train_sens": sens_train,
        "train_spec": spec_train,
        "n_train": int(len(y)),
    }
    dest = Path(__file__).resolve().parent / "lr_model.json"
    dest.write_text(json.dumps(out, indent=2))
    print("wrote", dest)


if __name__ == "__main__":
    main()