package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func clearConfigEnv(t *testing.T) {
	t.Helper()
	for _, name := range []string{
		"CONFIG_FILE", "SERVER_HOST", "SERVER_PORT", "MQTT_HOST", "MQTT_PORT",
		"MQTT_USERNAME", "MQTT_PASSWORD", "MQTT_CLIENT_ID", "MQTT_TOPIC_PREFIX",
		"MQTT_TRANSPORT", "MQTT_TLS", "MQTT_WS_PATH", "TURSO_DATABASE_URL",
		"DATABASE_URL", "TURSO_AUTH_TOKEN", "GATEWAY_CLIENT_ID", "LOG_LEVEL",
	} {
		t.Setenv(name, "")
	}
}

func TestParseConfigFile(t *testing.T) {
	values := parseConfigFile(`
# comment
export MQTT_HOST=broker.example.com
MQTT_PORT = 1883
QUOTED="value with spaces"
SINGLE='literal'
INVALID LINE
=missing-key
`)
	if values["MQTT_HOST"] != "broker.example.com" {
		t.Fatalf("MQTT_HOST = %q", values["MQTT_HOST"])
	}
	if values["MQTT_PORT"] != "1883" {
		t.Fatalf("MQTT_PORT = %q", values["MQTT_PORT"])
	}
	if values["QUOTED"] != "value with spaces" {
		t.Fatalf("QUOTED = %q", values["QUOTED"])
	}
	if values["SINGLE"] != "literal" {
		t.Fatalf("SINGLE = %q", values["SINGLE"])
	}
}

func TestLoadConfigPrecedence(t *testing.T) {
	clearConfigEnv(t)
	path := filepath.Join(t.TempDir(), "server.env")
	content := "MQTT_HOST=file-broker\nSERVER_PORT=9000\nLOG_LEVEL=info\nTURSO_AUTH_TOKEN=file-token\n"
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", path)
	t.Setenv("MQTT_HOST", "env-broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://env.turso.io")

	cfg, err := loadConfig()
	if err != nil {
		t.Fatalf("loadConfig: %v", err)
	}
	if cfg.MQTT.Host != "env-broker" {
		t.Fatalf("MQTT.Host = %q, want env-broker", cfg.MQTT.Host)
	}
	if cfg.Database.URL != "libsql://env.turso.io" {
		t.Fatalf("Database.URL = %q, want env URL", cfg.Database.URL)
	}
	if cfg.Database.Token != "file-token" {
		t.Fatalf("Database.Token = %q, want file-token", cfg.Database.Token)
	}
	if strings.Contains(cfg.Database.String(), "file-token") {
		t.Fatalf("Database.String leaked the auth token: %s", cfg.Database)
	}
	if cfg.Port != 9000 {
		t.Fatalf("Port = %d, want 9000", cfg.Port)
	}
	if cfg.LogLevel != levelInfo {
		t.Fatalf("LogLevel = %v, want info", cfg.LogLevel)
	}
	if cfg.CommandTopic != "home/OpenMQTTGateway/commands/MQTTto433" {
		t.Fatalf("CommandTopic = %q", cfg.CommandTopic)
	}
	if cfg.SubscribedTopic != "home/OpenMQTTGateway/#" {
		t.Fatalf("SubscribedTopic = %q", cfg.SubscribedTopic)
	}
}

func TestLoadConfigRequiresMQTTHost(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	_, err := loadConfig()
	if err == nil || !strings.Contains(err.Error(), "MQTT_HOST") {
		t.Fatalf("expected an MQTT_HOST error, got: %v", err)
	}
}

func TestLoadConfigRequiresDatabaseURL(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	_, err := loadConfig()
	if err == nil || !strings.Contains(err.Error(), "TURSO_DATABASE_URL") {
		t.Fatalf("expected a TURSO_DATABASE_URL error, got: %v", err)
	}
}

func TestLoadConfigAcceptsDatabaseURLAlias(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("DATABASE_URL", "libsql://alias.turso.io")

	cfg, err := loadConfig()
	if err != nil {
		t.Fatalf("loadConfig: %v", err)
	}
	if cfg.Database.URL != "libsql://alias.turso.io" {
		t.Fatalf("Database.URL = %q, want alias URL", cfg.Database.URL)
	}
}

func TestLoadConfigRejectsRawTCP(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	t.Setenv("MQTT_TLS", "true")
	if _, err := loadConfig(); err == nil {
		t.Fatal("expected an error for MQTT_TLS without websocket transport")
	}
}

func TestLoadConfigWebSocketURL(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	t.Setenv("MQTT_PORT", "8084")
	t.Setenv("MQTT_TRANSPORT", "websocket")
	t.Setenv("MQTT_TLS", "true")
	t.Setenv("MQTT_WS_PATH", "/mqtt")

	cfg, err := loadConfig()
	if err != nil {
		t.Fatalf("loadConfig: %v", err)
	}
	if got := brokerURL(cfg.MQTT); got != "wss://broker:8084/mqtt" {
		t.Fatalf("brokerURL = %q", got)
	}
	if !strings.Contains(cfg.MQTT.String(), "host=broker") {
		t.Fatalf("unexpected MQTT config string: %s", cfg.MQTT)
	}
}
