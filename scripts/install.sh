#!/usr/bin/env bash
# Installs (or updates) Compound on an Android phone over ADB from a Mac or Linux machine.
# ADB installs aren't subject to Play Protect's block on sideloaded apps that read SMS.
#
#   ./scripts/install.sh              # latest SMS edition (reads the inbox directly)
#   ./scripts/install.sh v1.0.5       # a specific release
set -euo pipefail

REPO="AkshaySiv/FinApp"
TAG="${1:-latest}"
[ "$TAG" = "--sms" ] && TAG="latest"

if ! command -v adb >/dev/null; then
  echo "adb not found. On a Mac: brew install android-platform-tools"
  exit 1
fi

devices=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$devices" = "0" ]; then
  echo "No phone connected. Turn on USB debugging (Settings > About phone > tap Build number 7 times,"
  echo "then Settings > System > Developer options > USB debugging), plug in, and accept the prompt on the phone."
  adb devices
  exit 1
fi

if [ "$TAG" = "latest" ]; then
  api="https://api.github.com/repos/$REPO/releases/latest"
else
  api="https://api.github.com/repos/$REPO/releases/tags/$TAG"
fi
url=$(curl -fsSL "$api" | grep -o '"browser_download_url": *"[^"]*\.apk"' | grep -- '-sms-' | head -1 | sed 's/.*"\(https[^"]*\)"/\1/')
[ -n "$url" ] || { echo "No APK found for $TAG"; exit 1; }

apk="$(mktemp -d)/$(basename "$url")"
echo "Downloading $(basename "$url")..."
curl -fsSL -o "$apk" "$url"

echo "Installing on the phone (your data is kept when updating)..."
adb install -r "$apk"
echo "Done. Open Compound on the phone and tap 'Allow SMS access'."
