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

## Results (voiced-gated DSP)
- TORGO: AUC ≈ **0.62** (control vs dysarthric, leave-speaker-out).
- pathological: AUC ≈ **0.68**; strongest features: jitter 0.68, f0Std 0.61,
  shimmer 0.62, hnr 0.48.
- Severity correlation is weak/negative on the pathological set: severely
  unintelligible speech often loses voicing entirely, so pitch-based
  jitter/shimmer/HNR collapse toward zero and the detector under-scores it.
- These numbers use the **fixed voiced-gated sub-frame DSP** (pitch/jitter/HNR
  on voiced 40 ms frames) that shipped in the app — the earlier full-window
  DSP scored 0.48 / 0.62.

### Learned classifiers (speaker-disjoint, pooled leave-speaker-out AUC)
SSL embeddings are **mean+std pooled last-hidden-state** of WavLM-base-plus.

| Corpus | Detector (z) | Handcrafted LR | **SSL LR** | **Fusion LR** |
| --- | --- | --- | --- | --- |
| TORGO (5 spk) | 0.69 | 0.77 | **0.997** | **0.997** |
| pathological (12 spk) | 0.72 | 0.66 | **0.945** | **0.951** |

- Severity Spearman on pathological (fusion): **0.19** (positive, n=2000).
- Model params exported to `slur_classifier_{torgo,pathological}.json`
  (StandardScaler + LogisticRegression over handcrafted + 1536-d SSL features);
  see `ssl_embed.py` and `train_classifier.py`.

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

## Personal-mode calibration (fixes phone-mic over-reporting)

The SSL LR (AUC 0.945) is trained on corpus audio. Live phone-mic speech is
out-of-domain: its embedding sits far from the corpus, so the LR saturates at
~1.0 for *normal* phone speech.

Fix: enroll the user's voice (≥8 s) and shift live embeddings onto the corpus
**healthy centroid** before the same LR — `z' = z - user_centroid + healthy_centroid`
(`healthy_centroid_z` is baked into the exported model JSONs). This is a
cepstral-mean-style domain anchor: the user's phone domain maps onto healthy,
and real slur deviates from it.

Validated on TORGO (same speaker, FC01):

| clip | corpus | personal |
| --- | --- | --- |
| enrolled healthy | 0.0002 | 0.000 |
| healthy, different content | 0.001 | 0.000 |
| dysarthric | 1.000 | 1.000 |
| synthesized acute slur | 1.000 | 0.9996 |

Gateways expose this as `POST /v1/slur/calibrate` (switches `analyze` to
`"mode": "personal"`).

## Re-run
```bash
.venv/bin/python download_data.py   # one-time download (public)
.venv/bin/python evaluate.py --dataset torgo
.venv/bin/python evaluate.py --dataset pathological
```