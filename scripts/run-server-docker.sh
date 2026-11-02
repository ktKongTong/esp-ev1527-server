#!/usr/bin/env bash
# Build (if needed) and run the Kotlin/Native server inside a Linux container.
set -euo pipefail

cd "$(dirname "$0")/.."

BIN_DIR="$PWD/server/build/bin/linuxArm64/debugExecutable"

if [ ! -x "$BIN_DIR/esp32-control-server.kexe" ]; then
    "$PWD/scripts/build-server.sh"
fi

# Remove a stale container from a previous run, if any.
docker rm -f esp32-control-server >/dev/null 2>&1 || true

exec docker run --rm --name esp32-control-server \
    -p 8080:8080 \
    -e SERVER_HOST=0.0.0.0 \
    -e SERVER_PORT=8080 \
    -v "$BIN_DIR:/app:ro" \
    debian:trixie-slim /app/esp32-control-server.kexe
