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

# MQTT is mandatory; the server has no offline fallback.
: "${MQTT_HOST:?set MQTT_HOST to the broker host reachable from the container}"

network_args=()
if [ -n "${MQTT_NETWORK:-}" ]; then
    network_args+=(--network "$MQTT_NETWORK")
fi

auth_args=()
if [ -n "${MQTT_USERNAME:-}" ]; then
    auth_args+=(-e "MQTT_USERNAME=$MQTT_USERNAME")
fi
if [ -n "${MQTT_PASSWORD:-}" ]; then
    auth_args+=(-e "MQTT_PASSWORD=$MQTT_PASSWORD")
fi

# Keep the SQLite database on the host so it survives container restarts.
mkdir -p "$PWD/.data"

exec docker run --rm --name esp32-control-server \
    -p 8080:8080 \
    ${network_args[@]+"${network_args[@]}"} \
    -e SERVER_HOST=0.0.0.0 \
    -e SERVER_PORT=8080 \
    -e DATABASE_PATH=/data/esp32-control.db \
    -e MQTT_HOST="$MQTT_HOST" \
    -e MQTT_PORT="${MQTT_PORT:-1883}" \
    ${auth_args[@]+"${auth_args[@]}"} \
    -v "$BIN_DIR:/app:ro" \
    -v "$PWD/.data:/data" \
    debian:trixie-slim /app/esp32-control-server.kexe
