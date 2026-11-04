package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParseDotEnv(t *testing.T) {
	content := `
# comment
MQTT_URL=tcp://localhost:1883
export MQTT_USERNAME="alice"
MQTT_PASSWORD='secret'
EMPTY=
WITH_EQUALS=tcp://host:1883/path?a=b
INVALID_LINE
=missing-key
`
	values := parseDotEnv(content)

	want := map[string]string{
		"MQTT_URL":      "tcp://localhost:1883",
		"MQTT_USERNAME": "alice",
		"MQTT_PASSWORD": "secret",
		"EMPTY":         "",
		"WITH_EQUALS":   "tcp://host:1883/path?a=b",
	}
	for key, expected := range want {
		if got := values[key]; got != expected {
			t.Errorf("parseDotEnv()[%q] = %q, want %q", key, got, expected)
		}
	}
}

func TestReadDotEnv(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".env")
	if err := os.WriteFile(path, []byte("MQTT_CLIENT_ID=from-file\n"), 0o600); err != nil {
		t.Fatalf("write .env: %v", err)
	}

	values, ok := readDotEnv(path)
	if !ok {
		t.Fatal("readDotEnv() did not load the file")
	}
	if got := values["MQTT_CLIENT_ID"]; got != "from-file" {
		t.Fatalf("MQTT_CLIENT_ID = %q, want %q", got, "from-file")
	}

	if _, ok := readDotEnv(filepath.Join(t.TempDir(), "missing.env")); ok {
		t.Fatal("readDotEnv() reported a missing file as loaded")
	}
}

func TestEnvOrFilePrecedence(t *testing.T) {
	const key = "OMG_TEST_ENV_PRECEDENCE"
	fileValues := map[string]string{key: "from-file"}

	t.Setenv(key, "from-env")
	if got := envOrFile(key, "default", fileValues); got != "from-env" {
		t.Fatalf("envOrFile() = %q, want environment value", got)
	}

	_ = os.Unsetenv(key)
	if got := envOrFile(key, "default", fileValues); got != "from-file" {
		t.Fatalf("envOrFile() = %q, want file value", got)
	}

	if got := envOrFile("OMG_TEST_ENV_MISSING", "default", fileValues); got != "default" {
		t.Fatalf("envOrFile() = %q, want default value", got)
	}
}
