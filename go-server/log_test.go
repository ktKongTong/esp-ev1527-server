package main

import (
	"context"
	"log/slog"
	"testing"
)

func TestParseLogLevel(t *testing.T) {
	cases := map[string]slog.Level{
		"":        slog.LevelDebug,
		"DEBUG":   slog.LevelDebug,
		" info ":  slog.LevelInfo,
		"warning": slog.LevelWarn,
		"error":   slog.LevelError,
	}
	for raw, want := range cases {
		got, err := parseLogLevel(raw)
		if err != nil {
			t.Fatalf("parseLogLevel(%q): %v", raw, err)
		}
		if got != want {
			t.Fatalf("parseLogLevel(%q) = %s, want %s", raw, got, want)
		}
	}
	if _, err := parseLogLevel("trace"); err == nil {
		t.Fatal("expected an error for an unsupported level")
	}
}

func TestLogLevelFiltering(t *testing.T) {
	t.Cleanup(func() { setLogLevel(levelDebug) })
	setLogLevel(levelInfo)

	ctx := context.Background()
	if appLog.Enabled(ctx, slog.LevelDebug) {
		t.Fatal("debug must be disabled at info level")
	}
	if !appLog.Enabled(ctx, slog.LevelInfo) {
		t.Fatal("info must be enabled at info level")
	}
}

func TestLogPreview(t *testing.T) {
	if got := logPreview([]byte("  abc  "), 10); got != "abc" {
		t.Fatalf("short preview = %q", got)
	}
	if got := logPreview([]byte("abcdefghij"), 4); got != "abcd... (10B)" {
		t.Fatalf("truncated preview = %q", got)
	}
}
