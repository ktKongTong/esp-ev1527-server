#!/usr/bin/env bash
# Build the Wasm production bundle and serve it on http://127.0.0.1:8081.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME

./gradlew :webApp:wasmJsBrowserDistribution --console=plain

DIST="$PWD/webApp/build/dist/wasmJs/productionExecutable"
PORT="${WEB_PORT:-8081}"

echo
echo "Serving $DIST at http://127.0.0.1:$PORT/"
exec python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$DIST"
