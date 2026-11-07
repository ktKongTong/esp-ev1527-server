# syntax=docker/dockerfile:1
#
# Go server image for linux/amd64 and linux/arm64. Build from the repository
# root:
#
#   docker buildx build \
#     --platform linux/amd64,linux/arm64 \
#     -f go-server/Dockerfile \
#     -t ghcr.io/ktkongtong/esp-ev1527-server:latest --push .
#
# The GORM sqlite dialector imports mattn/go-sqlite3, which requires CGO even
# though production connects to Turso through the pure-Go serverless driver.
# Each target platform therefore builds natively with its own toolchain.
# Cross-compiling CGO would need a second compiler; this module is small enough
# that buildx emulation is cheaper to maintain.

FROM golang:1.26-bookworm AS build

RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY go-server/go.mod go-server/go.sum ./
RUN go mod download
COPY go-server/*.go ./
RUN CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/esp32-control-server .

FROM debian:bookworm-slim

# ca-certificates is needed for Turso Cloud and MQTT over WSS; wget backs
# HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --home-dir /nonexistent \
         --shell /usr/sbin/nologin app \
    && mkdir -p /app

COPY --from=build /out/esp32-control-server /usr/local/bin/esp32-control-server

USER app
WORKDIR /app

# MQTT_HOST, TURSO_DATABASE_URL, and TURSO_AUTH_TOKEN have no defaults and
# must be supplied at runtime. The database is remote, so the container needs
# no persistent volume.
ENV SERVER_HOST=0.0.0.0 \
    SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/api/v1/health" || exit 1

ENTRYPOINT ["/usr/local/bin/esp32-control-server"]
