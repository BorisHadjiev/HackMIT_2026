#!/usr/bin/env bash
# Install the StrokeSense alert gateway as a *user* systemd service.
# No sudo required for the service itself; lingering needs one root command.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${STROKESENSE_DIR:-$HOME/strokesense-gateway}"
UNIT_DIR="$HOME/.config/systemd/user"

mkdir -p "$TARGET" "$UNIT_DIR"
cp -r "$REPO_DIR/gateway" "$TARGET/"
cp "$REPO_DIR/requirements.txt" "$TARGET/"
if [[ ! -f "$TARGET/.env" ]]; then
  cp "$REPO_DIR/.env.example" "$TARGET/.env"
  echo "Created $TARGET/.env from the example. Fill it in before starting."
fi

python3 -m venv "$TARGET/.venv"
"$TARGET/.venv/bin/pip" install --upgrade pip
"$TARGET/.venv/bin/pip" install -r "$TARGET/requirements.txt"

cp "$REPO_DIR/deploy/strokesense-gateway.service" "$UNIT_DIR/"
systemctl --user daemon-reload
systemctl --user enable --now strokesense-gateway.service

echo
echo "Installed. Check status with:"
echo "  systemctl --user status strokesense-gateway"
echo "  curl -s http://127.0.0.1:8000/healthz"
echo
echo "To survive logout/reboot, run once:  sudo loginctl enable-linger $USER"
