#!/usr/bin/env bash
# Rename the app in one command.
#
#   tools/rename.sh "My New Name" [com.example.newid]
#
# Updates APP_NAME (launcher label / UI) and optionally APP_ID (applicationId).
# The internal Kotlin namespace stays com.hackmit.app on purpose, so no source
# files need to move when you rename the product.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/gradle.properties"

if [[ $# -lt 1 ]]; then
  echo "usage: tools/rename.sh \"New App Name\" [com.example.appid]" >&2
  exit 1
fi

NEW_NAME="$1"
NEW_ID="${2:-}"

# Escape sed replacement special chars.
esc() { printf '%s' "$1" | sed -e 's/[&/\]/\\&/g'; }

sed -i "s/^APP_NAME=.*/APP_NAME=$(esc "$NEW_NAME")/" "$PROPS"
if [[ -n "$NEW_ID" ]]; then
  sed -i "s/^APP_ID=.*/APP_ID=$(esc "$NEW_ID")/" "$PROPS"
fi

echo "Updated $PROPS:"
grep -E '^(APP_NAME|APP_ID)=' "$PROPS"
