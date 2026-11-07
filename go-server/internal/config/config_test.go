package config

import (
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func clearConfigEnv(t *testing.T) {
	t.Helper()
	for _, name := range []string{
		"SERVER_HOST", "SERVER_PORT", "MQTT_HOST", "MQTT_PORT",
		"MQTT_USERNAME", "MQTT_PASSWORD", "MQTT_CLIENT_ID", "MQTT_TOPIC_PREFIX",
		"MQTT_TRANSPORT", "MQTT_TLS", "MQTT_WS_PATH", "TURSO_DATABASE_URL",
		"DATABASE_URL", "TURSO_AUTH_TOKEN", "AUTH_TOKEN", "GATEWAY_CLIENT_ID",
		"LOG_LEVEL",
	} {
		t.Setenv(name, "")
	}
}

func TestLoadConfigPrecedence(t *testing.T) {
	clearConfigEnv(t)
	dir := t.TempDir()
	t.Chdir(dir)
	content := "MQTT_HOST=file-broker\nSERVER_PORT=9000\nLOG_LEVEL=info\n" +
		"TURSO_AUTH_TOKEN=file-token\nAUTH_TOKEN=file-auth-token\n"
	if err := os.WriteFile(filepath.Join(dir, ".env"), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("MQTT_HOST", "env-broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://env.turso.io")
	t.Setenv("AUTH_TOKEN", "env-auth-token")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
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
	if cfg.Auth.Token != "env-auth-token" {
		t.Fatalf("Auth.Token = %q, want env-auth-token", cfg.Auth.Token)
	}
	if strings.Contains(cfg.Auth.String(), "env-auth-token") {
		t.Fatalf("Auth.String leaked the token: %s", cfg.Auth)
	}
	if cfg.Port != 9000 {
		t.Fatalf("Port = %d, want 9000", cfg.Port)
	}
	if cfg.LogLevel != slog.LevelInfo {
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
	_, err := LoadConfig()
	if err == nil || !strings.Contains(err.Error(), "MQTT_HOST") {
		t.Fatalf("expected an MQTT_HOST error, got: %v", err)
	}
}

func TestLoadConfigRequiresDatabaseURL(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	_, err := LoadConfig()
	if err == nil || !strings.Contains(err.Error(), "TURSO_DATABASE_URL") {
		t.Fatalf("expected a TURSO_DATABASE_URL error, got: %v", err)
	}
}

func TestLoadConfigRequiresAuthToken(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	_, err := LoadConfig()
	if err == nil || !strings.Contains(err.Error(), "AUTH_TOKEN") {
		t.Fatalf("expected an AUTH_TOKEN error, got: %v", err)
	}
}

func TestLoadConfigAcceptsDatabaseURLAlias(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("DATABASE_URL", "libsql://alias.turso.io")
	t.Setenv("AUTH_TOKEN", "test-auth-token")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.Database.URL != "libsql://alias.turso.io" {
		t.Fatalf("Database.URL = %q, want alias URL", cfg.Database.URL)
	}
}

func TestLoadConfigRejectsRawTCP(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	t.Setenv("AUTH_TOKEN", "test-auth-token")
	t.Setenv("MQTT_TLS", "true")
	if _, err := LoadConfig(); err == nil {
		t.Fatal("expected an error for MQTT_TLS without websocket transport")
	}
}

func TestLoadConfigWebSocket(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	t.Setenv("AUTH_TOKEN", "test-auth-token")
	t.Setenv("MQTT_PORT", "8084")
	t.Setenv("MQTT_TRANSPORT", "websocket")
	t.Setenv("MQTT_TLS", "true")
	t.Setenv("MQTT_WS_PATH", "/mqtt")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.MQTT.Transport != MQTTTransportWebSocket || !cfg.MQTT.UseTLS {
		t.Fatalf("unexpected transport config: %+v", cfg.MQTT)
	}
	if !strings.Contains(cfg.MQTT.String(), "host=broker") {
		t.Fatalf("unexpected MQTT config string: %s", cfg.MQTT)
	}
}

// The dotenv provider uses godotenv syntax: comments, export prefixes and
// quoted values are accepted, while a malformed file is reported instead of
// being partially applied.
func TestLoadConfigDotenvSyntax(t *testing.T) {
	clearConfigEnv(t)
	dir := t.TempDir()
	t.Chdir(dir)
	content := "# comment\n" +
		"export MQTT_HOST=broker.example.com\n" +
		"MQTT_PORT = 1883\n" +
		"MQTT_USERNAME=\"user with spaces\"\n" +
		"TURSO_DATABASE_URL=libsql://test.turso.io\n" +
		"AUTH_TOKEN=file-auth-token\n"
	if err := os.WriteFile(filepath.Join(dir, ".env"), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.MQTT.Host != "broker.example.com" {
		t.Fatalf("MQTT.Host = %q", cfg.MQTT.Host)
	}
	if cfg.MQTT.Port != 1883 {
		t.Fatalf("MQTT.Port = %d", cfg.MQTT.Port)
	}
	if cfg.MQTT.Username != "user with spaces" {
		t.Fatalf("MQTT.Username = %q", cfg.MQTT.Username)
	}
}

func TestLoadConfigWithoutDotenv(t *testing.T) {
	clearConfigEnv(t)
	t.Chdir(t.TempDir())
	t.Setenv("MQTT_HOST", "broker")
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	t.Setenv("AUTH_TOKEN", "test-auth-token")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.MQTT.Port != defaultMQTTPort {
		t.Fatalf("MQTT.Port = %d, want default %d", cfg.MQTT.Port, defaultMQTTPort)
	}
}

func TestLoadConfigIgnoresConfigFileEnv(t *testing.T) {
	clearConfigEnv(t)
	dir := t.TempDir()
	t.Chdir(dir)
	path := filepath.Join(dir, "server.env")
	if err := os.WriteFile(path, []byte("MQTT_HOST=file-broker\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CONFIG_FILE", path)
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	t.Setenv("AUTH_TOKEN", "test-auth-token")

	if _, err := LoadConfig(); err == nil || !strings.Contains(err.Error(), "MQTT_HOST") {
		t.Fatalf("expected MQTT_HOST to stay unset, got: %v", err)
	}
}

func TestLoadConfigDoesNotSearchParentDirectories(t *testing.T) {
	clearConfigEnv(t)
	root := t.TempDir()
	if err := os.WriteFile(
		filepath.Join(root, ".env"),
		[]byte("MQTT_HOST=parent-broker\n"),
		0o600,
	); err != nil {
		t.Fatal(err)
	}
	child := filepath.Join(root, "child")
	if err := os.Mkdir(child, 0o700); err != nil {
		t.Fatal(err)
	}
	t.Chdir(child)
	t.Setenv("TURSO_DATABASE_URL", "libsql://test.turso.io")
	t.Setenv("AUTH_TOKEN", "test-auth-token")

	if _, err := LoadConfig(); err == nil || !strings.Contains(err.Error(), "MQTT_HOST") {
		t.Fatalf("expected MQTT_HOST to stay unset, got: %v", err)
	}
}
