#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
echo "Starting BotHive Desktop v0.7.2..."
if command -v open >/dev/null 2>&1; then
  (sleep 1 && open "http://127.0.0.1:7718") &
elif command -v xdg-open >/dev/null 2>&1; then
  (sleep 1 && xdg-open "http://127.0.0.1:7718") &
fi
exec python3 "$DIR/bothive_pc.py" --port 7718
