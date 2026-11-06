package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

const (
	defaultTopicPrefix   = "home/OpenMQTTGateway"
	defaultGatewayClient = "OpenMQTTGateway_ESP32_315M"
)

type mqttTransportType string

const (
	mqttTransportTCP       mqttTransportType = "tcp"
	mqttTransportWebSocket mqttTransportType = "websocket"
)

type mqttConfig struct {
	Host             string
	Port             int
	Username         string
	Password         string
	ClientID         string
	TopicPrefix      string
	KeepAliveSeconds int
	Transport        mqttTransportType
	WebSocketPath    string
	UseTLS           bool
}

// String redacts credentials so config values can be logged.
func (c mqttConfig) String() string {
	username := "<none>"
	if c.Username != "" {
		username = "***"
	}
	password := "<none>"
	if c.Password != "" {
		password = "***"
	}
	return fmt.Sprintf(
		"MqttConfig(host=%s, port=%d, username=%s, password=%s, clientId=%s, "+
			"topicPrefix=%s, keepAliveSeconds=%d, transport=%s, websocketPath=%s, useTls=%t)",
		c.Host, c.Port, username, password, c.ClientID, c.TopicPrefix,
		c.KeepAliveSeconds, c.Transport, c.WebSocketPath, c.UseTLS,
	)
}

type config struct {
	Host            string
	Port            int
	MQTT            mqttConfig
	DatabasePath    string
	GatewayClientID string
	CommandTopic    string
	SubscribedTopic string
	LogLevel        logLevel
}

func loadConfig() (config, error) {
	file, err := loadConfigFile()
	if err != nil {
		return config{}, err
	}
	setting := func(name string) string {
		if value, ok := os.LookupEnv(name); ok && strings.TrimSpace(value) != "" {
			return value
		}
		if value, ok := file[name]; ok && strings.TrimSpace(value) != "" {
			return value
		}
		return ""
	}

	topicPrefix := setting("MQTT_TOPIC_PREFIX")
	if topicPrefix == "" {
		topicPrefix = defaultTopicPrefix
	}
	topicPrefix = strings.TrimSuffix(strings.TrimSpace(topicPrefix), "/")

	mqttHost := setting("MQTT_HOST")
	if mqttHost == "" {
		return config{}, fmt.Errorf("MQTT_HOST is required; there is no offline fallback")
	}
	mqttPort := 1883
	if raw := setting("MQTT_PORT"); raw != "" {
		if parsed, parseErr := strconv.Atoi(raw); parseErr == nil {
			mqttPort = parsed
		}
	}

	transport := mqttTransportTCP
	switch strings.ToLower(setting("MQTT_TRANSPORT")) {
	case "", "tcp":
		transport = mqttTransportTCP
	case "websocket", "ws", "wss":
		transport = mqttTransportWebSocket
	default:
		return config{}, fmt.Errorf("MQTT_TRANSPORT must be tcp or websocket, got: %s", setting("MQTT_TRANSPORT"))
	}
	useTLS := false
	if raw := setting("MQTT_TLS"); raw != "" {
		parsed, parseErr := strconv.ParseBool(raw)
		if parseErr != nil {
			return config{}, fmt.Errorf("MQTT_TLS must be true or false, got: %s", raw)
		}
		useTLS = parsed
	}
	if transport == mqttTransportTCP && useTLS {
		return config{}, fmt.Errorf(
			"MQTT_TLS requires MQTT_TRANSPORT=websocket: raw TLS is not part of the gateway contract",
		)
	}

	level, err := parseLogLevel(setting("LOG_LEVEL"))
	if err != nil {
		return config{}, err
	}

	clientID := setting("MQTT_CLIENT_ID")
	if clientID == "" {
		clientID = newID("esp32-control-server")
	}

	port := 8080
	if raw := setting("SERVER_PORT"); raw != "" {
		if parsed, parseErr := strconv.Atoi(raw); parseErr == nil {
			port = parsed
		}
	}

	host := setting("SERVER_HOST")
	if host == "" {
		host = "0.0.0.0"
	}

	databasePath := setting("DATABASE_PATH")
	if databasePath == "" {
		databasePath = "esp32-control.db"
	}
	gatewayClientID := setting("GATEWAY_CLIENT_ID")
	if gatewayClientID == "" {
		gatewayClientID = defaultGatewayClient
	}

	return config{
		Host: host,
		Port: port,
		MQTT: mqttConfig{
			Host:             mqttHost,
			Port:             mqttPort,
			Username:         setting("MQTT_USERNAME"),
			Password:         setting("MQTT_PASSWORD"),
			ClientID:         clientID,
			TopicPrefix:      topicPrefix,
			KeepAliveSeconds: 60,
			Transport:        transport,
			WebSocketPath:    defaultString(setting("MQTT_WS_PATH"), "/mqtt"),
			UseTLS:           useTLS,
		},
		DatabasePath:    databasePath,
		GatewayClientID: gatewayClientID,
		CommandTopic:    topicPrefix + "/commands/MQTTto433",
		SubscribedTopic: topicPrefix + "/#",
		LogLevel:        level,
	}, nil
}

func defaultString(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

// loadConfigFile implements the same precedence as the Kotlin server:
// CONFIG_FILE, then .env, then server/.env. The extra go-server/.env path lets
// this module run from its own directory without sharing the repository root.
func loadConfigFile() (map[string]string, error) {
	if path := strings.TrimSpace(os.Getenv("CONFIG_FILE")); path != "" {
		values, ok, err := readConfigFile(path)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, fmt.Errorf("CONFIG_FILE not found: %s", path)
		}
		return values, nil
	}
	for _, path := range []string{".env", "go-server/.env", "server/.env"} {
		values, ok, err := readConfigFile(path)
		if err != nil {
			return nil, err
		}
		if ok {
			return values, nil
		}
	}
	return map[string]string{}, nil
}

func readConfigFile(path string) (map[string]string, bool, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, false, nil
		}
		return nil, false, fmt.Errorf("read %s: %w", path, err)
	}
	return parseConfigFile(string(data)), true, nil
}

func parseConfigFile(content string) map[string]string {
	values := make(map[string]string)
	for _, raw := range strings.Split(content, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		assignment := strings.TrimSpace(strings.TrimPrefix(line, "export "))
		separator := strings.IndexByte(assignment, '=')
		if separator <= 0 {
			continue
		}
		key := strings.TrimSpace(assignment[:separator])
		if key == "" {
			continue
		}
		value := strings.TrimSpace(assignment[separator+1:])
		if len(value) >= 2 {
			quote := value[0]
			if (quote == '"' || quote == '\'') && value[len(value)-1] == quote {
				value = value[1 : len(value)-1]
			}
		}
		values[key] = value
	}
	return values
}
