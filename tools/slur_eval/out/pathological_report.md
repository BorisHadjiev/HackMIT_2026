# pathological slur-detection evaluation

- control clips: 2000, dysarthric clips: 2000
- control mean score: 0.0922
- dysarthric mean score: 0.1421
- AUC (mean_raw, control vs dysarthric): **0.621**
- severity vs score Spearman rho: -0.210 (p=2.19e-21)
- per-feature AUC (control vs dysarthric):
  - jitter: 0.6
  - shimmer: 0.538
  - hnr: 0.611
  - f0Std: 0.613
  - ems4hz: 0.536
  - pauseRatio: 0.603
  - wpm: 0.474
