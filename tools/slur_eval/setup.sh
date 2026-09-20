#!/usr/bin/env bash
# Creates the slur_eval venv and installs Python deps on gx10.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

python3 -m venv .venv
.venv/bin/pip install --quiet --upgrade pip
# datasets>=3.2 requires torchcodec (torch) to decode audio; 2.x uses soundfile.
.venv/bin/pip install --quiet \
  "datasets==2.21.0" "soundfile" "librosa" "scikit-learn" \
  "matplotlib" "jiwer" "numpy" "scipy" "tqdm"
.venv/bin/pip install --quiet --no-deps "parselmouth"
echo "setup complete"