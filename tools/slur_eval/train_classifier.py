#!/usr/bin/env python3
"""Speaker-disjoint evaluation of slur classifiers.

Ablations on each corpus (TORGO, pathological):
  - detector  : the app's z-score detector (mean raw) against a fold-local
                control baseline
  - handcrafted: LR on the 7 on-device features
  - ssl        : LR on WavLM/wav2vec2 embeddings (mean+std pooled)
  - fusion     : LR on handcrafted + ssl

Writes out/classifier_report.md + ROC plots, and saves the fusion model params.
Run with the slur_eval venv (numpy/sklearn/soundfile).
"""
from __future__ import annotations

import argparse
import json
import os
import pickle
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import soundfile as sf
from scipy import stats
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score, roc_curve
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

import detector as det
import features as feat

HERE = os.path.dirname(os.path.abspath(__file__))
KEYS = ["jitter", "shimmer", "hnr", "f0Std", "ems4hz", "pauseRatio", "wpm"]


def load_manifest() -> list[dict]:
    with open(os.path.join(HERE, "manifest.csv"), newline="") as f:
        return list(csv_reader(f))


def csv_reader(f):
    import csv

    return csv.DictReader(f)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default=os.path.join(HERE, "out"))
    args = ap.parse_args()

    rows = load_manifest()
    ssl = np.load(os.path.join(HERE, "ssl_emb.npz"))
    ssl_paths = {p: i for i, p in enumerate(ssl["paths"])}
    emb = ssl["emb"]

    # --- handcrafted features per clip (cached) ---
    hf_cache = os.path.join(HERE, "handcrafted.pkl")
    windows_cache = os.path.join(HERE, "windows.pkl")
    windows = pickle.load(open(windows_cache, "rb")) if os.path.exists(windows_cache) else {}
    hand = pickle.load(open(hf_cache, "rb")) if os.path.exists(hf_cache) else {}
    for r in rows:
        if r["path"] in hand:
            continue
        y, sr = sf.read(os.path.join(HERE, "wav", r["path"]), dtype="float32")
        ws = feat.extract_windows(y, sr, wpm=feat.clip_wpm(r["text"], float(r["duration_s"])))
        windows[r["path"]] = ws
        hand[r["path"]] = {k: float(np.mean([w.get(k, 0.0) for w in ws])) for k in KEYS} if ws else {k: 0.0 for k in KEYS}
    pickle.dump(windows, open(windows_cache, "wb"))
    pickle.dump(hand, open(hf_cache, "wb"))

    os.makedirs(args.outdir, exist_ok=True)
    report = open(os.path.join(args.outdir, "classifier_report.md"), "w")

    for ds in ["torgo", "pathological"]:
        rr = [r for r in rows if r["dataset"] == ds]
        y = np.array([0 if r["label"] in ("healthy", "control") else 1 for r in rr])
        groups = np.array([r["speaker"] for r in rr])
        H = np.array([[hand[r["path"]][k] for k in KEYS] for r in rr])
        S = np.array([emb[ssl_paths[r["path"]]] for r in rr])
        F = np.hstack([H, S])

        def cv(X, y, groups):
            n_splits = min(5, len(set(groups)))
            all_y, all_p = [], []
            from sklearn.model_selection import GroupKFold

            for tr, te in GroupKFold(n_splits=n_splits).split(X, y, groups):
                pipe = make_pipeline(StandardScaler(), LogisticRegression(max_iter=5000, class_weight="balanced"))
                pipe.fit(X[tr], y[tr])
                p = pipe.predict_proba(X[te])[:, 1]
                all_y += list(y[te])
                all_p += list(p)
            # leave-one-speaker-out: pool predictions across folds, then AUC.
            return float(roc_auc_score(np.array(all_y), np.array(all_p))), np.array(all_y), np.array(all_p)

        def detector_auc(rr, y, groups):
            n_splits = min(5, len(set(groups)))
            all_y, all_s = [], []
            from sklearn.model_selection import GroupKFold

            for tr, te in GroupKFold(n_splits=n_splits).split(y, y, groups):
                ctrl = [r for i, r in enumerate(rr) if i in tr and y[i] == 0]
                base = det.build_baseline([w for r in ctrl for w in windows[r["path"]]])
                scores = []
                for i in te:
                    d = det.SlurDetector(base)
                    raws = [d.update(w)["raw"] for w in windows[rr[i]["path"]]]
                    scores.append(float(np.mean(raws)) if raws else 0.0)
                all_y += list(y[te])
                all_s += scores
            return float(roc_auc_score(np.array(all_y), np.array(all_s)))

        det_auc = detector_auc(rr, y, groups)
        h_auc, _, _ = cv(H, y, groups)
        s_auc, sy, sp = cv(S, y, groups)
        f_auc, fy, fp = cv(F, y, groups)

        report.write(f"## {ds} (speakers={len(set(groups))}, n={len(rr)})\n")
        report.write(f"- detector (z-score, fold-local baseline): AUC **{det_auc:.3f}**\n")
        report.write(f"- handcrafted LR: AUC {h_auc:.3f}\n")
        report.write(f"- ssl LR: AUC {s_auc:.3f}\n")
        report.write(f"- fusion LR: AUC **{f_auc:.3f}**\n")

        # severity correlation on the pathological positives
        if ds == "pathological":
            sev_map = {"mild": 1, "moderate": 2, "severe": 3}
            sev = np.array([sev_map.get(r["severity"], np.nan) for r in rr])
            mask = ~np.isnan(sev)
            if mask.sum() >= 10:
                rho, p = stats.spearmanr(sev[mask], fp[mask])
                report.write(f"- fusion severity Spearman: {rho:.3f} (p={p:.2e}, n={int(mask.sum())})\n")

        # ROC for fusion
        fpr, tpr, _ = roc_curve(fy, fp)
        plt.figure()
        plt.plot(fpr, tpr, label=f"fusion AUC={f_auc:.3f}")
        plt.plot([0, 1], [0, 1], "--", color="gray")
        plt.xlabel("FPR"); plt.ylabel("TPR"); plt.legend(); plt.title(f"{ds} fusion")
        plt.savefig(os.path.join(args.outdir, f"{ds}_classifier_roc.png"), dpi=120)

        # Export the fusion model (fit on all data) for optional serving.
        pipe = make_pipeline(StandardScaler(), LogisticRegression(max_iter=5000, class_weight="balanced"))
        pipe.fit(F, y)
        sc = pipe.named_steps["standardscaler"]
        lr = pipe.named_steps["logisticregression"]
        model = {
            "features": KEYS + [f"ssl_{i}" for i in range(S.shape[1])],
            "mean": sc.mean_.tolist(),
            "std": sc.scale_.tolist(),
            "coef": lr.coef_[0].tolist(),
            "intercept": float(lr.intercept_[0]),
            "train_auc": f_auc,
            "n": int(len(y)),
        }
        json.dump(model, open(os.path.join(HERE, f"slur_classifier_{ds}.json"), "w"), indent=2)

        # SSL-only model for serving (no handcrafted/ASR needed), with a Youden
        # operating threshold from the pooled speaker-disjoint predictions.
        pipe2 = make_pipeline(StandardScaler(), LogisticRegression(max_iter=5000, class_weight="balanced"))
        pipe2.fit(S, y)
        sc2 = pipe2.named_steps["standardscaler"]
        lr2 = pipe2.named_steps["logisticregression"]
        fpr2, tpr2, thr2 = roc_curve(sy, sp)
        youden = int(np.argmax(tpr2 - fpr2))
        model2 = {
            "mean": sc2.mean_.tolist(),
            "std": sc2.scale_.tolist(),
            "coef": lr2.coef_[0].tolist(),
            "intercept": float(lr2.intercept_[0]),
            "threshold": float(thr2[youden]),
            "ssl_model": str(ssl["model"]),
            "cv_auc": s_auc,
            "n": int(len(y)),
        }
        json.dump(model2, open(os.path.join(HERE, f"slur_classifier_ssl_{ds}.json"), "w"), indent=2)
        report.write("\n")

    report.close()
    print("wrote", os.path.join(args.outdir, "classifier_report.md"))


if __name__ == "__main__":
    sys.exit(main())