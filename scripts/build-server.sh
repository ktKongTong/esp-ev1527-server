#!/usr/bin/env bash
# Build the Linux/arm64 Kotlin/Native server binary.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME

./gradlew :server:linkDebugExecutableLinuxArm64 --console=plain

echo
echo "Binary: server/build/bin/linuxArm64/debugExecutable/esp32-control-server.kexe"
