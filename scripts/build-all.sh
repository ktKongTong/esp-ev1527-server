#!/usr/bin/env bash
# Build every deliverable in one pass.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME

./gradlew \
    :server:linkDebugExecutableLinuxArm64 \
    :androidApp:assembleDebug \
    :webApp:wasmJsBrowserDistribution \
    --console=plain

echo
echo "Server: server/build/bin/linuxArm64/debugExecutable/esp32-control-server.kexe"
echo "APK:    androidApp/build/outputs/apk/debug/androidApp-debug.apk"
echo "Web:    webApp/build/dist/wasmJs/productionExecutable/index.html"
