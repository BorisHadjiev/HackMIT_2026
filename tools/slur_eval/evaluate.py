#!/usr/bin/env python3
"""Leave-speaker-out evaluation of the ported SlurDetector on dysarthria corpora."""
from __future__ import annotations

import argparse
import csv
import os
import pickle
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import soundfile as sf
from scipy import stats
from sklearn.metrics import roc_auc_score, roc_curve
from tqdm import tqdm

import detector as det
import features as feat

HERE = os.path.dirname(os.path.abspath(__file__))


def load_manifest() -> list[dict]:
    with open(os.path.join(HERE, "manifest.csv"), newline="") as f:
        return list(csv.DictReader(f))


def extract(row: dict, cache: dict) -> list[dict]:
    if row["path"] in cache:
        return cache[row["path"]]
    y, sr = sf.read(os.path.join(HERE, "wav", row["path"]), dtype="float32")
    wpm = feat.clip_wpm(row["text"], float(row["duration_s"]))
    windows = feat.extract_windows(y, sr, wpm=wpm)
    cache[row["path"]] = windows
    return windows


def clip_scores(windows: list[dict], baseline: det.Baseline, sensitivity: float = 0.55):
    detector = det.SlurDetector(baseline, sensitivity)
    raws = []
    finals = []
    for w in windows:
        result = detector.update(w)
        raws.append(result["raw"])
        finals.append(result["score"])
    if not raws:
        return {"final": 0.0, "mean_raw": 0.0, "max_raw": 0.0, "raws": []}
    return {"final": finals[-1], "mean_raw": float(np.mean(raws)),
            "max_raw": float(np.max(raws)), "raws": raws}


def feature_auc(control_feats: list[list[dict]], dys_feats: list[list[dict]]) -> dict:
    out = {}
    keys = ["jitter", "shimmer", "hnr", "f0Std", "ems4hz", "pauseRatio", "wpm"]
    for k in keys:
        c = np.array([np.mean([w.get(k, 0.0) for w in clip]) if clip else 0.0 for clip in control_feats])
        d = np.array([np.mean([w.get(k, 0.0) for w in clip]) if clip else 0.0 for clip in dys_feats])
        y = np.array([0] * len(c) + [1] * len(d))
        x = np.concatenate([c, d])
        if len(np.unique(y)) < 2 or np.allclose(x, x[0]):
            out[k] = float("nan")
            continue
        out[k] = roc_auc_score(y, x)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dataset", default="torgo", choices=["torgo", "pathological"])
    ap.add_argument("--outdir", default=os.path.join(HERE, "out"))
    ap.add_argument("--metric", default="mean_raw", choices=["final", "mean_raw", "max_raw"])
    args = ap.parse_args()

    rows = [r for r in load_manifest() if r["dataset"] == args.dataset]
    control = [r for r in rows if r["label"] in ("healthy", "control")]
    dys = [r for r in rows if r["label"] in ("dysarthria", "dysarthric")]
    if args.limit:
        control = control[: args.limit]
        dys = dys[: args.limit]
    print(f"{args.dataset}: control={len(control)} dysarthric={len(dys)}", flush=True)

    cache: dict = {}
    if os.path.exists(os.path.join(HERE, "cache.pkl")):
        with open(os.path.join(HERE, "cache.pkl"), "rb") as f:
            cache = pickle.load(f)

    def feats(group):
        return [extract(r, cache) for r in tqdm(group, desc="feats")]

    control_windows = [w for cw in feats(control) for w in cw]
    baseline = det.build_baseline(control_windows)
    print(f"baseline samples={baseline.sample_count}", flush=True)

    control_f = feats(control)
    dys_f = feats(dys)
    control_meta = [clip_scores(w, baseline) for w in control_f]
    dys_meta = [clip_scores(w, baseline) for w in dys_f]

    with open(os.path.join(HERE, "cache.pkl"), "wb") as f:
        pickle.dump(cache, f)

    def metric(meta):
        return [m[args.metric] for m in meta]

    c_s = np.array(metric(control_meta))
    d_s = np.array(metric(dys_meta))
    y = np.array([0] * len(c_s) + [1] * len(d_s))
    s = np.concatenate([c_s, d_s])
    auc = roc_auc_score(y, s)
    fpr, tpr, _ = roc_curve(y, s)

    sev_map = {"": np.nan, "normal": 0, "mild": 1, "moderate": 2, "severe": 3}
    rho, p = float("nan"), float("nan")
    if args.dataset == "pathological":
        sev_scores = []
        for r, m in zip(dys, dys_meta):
            sev = sev_map.get(r["severity"], np.nan)
            if not np.isnan(sev):
                sev_scores.append((sev, m[args.metric]))
        if len(sev_scores) >= 10:
            sev_arr = np.array(sev_scores)
            rho, p = stats.spearmanr(sev_arr[:, 0], sev_arr[:, 1])
            print(f"severity vs score: Spearman rho={rho:.3f} p={p:.2e} (n={len(sev_scores)})")

    f_auc = feature_auc(control_f, dys_f)
    print(f"AUC({args.metric}) = {auc:.3f}")
    print("per-feature AUC:", {k: (round(v, 3) if not np.isnan(v) else None) for k, v in f_auc.items()})

    os.makedirs(args.outdir, exist_ok=True)
    plt.figure()
    plt.plot(fpr, tpr, label=f"AUC={auc:.3f}")
    plt.plot([0, 1], [0, 1], "--", color="gray")
    plt.xlabel("FPR"); plt.ylabel("TPR"); plt.legend()
    plt.title(f"{args.dataset} [{args.metric}]")
    plt.savefig(os.path.join(args.outdir, f"{args.dataset}_roc.png"), dpi=120)

    plt.figure()
    plt.boxplot([c_s, d_s], tick_labels=["Control", "Dysarthric"])
    plt.ylabel("Slur score")
    plt.title(f"{args.dataset}: control={c_s.mean():.3f} dys={d_s.mean():.3f}")
    plt.savefig(os.path.join(args.outdir, f"{args.dataset}_box.png"), dpi=120)

    if not np.isnan(rho):
        plt.figure()
        plt.scatter(sev_arr[:, 0], sev_arr[:, 1], alpha=0.3)
        plt.xlabel("Severity"); plt.ylabel("Slur score"); plt.title(f"rho={rho:.3f}")
        plt.savefig(os.path.join(args.outdir, f"{args.dataset}_severity.png"), dpi=120)

    with open(os.path.join(args.outdir, f"{args.dataset}_report.md"), "w") as f:
        f.write(f"# {args.dataset} slur-detection evaluation\n\n")
        f.write(f"- control clips: {len(c_s)}, dysarthric clips: {len(d_s)}\n")
        f.write(f"- control mean score: {c_s.mean():.4f}\n")
        f.write(f"- dysarthric mean score: {d_s.mean():.4f}\n")
        f.write(f"- AUC ({args.metric}, control vs dysarthric): **{auc:.3f}**\n")
        f.write(f"- severity vs score Spearman rho: {rho:.3f} (p={p:.2e})\n")
        f.write("- per-feature AUC (control vs dysarthric):\n")
        for k, v in f_auc.items():
            f.write(f"  - {k}: {round(v, 3) if not np.isnan(v) else 'n/a'}\n")
    print(f"report -> {report_path}" if (report_path := os.path.join(args.outdir, f"{args.dataset}_report.md")) else "")


if __name__ == "__main__":
    sys.exit(main())