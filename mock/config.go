package main

import (
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultMQTTURL     = "tcp://localhost:1883"
	defaultClientID    = "OpenMQTTGateway_ESP32_315M"
	defaultHTTPAddr    = ":8099"
	defaultTopicPrefix = "home/OpenMQTTGateway"
	gatewayVersion     = "v1.7.0"
)

// Config holds everything that can be tuned without touching the protocol contract.
type Config struct {
	MQTTURL        string
	ClientID       string
	Username       string
	Password       string
	HTTPAddr       string
	TopicPrefix    string
	ConnectTimeout time.Duration
	KeepAlive      time.Duration
}

// Topics derives the OpenMQTTGateway topic set from the configured prefix.
// With the default prefix the resulting topics are exactly the ones in the
// contract (home/OpenMQTTGateway/...).
type Topics struct {
	LWT     string
	Version string
	Command string
	RX      string
}

func NewTopics(prefix string) Topics {
	p := strings.TrimSuffix(strings.TrimSpace(prefix), "/")
	if p == "" {
		p = defaultTopicPrefix
	}
	return Topics{
		LWT:     p + "/LWT",
		Version: p + "/version",
		Command: p + "/commands/MQTTto315",
		RX:      p + "/315toMQTT",
	}
}

func loadConfig() Config {
	fileValues := loadDotEnv()
	cfg := Config{
		MQTTURL:        envOrFile("MQTT_URL", defaultMQTTURL, fileValues),
		ClientID:       envOrFile("MQTT_CLIENT_ID", defaultClientID, fileValues),
		Username:       envOrFile("MQTT_USERNAME", "", fileValues),
		Password:       envOrFile("MQTT_PASSWORD", "", fileValues),
		HTTPAddr:       envOrFile("HTTP_ADDR", defaultHTTPAddr, fileValues),
		TopicPrefix:    envOrFile("OMG_TOPIC_PREFIX", defaultTopicPrefix, fileValues),
		ConnectTimeout: 5 * time.Second,
		KeepAlive:      30 * time.Second,
	}

	flag.StringVar(&cfg.MQTTURL, "mqtt", cfg.MQTTURL, "MQTT Broker URL，例如 tcp://localhost:1883（环境变量 MQTT_URL）")
	flag.StringVar(&cfg.ClientID, "client-id", cfg.ClientID, "MQTT ClientID（环境变量 MQTT_CLIENT_ID）")
	flag.StringVar(&cfg.Username, "user", cfg.Username, "MQTT 用户名（环境变量 MQTT_USERNAME）")
	flag.StringVar(&cfg.Password, "pass", cfg.Password, "MQTT 密码（环境变量 MQTT_PASSWORD）")
	flag.StringVar(&cfg.HTTPAddr, "http", cfg.HTTPAddr, "HTTP 控制端口监听地址；设为空字符串可禁用（环境变量 HTTP_ADDR）")
	flag.StringVar(&cfg.TopicPrefix, "topic-prefix", cfg.TopicPrefix, "MQTT 主题前缀（环境变量 OMG_TOPIC_PREFIX）")
	flag.DurationVar(&cfg.ConnectTimeout, "connect-timeout", cfg.ConnectTimeout, "MQTT 连接超时")
	flag.DurationVar(&cfg.KeepAlive, "keepalive", cfg.KeepAlive, "MQTT KeepAlive 间隔")
	flag.Usage = func() {
		fmt.Fprintf(flag.CommandLine.Output(), "OpenMQTTGateway 315MHz 高保真模拟器\n\n用法: %s [选项]\n\n选项:\n", os.Args[0])
		flag.PrintDefaults()
	}
	flag.Parse()

	cfg.HTTPAddr = normalizeHTTPAddr(cfg.HTTPAddr)
	cfg.TopicPrefix = strings.TrimSuffix(strings.TrimSpace(cfg.TopicPrefix), "/")
	if cfg.TopicPrefix == "" {
		cfg.TopicPrefix = defaultTopicPrefix
	}
	if cfg.ConnectTimeout <= 0 {
		cfg.ConnectTimeout = 5 * time.Second
	}
	if cfg.KeepAlive <= 0 {
		cfg.KeepAlive = 30 * time.Second
	}
	return cfg
}

// loadDotEnv reads .env from the working directory, or mock/.env when the
// process runs from the repository root. A missing file is not an error.
func loadDotEnv() map[string]string {
	for _, path := range []string{".env", "mock/.env"} {
		if values, ok := readDotEnv(path); ok {
			return values
		}
	}
	return map[string]string{}
}

func readDotEnv(path string) (map[string]string, bool) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, false
	}
	return parseDotEnv(string(data)), true
}

func parseDotEnv(content string) map[string]string {
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

// envOrFile resolves in the same order as the server: process environment,
// then .env values, then the built-in default.
func envOrFile(key, fallback string, fileValues map[string]string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	if value, ok := fileValues[key]; ok {
		return value
	}
	return fallback
}

func normalizeHTTPAddr(addr string) string {
	addr = strings.TrimSpace(addr)
	if addr == "" {
		return ""
	}
	if strings.HasPrefix(addr, ":") {
		return addr
	}
	if _, err := strconv.Atoi(addr); err == nil {
		return ":" + addr
	}
	return addr
}
