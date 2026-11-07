#!/usr/bin/env bash
# Build every deliverable in one pass.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME

./gradlew \
    :androidApp:assembleDebug \
    :webApp:wasmJsBrowserDistribution \
    --console=plain

(cd go-server && CGO_ENABLED=1 go build -o build/esp32-control-server .)

echo
echo "Server: go-server/build/esp32-control-server"
echo "APK:    androidApp/build/outputs/apk/debug/androidApp-debug.apk"
echo "Web:    webApp/build/dist/wasmJs/productionExecutable/index.html"
