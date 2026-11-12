#!/bin/sh
# Re-takes the phone screenshots in docs/screens from the fake projector (tools/demo_server.py).
# Needs Google Chrome or Chromium. Usage: tools/screenshots.sh
set -eu
cd "$(dirname "$0")/.."
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
command -v "$CHROME" >/dev/null 2>&1 || [ -x "$CHROME" ] || CHROME="$(command -v chromium || command -v google-chrome)"
OUT=docs/screens
PORT=8099
UA="Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

mkdir -p "$OUT"
python3 tools/demo_server.py --port "$PORT" >/dev/null 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
sleep 1

for scene in pair pin done remote touchpad mouse apps edit projector settings guest screens search type lost; do
  PROFILE="$(mktemp -d)"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --no-first-run --user-data-dir="$PROFILE" \
    --window-size=390,844 --force-device-scale-factor=2 --user-agent="$UA" \
    --virtual-time-budget=9000 --screenshot="$OUT/phone-$scene.png" \
    "http://localhost:$PORT/frame?demo=$scene" >/dev/null 2>&1
  rm -rf "$PROFILE"
  echo "$OUT/phone-$scene.png"
done
