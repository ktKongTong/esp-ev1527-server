# syntax=docker/dockerfile:1
#
# Server image for linux/amd64 and linux/arm64. Build from the repository
# root with buildx:
#
#   docker buildx build \
#     --platform linux/amd64,linux/arm64 \
#     --secret id=github_actor,env=GITHUB_ACTOR \
#     --secret id=github_token,env=GITHUB_TOKEN \
#     -t <registry>/esp32-control-server:latest --push .
#
# buildx injects TARGETOS/TARGETARCH for each --platform; the build stage
# maps them to the matching Kotlin/Native target. `--load` accepts only one
# platform; a multi-platform result must be pushed to a registry.
#
# Kotlin/Native emits Linux executables linked against glibc (the ELF
# interpreter is /lib/ld-linux-*.so.*), and the compiler itself runs on a
# glibc host. The build stage therefore uses a glibc JDK image; the runtime
# stage is Alpine with gcompat providing the glibc loader and libc shims.

# Kotlin/Native supports Linux x86_64 as a compiler host, but not Linux
# arm64. The build stage therefore runs on linux/amd64 and cross-compiles both
# linux/amd64 and linux/arm64 targets. On an arm64 host this stage runs under
# QEMU, so a native amd64 builder is faster. This is intentionally not
# $BUILDPLATFORM: on arm64 hosts that would select an unsupported compiler
# host.
ARG BUILD_PLATFORM=linux/amd64
FROM --platform=$BUILD_PLATFORM eclipse-temurin:17-jdk-jammy AS build

# Automatic buildx platform args. Every supported platform must map to a
# Kotlin/Native executable declared in server/build.gradle.kts.
ARG TARGETOS
ARG TARGETARCH

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Only the projects needed by :server are copied. The remaining modules
# included by settings.gradle.kts stay empty, so they configure trivially and
# the Android SDK is not needed. Gradle 9 rejects included projects whose
# directories do not exist, hence the empty directories below.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY contract ./contract
COPY server ./server

RUN chmod +x gradlew \
    && mkdir -p shared androidApp webApp

# ktor-mqtt is published on GitHub Packages, so dependency resolution needs
# credentials. BuildKit secrets keep them out of image layers and history.
RUN --mount=type=secret,id=github_actor \
    --mount=type=secret,id=github_token \
    --mount=type=cache,target=/root/.gradle,sharing=locked \
    --mount=type=cache,target=/root/.konan,sharing=locked \
    { test -s /run/secrets/github_actor \
      || { echo "missing secret github_actor (pass --secret id=github_actor,env=GITHUB_ACTOR)" >&2; exit 1; }; } \
    && { test -s /run/secrets/github_token \
      || { echo "missing secret github_token (pass --secret id=github_token,env=GITHUB_TOKEN)" >&2; exit 1; }; } \
    && case "$TARGETOS/$TARGETARCH" in \
         linux/arm64) task=linkReleaseExecutableLinuxArm64; out=linuxArm64 ;; \
         linux/amd64) task=linkReleaseExecutableLinuxX64;   out=linuxX64 ;; \
         *) echo "unsupported target platform: ${TARGETOS:-?}/${TARGETARCH:-?}" \
                 "(supported: linux/amd64, linux/arm64)" >&2; exit 1 ;; \
       esac \
    && GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
       GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
       ./gradlew ":server:$task" --no-daemon --console=plain \
    && install -D -m 0755 \
         "server/build/bin/$out/releaseExecutable/esp32-control-server.kexe" \
         /out/server.kexe

FROM alpine:3.24

# gcompat provides the glibc loader and libc/libpthread/etc. shims the
# Kotlin/Native binary expects; zlib and libgcc back its remaining dynamic
# dependencies. ca-certificates is required for MQTT over WSS.
RUN apk add --no-cache ca-certificates gcompat libgcc zlib \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && mkdir -p /data \
    && chown app:app /data

COPY --from=build /out/server.kexe /usr/local/bin/esp32-control-server

USER app
WORKDIR /data

# MQTT_HOST has no default and must be supplied at runtime; the server refuses
# to start without it. DATABASE_PATH points at the writable volume.
ENV SERVER_HOST=0.0.0.0 \
    SERVER_PORT=8080 \
    DATABASE_PATH=/data/esp32-control.db

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/api/v1/health" || exit 1

ENTRYPOINT ["/usr/local/bin/esp32-control-server"]
