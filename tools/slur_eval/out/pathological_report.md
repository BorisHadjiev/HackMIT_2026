# pathological slur-detection evaluation

- control clips: 2000, dysarthric clips: 2000
- control mean score: 0.0973
- dysarthric mean score: 0.1802
- AUC (mean_raw, control vs dysarthric): **0.683**
- severity vs score Spearman rho: -0.244 (p=2.19e-28)
- per-feature AUC (control vs dysarthric):
  - jitter: 0.682
  - shimmer: 0.62
  - hnr: 0.483
  - f0Std: 0.613
  - ems4hz: 0.536
  - pauseRatio: 0.603
  - wpm: 0.474
