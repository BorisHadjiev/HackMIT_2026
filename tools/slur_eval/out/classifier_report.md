## torgo (speakers=5, n=4000)
- detector (z-score, fold-local baseline): AUC **0.694**
- handcrafted LR: AUC 0.768
- ssl LR: AUC 0.997
- fusion LR: AUC **0.997**

## pathological (speakers=12, n=4000)
- detector (z-score, fold-local baseline): AUC **0.716**
- handcrafted LR: AUC 0.663
- ssl LR: AUC 0.945
- fusion LR: AUC **0.951**
- fusion severity Spearman: 0.194 (p=2.21e-18, n=2000)

