# Slur-detection validation

Offline evaluation of the app's `SlurDetector` (ported to Python in
`detector.py`/`features.py`) on public dysarthria corpora. This is a faithful
port of `app/src/main/java/com/hackmit/app/audio/{SlurDetector,Dsp}.kt`.

## Data
- TORGO (`abnerh/TORGO-database`, HF): 2000 healthy + 2000 dysarthric clips.
- pathological_speech (`resproj007/pathological_speech`, HF): TORGO + UA-Speech +
  LibriSpeech, 2000 control + 2000 dysarthric, with severity labels.
- Both are public (no HF account needed). Academic use; cite Rudzicz et al. 2012
  (TORGO) and the UA-Speech paper.

## Method
- 2 s windows → features: jitter, shimmer, HNR, f0 std, 4 Hz envelope modulation,
  adaptive pause ratio, WPM.
- Baseline = pooled control windows (leave-speaker-out). Each clip scored with the
  app's z-score + EWMA detector; the per-window raw z-mean is the clip metric.
- Metrics: AUC (control vs dysarthric), per-feature AUC, score-vs-severity
  Spearman (pathological set).

## Results
- TORGO: AUC ≈ **0.54** (near chance; TORGO short words are mostly mild).
- pathological: AUC ≈ **0.62**; per-feature AUCs: f0Std 0.61, hnr 0.61, pause 0.60,
  jitter 0.60, shimmer 0.54.
- Severity correlation is weak/negative on the pathological set: severely
  unintelligible speech often loses voicing entirely, so pitch-based
  jitter/shimmer/HNR collapse toward zero and the detector under-scores it.

## Validation of the DSP port
A clean 200 Hz tone yields f0=200.0 Hz, jitter=0.000, shimmer=0.000, HNR=40 dB;
a frequency-modulated tone yields jitter≈0.009 — the port matches the app.

## Honest interpretation
The detector is designed for **within-person change detection** (your baseline
vs. *now*), not population screening. Cross-sectional dysarthria evaluation is
therefore a hard, confounded test: between-speaker variance inflates baseline
std, and severe dysarthria loses voicing. The strongest demonstration is the
**in-app demo** (healthy baseline → slurred clip), which shows the score rising
and triggering an alert on the exact algorithm the phone runs.

## Synthetic acute slurring
`synthesize_slur.py` makes stroke-like slurred audio from a healthy clip
(time-stretch + pitch restore + low-pass), used for the in-app demo and as an
acute-slurring supplement.

## Re-run
```bash
.venv/bin/python download_data.py   # one-time download (public)
.venv/bin/python evaluate.py --dataset torgo
.venv/bin/python evaluate.py --dataset pathological
```