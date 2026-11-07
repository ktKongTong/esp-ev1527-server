package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/knadh/koanf/parsers/dotenv"
	"github.com/knadh/koanf/providers/env"
	"github.com/knadh/koanf/providers/file"
	"github.com/knadh/koanf/v2"

	"esp32controlserver/internal/idgen"
	"esp32controlserver/internal/logging"
)

const (
	defaultTopicPrefix   = "home/OpenMQTTGateway"
	defaultGatewayClient = "OpenMQTTGateway_ESP32_315M"
	defaultServerHost    = "0.0.0.0"
	defaultServerPort    = 8080
	defaultMQTTPort      = 1883
	defaultMQTTWSPath    = "/mqtt"
	defaultMQTTKeepAlive = 60
)

// LoadConfig layers .env in the working directory and environment variables
// through Koanf. Environment variables take precedence; an empty environment
// value counts as unset, so it cannot erase a file value. A missing .env is
// not an error.
func LoadConfig() (Config, error) {
	settings, err := loadSettings()
	if err != nil {
		return Config{}, err
	}

	setting := func(name string) string { return strings.TrimSpace(settings.String(name)) }

	topicPrefix := setting("MQTT_TOPIC_PREFIX")
	if topicPrefix == "" {
		topicPrefix = defaultTopicPrefix
	}
	topicPrefix = strings.TrimSuffix(topicPrefix, "/")

	mqttHost := setting("MQTT_HOST")
	if mqttHost == "" {
		return Config{}, fmt.Errorf("MQTT_HOST is required; there is no offline fallback")
	}

	mqttPort := settings.Int("MQTT_PORT")
	if mqttPort == 0 {
		mqttPort = defaultMQTTPort
	}

	transport := MQTTTransportTCP
	switch strings.ToLower(setting("MQTT_TRANSPORT")) {
	case "", "tcp":
		transport = MQTTTransportTCP
	case "websocket", "ws", "wss":
		transport = MQTTTransportWebSocket
	default:
		return Config{}, fmt.Errorf(
			"MQTT_TRANSPORT must be tcp or websocket, got: %s",
			setting("MQTT_TRANSPORT"),
		)
	}

	useTLS := false
	if raw := setting("MQTT_TLS"); raw != "" {
		parsed, parseErr := strconv.ParseBool(raw)
		if parseErr != nil {
			return Config{}, fmt.Errorf("MQTT_TLS must be true or false, got: %s", raw)
		}
		useTLS = parsed
	}
	if transport == MQTTTransportTCP && useTLS {
		return Config{}, fmt.Errorf(
			"MQTT_TLS requires MQTT_TRANSPORT=websocket: raw TLS is not part of the gateway contract",
		)
	}

	level, err := logging.ParseLevel(setting("LOG_LEVEL"))
	if err != nil {
		return Config{}, err
	}

	clientID := setting("MQTT_CLIENT_ID")
	if clientID == "" {
		clientID = idgen.New("esp32-control-server")
	}

	port := settings.Int("SERVER_PORT")
	if port == 0 {
		port = defaultServerPort
	}

	host := setting("SERVER_HOST")
	if host == "" {
		host = defaultServerHost
	}

	databaseURL := setting("TURSO_DATABASE_URL")
	if databaseURL == "" {
		databaseURL = setting("DATABASE_URL")
	}
	if databaseURL == "" {
		return Config{}, fmt.Errorf(
			"TURSO_DATABASE_URL is required (DATABASE_URL is accepted as an alias)",
		)
	}

	authToken := setting("AUTH_TOKEN")
	if authToken == "" {
		return Config{}, fmt.Errorf(
			"AUTH_TOKEN is required; there is no unauthenticated mode",
		)
	}

	gatewayClientID := setting("GATEWAY_CLIENT_ID")
	if gatewayClientID == "" {
		gatewayClientID = defaultGatewayClient
	}

	return Config{
		Host: host,
		Port: port,
		MQTT: MQTTConfig{
			Host:             mqttHost,
			Port:             mqttPort,
			Username:         settings.String("MQTT_USERNAME"),
			Password:         settings.String("MQTT_PASSWORD"),
			ClientID:         clientID,
			TopicPrefix:      topicPrefix,
			KeepAliveSeconds: defaultMQTTKeepAlive,
			Transport:        transport,
			WebSocketPath:    defaultString(setting("MQTT_WS_PATH"), defaultMQTTWSPath),
			UseTLS:           useTLS,
		},
		Database: DatabaseConfig{
			URL:   databaseURL,
			Token: setting("TURSO_AUTH_TOKEN"),
		},
		Auth:            AuthConfig{Token: authToken},
		GatewayClientID: gatewayClientID,
		CommandTopic:    topicPrefix + "/commands/MQTTto433",
		SubscribedTopic: topicPrefix + "/#",
		LogLevel:        level,
	}, nil
}

func loadSettings() (*koanf.Koanf, error) {
	settings := koanf.New(".")

	if _, err := os.Stat(".env"); err == nil {
		if err := settings.Load(file.Provider(".env"), dotenv.Parser()); err != nil {
			return nil, fmt.Errorf("load .env: %w", err)
		}
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("stat .env: %w", err)
	}

	if err := settings.Load(env.ProviderWithValue(
		"",
		".",
		func(key, value string) (string, any) {
			if strings.TrimSpace(value) == "" {
				return "", nil
			}
			return key, value
		},
	), nil); err != nil {
		return nil, fmt.Errorf("load environment: %w", err)
	}

	return settings, nil
}

func defaultString(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
