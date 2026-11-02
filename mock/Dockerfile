# syntax=docker/dockerfile:1
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/omg315-mock .

FROM alpine:3.21
RUN apk add --no-cache ca-certificates \
    && adduser -D -u 10001 mock
COPY --from=build /out/omg315-mock /usr/local/bin/omg315-mock
USER mock
EXPOSE 8099
HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8099/mock/health >/dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/omg315-mock"]
