// Package config defines the server configuration and loads it from
// environment variables and a dotenv file.
package config

import (
	"fmt"
	"log/slog"
	"time"
)

type MQTTTransport string

const (
	MQTTTransportTCP       MQTTTransport = "tcp"
	MQTTTransportWebSocket MQTTTransport = "websocket"
)

type MQTTConfig struct {
	Host             string
	Port             int
	Username         string
	Password         string
	ClientID         string
	TopicPrefix      string
	KeepAliveSeconds int
	Transport        MQTTTransport
	WebSocketPath    string
	UseTLS           bool
}

// String redacts credentials so config values can be logged.
func (c MQTTConfig) String() string {
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

// AuthConfig holds the single API access token. The server accepts exactly one
// token: this is a shared secret for a trusted deployment, not a user system.
type AuthConfig struct {
	Token string
}

// String redacts the token so config values can be logged.
func (c AuthConfig) String() string {
	token := "<none>"
	if c.Token != "" {
		token = "***"
	}
	return fmt.Sprintf("AuthConfig(token=%s)", token)
}

type DatabaseConfig struct {
	URL   string
	Token string
}

// String redacts the auth token so config values can be logged.
func (c DatabaseConfig) String() string {
	token := "<none>"
	if c.Token != "" {
		token = "***"
	}
	return fmt.Sprintf("DatabaseConfig(url=%s, authToken=%s)", c.URL, token)
}

type Config struct {
	Host                string
	Port                int
	MQTT                MQTTConfig
	Database            DatabaseConfig
	Auth                AuthConfig
	GatewayClientID     string
	CommandTopic        string
	SubscribedTopic     string
	LogLevel            slog.Level
	ScheduleMissedGrace time.Duration
}
