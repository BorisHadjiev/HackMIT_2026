#!/usr/bin/env python3
"""Probability calibration for the SSL slur classifier.

Recomputes the speaker-disjoint pooled predictions (same protocol as
train_classifier.py) and reports:
  - ECE (expected calibration error, 10 bins)
  - Brier score
  - reliability diagram
  - a temperature T fit to minimize log-loss (temperature scaling)

Writes out/calibration_report.md, out/{ds}_reliability.png, and a
calibration.json with the fitted temperature. Also bakes `temperature` into the
served ssl model JSON so the gateway can apply it at inference.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from scipy.optimize import minimize  # noqa: E402
from sklearn.linear_model import LogisticRegression  # noqa: E402
from sklearn.metrics import brier_score_loss  # noqa: E402
from sklearn.model_selection import GroupKFold  # noqa: E402
from sklearn.pipeline import make_pipeline  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def ece(y: np.ndarray, p: np.ndarray, n_bins: int = 10) -> float:
    """Expected calibration error over equal-width probability bins."""
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    total = 0.0
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (p >= lo) & (p < hi) | ((p == 1.0) & (hi == 1.0))
        if not m.any():
            continue
        acc = y[m].mean()
        conf = p[m].mean()
        total += m.sum() * abs(acc - conf)
    return float(total / len(y))


def calibrate_curve(y: np.ndarray, p: np.ndarray, n_bins: int = 10):
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    out = []
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (p >= lo) & (p < hi) | ((p == 1.0) & (hi == 1.0))
        if not m.any():
            continue
        out.append((p[m].mean(), y[m].mean(), int(m.sum())))
    return np.array(out) if out else np.zeros((0, 3))


def fit_temperature(logits: np.ndarray, y: np.ndarray) -> float:
    def nll(T: float) -> float:
        p = 1.0 / (1.0 + np.exp(-logits / T))
        p = np.clip(p, 1e-7, 1 - 1e-7)
        return float(-(y * np.log(p) + (1 - y) * np.log(1 - p)).mean())

    res = minimize(nll, x0=1.0, method="Nelder-Mead")
    return float(res.x[0])


def pooled_ssl_predictions(rows, emb, paths) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Speaker-disjoint pooled (y, p, logit) for the SSL LR on a dataset subset."""
    y = np.array([0 if r["label"] in ("healthy", "control") else 1 for r in rows])
    groups = np.array([r["speaker"] for r in rows])
    S = np.array([emb[paths.index(r["path"])] for r in rows])
    all_y, all_p, all_l = [], [], []
    n_splits = min(5, len(set(groups)))
    for tr, te in GroupKFold(n_splits=n_splits).split(S, y, groups):
        pipe = make_pipeline(StandardScaler(), LogisticRegression(max_iter=5000, class_weight="balanced"))
        pipe.fit(S[tr], y[tr])
        lr = pipe.named_steps["logisticregression"]
        sc = pipe.named_steps["standardscaler"]
        z = sc.transform(S[te])
        logit = lr.decision_function(z)
        p = 1.0 / (1.0 + np.exp(-logit))
        all_y += list(y[te])
        all_p += list(p)
        all_l += list(logit)
    return np.array(all_y), np.array(all_p), np.array(all_l)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default=os.path.join(HERE, "out"))
    args = ap.parse_args()

    rows = list(csv.DictReader(open(os.path.join(HERE, "manifest.csv"))))
    ssl = np.load(os.path.join(HERE, "ssl_emb.npz"))
    emb, paths = ssl["emb"], list(ssl["paths"])

    os.makedirs(args.outdir, exist_ok=True)
    report = open(os.path.join(args.outdir, "calibration_report.md"), "w")
    calibrations: dict[str, dict] = {}

    for ds, json_fn in [
        ("torgo", "slur_classifier_ssl_torgo.json"),
        ("pathological", "slur_classifier_ssl_pathological.json"),
    ]:
        rr = [r for r in rows if r["dataset"] == ds]
        y, p, logit = pooled_ssl_predictions(rr, emb, paths)
        brier = float(brier_score_loss(y, p))
        ece_raw = ece(y, p)
        T = fit_temperature(logit, y)
        p_cal = 1.0 / (1.0 + np.exp(-logit / T))
        ece_cal = ece(y, p_cal)
        brier_cal = float(brier_score_loss(y, p_cal))

        report.write(f"## {ds} (n={len(rr)}, speakers={len(set(r['speaker'] for r in rr))})\n")
        report.write(f"- ECE (raw): **{ece_raw:.4f}**\n")
        report.write(f"- Brier (raw): {brier:.4f}\n")
        report.write(f"- temperature T: **{T:.3f}**\n")
        report.write(f"- ECE (temp-scaled): **{ece_cal:.4f}**\n")
        report.write(f"- Brier (temp-scaled): {brier_cal:.4f}\n\n")

        # reliability diagram
        c = calibrate_curve(y, p)
        cc = calibrate_curve(y, p_cal)
        fig, ax = plt.subplots(figsize=(5, 5))
        ax.plot([0, 1], [0, 1], "--", color="gray", label="perfect")
        if len(c):
            ax.plot(c[:, 0], c[:, 1], "o-", label=f"raw (ECE={ece_raw:.3f})")
        if len(cc):
            ax.plot(cc[:, 0], cc[:, 1], "s-", label=f"temp-scaled (ECE={ece_cal:.3f})")
        ax.set_xlabel("confidence"); ax.set_ylabel("empirical accuracy")
        ax.legend(); ax.set_title(f"{ds} reliability")
        plt.tight_layout()
        plt.savefig(os.path.join(args.outdir, f"{ds}_reliability.png"), dpi=120)
        plt.close()

        calibrations[ds] = {"temperature": T, "ece_raw": ece_raw, "brier_raw": brier,
                            "ece_cal": ece_cal, "brier_cal": brier_cal}
        # bake temperature into the served model json (in the slur_eval dir)
        model_path = os.path.join(HERE, json_fn)
        m = json.load(open(model_path))
        m["temperature"] = T
        json.dump(m, open(model_path, "w"), indent=2)
        print(f"{ds}: ECE {ece_raw:.4f} -> {ece_cal:.4f}  T={T:.3f}")

    with open(os.path.join(args.outdir, "calibration.json"), "w") as f:
        json.dump(calibrations, f, indent=2)
    report.close()
    print("wrote", os.path.join(args.outdir, "calibration_report.md"))


if __name__ == "__main__":
    sys.exit(main())