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
	TXEcho         bool
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
	cfg := Config{
		MQTTURL:        envOr("MQTT_URL", defaultMQTTURL),
		ClientID:       envOr("MQTT_CLIENT_ID", defaultClientID),
		Username:       os.Getenv("MQTT_USERNAME"),
		Password:       os.Getenv("MQTT_PASSWORD"),
		HTTPAddr:       envOr("HTTP_ADDR", defaultHTTPAddr),
		TopicPrefix:    envOr("OMG_TOPIC_PREFIX", defaultTopicPrefix),
		ConnectTimeout: 5 * time.Second,
		KeepAlive:      30 * time.Second,
		TXEcho:         envBool("MOCK_TX_ECHO", false),
	}

	flag.StringVar(&cfg.MQTTURL, "mqtt", cfg.MQTTURL, "MQTT Broker URL，例如 tcp://localhost:1883（环境变量 MQTT_URL）")
	flag.StringVar(&cfg.ClientID, "client-id", cfg.ClientID, "MQTT ClientID（环境变量 MQTT_CLIENT_ID）")
	flag.StringVar(&cfg.Username, "user", cfg.Username, "MQTT 用户名（环境变量 MQTT_USERNAME）")
	flag.StringVar(&cfg.Password, "pass", cfg.Password, "MQTT 密码（环境变量 MQTT_PASSWORD）")
	flag.StringVar(&cfg.HTTPAddr, "http", cfg.HTTPAddr, "HTTP 控制端口监听地址；设为空字符串可禁用（环境变量 HTTP_ADDR）")
	flag.StringVar(&cfg.TopicPrefix, "topic-prefix", cfg.TopicPrefix, "MQTT 主题前缀（环境变量 OMG_TOPIC_PREFIX）")
	flag.DurationVar(&cfg.ConnectTimeout, "connect-timeout", cfg.ConnectTimeout, "MQTT 连接超时")
	flag.DurationVar(&cfg.KeepAlive, "keepalive", cfg.KeepAlive, "MQTT KeepAlive 间隔")
	flag.BoolVar(&cfg.TXEcho, "tx-echo", cfg.TXEcho, "收到 MQTT 发射指令后模拟接收器回显一次捕获（默认 false，仅用于测试）")
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

func envOr(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}

func envBool(key string, fallback bool) bool {
	v, ok := os.LookupEnv(key)
	if !ok {
		return fallback
	}
	parsed, err := strconv.ParseBool(v)
	if err != nil {
		return fallback
	}
	return parsed
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
