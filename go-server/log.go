package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"
)

type logLevel = slog.Level

const (
	levelDebug = slog.LevelDebug
	levelInfo  = slog.LevelInfo
	levelWarn  = slog.LevelWarn
	levelError = slog.LevelError
)

var logLevelVar = newConfiguredLevelVar()

func newConfiguredLevelVar() *slog.LevelVar {
	level := new(slog.LevelVar)
	level.Set(levelDebug)
	return level
}

// appLog writes leveled logs to stdout, which is the container log sink. A
// TextHandler is enough here; no third-party logging dependency is needed.
var appLog = slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
	Level: logLevelVar,
	// Keep timestamps in UTC so container logs do not depend on the host zone.
	ReplaceAttr: func(groups []string, attr slog.Attr) slog.Attr {
		if len(groups) == 0 && attr.Key == slog.TimeKey {
			if value, ok := attr.Value.Any().(time.Time); ok {
				attr.Value = slog.TimeValue(value.UTC())
			}
		}
		return attr
	},
}))

func setLogLevel(level slog.Level) {
	logLevelVar.Set(level)
}

func parseLogLevel(raw string) (slog.Level, error) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "", "debug":
		return slog.LevelDebug, nil
	case "info":
		return slog.LevelInfo, nil
	case "warn", "warning":
		return slog.LevelWarn, nil
	case "error":
		return slog.LevelError, nil
	default:
		return 0, fmt.Errorf("LOG_LEVEL must be debug, info, warn or error, got: %s", raw)
	}
}

// The printf-style helpers keep existing call sites readable. slog still owns
// level filtering, timestamp formatting and concurrent writes.
func debugf(format string, args ...any) { logf(slog.LevelDebug, format, args...) }
func infof(format string, args ...any)  { logf(slog.LevelInfo, format, args...) }
func warnf(format string, args ...any)  { logf(slog.LevelWarn, format, args...) }
func errorf(format string, args ...any) { logf(slog.LevelError, format, args...) }

func logf(level slog.Level, format string, args ...any) {
	ctx := context.Background()
	if !appLog.Enabled(ctx, level) {
		return
	}
	appLog.Log(ctx, level, fmt.Sprintf(format, args...))
}

func logPreview(payload []byte, limit int) string {
	text := strings.TrimSpace(string(payload))
	if len(text) <= limit {
		return text
	}
	return fmt.Sprintf("%s... (%dB)", text[:limit], len(payload))
}
