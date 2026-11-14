#!/usr/bin/env bash
# Family Wealth — start the local app on your Mac.
set -euo pipefail
cd "$(dirname "$0")"

PORT="${FW_PORT:-8765}"
DATA_DIR="${FW_DATA_DIR:-$HOME/Library/Application Support/FamilyWealth}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found. Install it with:  xcode-select --install   (or: brew install python)"
  exit 1
fi

if [ ! -x .venv/bin/python ]; then
  echo "Creating Python environment…"
  rm -rf .venv
  python3 -m venv .venv
fi

# (Re)install dependencies whenever any are missing — also repairs an interrupted first run.
if ! ./.venv/bin/python -c "import fastapi, uvicorn, cryptography" >/dev/null 2>&1; then
  echo "Installing dependencies (one-time, ~30s)…"
  ./.venv/bin/python -m pip install --upgrade pip
  if ! ./.venv/bin/python -m pip install -r requirements.txt; then
    echo ""
    echo "Dependency install failed. Check your internet connection and the error above."
    echo "Python in use: $(./.venv/bin/python --version)"
    exit 1
  fi
fi

echo ""
echo "  Family Wealth is running →  http://127.0.0.1:${PORT}"
echo "  Encrypted data folder     →  ${DATA_DIR}"
echo "  Press Ctrl+C to stop (this also locks the vault)."
echo ""

( sleep 1.5 && open "http://127.0.0.1:${PORT}" >/dev/null 2>&1 || true ) &
export FW_PORT="$PORT" FW_DATA_DIR="$DATA_DIR"
exec ./.venv/bin/python -m backend.app
