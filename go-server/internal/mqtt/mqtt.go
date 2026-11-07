// Package mqtt adapts paho.mqtt.golang to the server's publisher and ingest
// needs. The MQTT protocol, keepalive, QoS 1 delivery and reconnection stay
// inside paho; this package only maps configuration and messages.
package mqtt

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"

	"esp32controlserver/internal/config"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/logging"
)

const (
	publishTimeout = 3 * time.Second
	qos            = 1
)

// BrokerURL builds the broker URL for the configured transport.
func BrokerURL(cfg config.MQTTConfig) string {
	address := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
	if cfg.Transport == config.MQTTTransportWebSocket {
		path := cfg.WebSocketPath
		if path == "" {
			path = "/mqtt"
		}
		scheme := "ws"
		if cfg.UseTLS {
			scheme = "wss"
		}
		return fmt.Sprintf("%s://%s%s", scheme, address, path)
	}
	return fmt.Sprintf("tcp://%s", address)
}

// Transport owns one paho client and the single subscription that feeds the
// gateway ingest.
type Transport struct {
	cfg            config.MQTTConfig
	broker         string
	topicFilter    string
	client         paho.Client
	onMessage      func(topic string, payload []byte)
	onDisconnected func()
}

func NewTransport(
	cfg config.MQTTConfig,
	topicFilter string,
	onMessage func(topic string, payload []byte),
	onDisconnected func(),
) *Transport {
	transport := &Transport{
		cfg:            cfg,
		broker:         BrokerURL(cfg),
		topicFilter:    topicFilter,
		onMessage:      onMessage,
		onDisconnected: onDisconnected,
	}

	options := paho.NewClientOptions()
	options.AddBroker(transport.broker)
	options.SetClientID(cfg.ClientID)
	if cfg.Username != "" {
		options.SetUsername(cfg.Username)
	}
	if cfg.Password != "" {
		options.SetPassword(cfg.Password)
	}
	options.SetKeepAlive(time.Duration(cfg.KeepAliveSeconds) * time.Second)
	options.SetCleanSession(true)
	options.SetAutoReconnect(true)
	options.SetConnectRetry(true)
	options.SetConnectRetryInterval(2 * time.Second)
	options.SetMaxReconnectInterval(30 * time.Second)
	options.SetConnectTimeout(5 * time.Second)
	options.SetOrderMatters(true)

	if cfg.UseTLS {
		options.SetTLSConfig(&tls.Config{
			ServerName: cfg.Host,
			MinVersion: tls.VersionTLS12,
		})
	}

	options.SetOnConnectHandler(func(client paho.Client) {
		logging.Infof(
			"[mqtt] session established: %s subscribed=%s",
			transport.broker, transport.topicFilter,
		)
		token := client.Subscribe(transport.topicFilter, qos, transport.handleMessage)
		if !token.WaitTimeout(5 * time.Second) {
			logging.Errorf("[mqtt] subscribe timed out: %s", transport.topicFilter)
			return
		}
		if err := token.Error(); err != nil {
			logging.Errorf("[mqtt] subscribe failed for %s: %v", transport.topicFilter, err)
		}
	})
	options.SetReconnectingHandler(func(_ paho.Client, _ *paho.ClientOptions) {
		logging.Infof("[mqtt] reconnecting to %s", transport.broker)
	})
	options.SetConnectionLostHandler(func(_ paho.Client, err error) {
		logging.Errorf("[mqtt] connection lost for %s: %v", transport.broker, err)
		transport.onDisconnected()
	})

	transport.client = paho.NewClient(options)
	return transport
}

func (t *Transport) handleMessage(_ paho.Client, message paho.Message) {
	payload := message.Payload()
	logging.Debugf(
		"[mqtt] <- %s qos=%d retain=%t payload=%s",
		message.Topic(), message.Qos(), message.Retained(), logging.Preview(payload, 240),
	)
	if t.onMessage != nil {
		t.onMessage(message.Topic(), payload)
	}
}

// Start initiates the connection without blocking HTTP startup. paho keeps
// retrying in the background, so the API stays available while the broker is
// down and the gateway state remains UNKNOWN until the retained LWT arrives.
func (t *Transport) Start() {
	logging.Infof("[mqtt] connecting to %s for %s", t.broker, t.topicFilter)
	token := t.client.Connect()
	go func() {
		if !token.WaitTimeout(10 * time.Second) {
			logging.Warnf("[mqtt] initial connection still in progress for %s", t.broker)
			return
		}
		if err := token.Error(); err != nil {
			logging.Errorf("[mqtt] initial connection failed for %s: %v", t.broker, err)
		}
	}()
}

func (t *Transport) Publish(topic string, payload []byte) error {
	logging.Debugf("[mqtt] -> %s payload=%s", topic, logging.Preview(payload, 240))
	token := t.client.Publish(topic, qos, false, payload)
	if !token.WaitTimeout(publishTimeout) {
		return fmt.Errorf("MQTT publish timed out for %s", topic)
	}
	if err := token.Error(); err != nil {
		return fmt.Errorf("MQTT publish failed for %s: %w", topic, err)
	}
	return nil
}

func (t *Transport) Close() {
	t.client.Disconnect(250)
}

// Transmitter publishes RF command frames to the gateway command topic.
type Transmitter struct {
	transport *Transport
	topic     string
}

func NewTransmitter(transport *Transport, topic string) *Transmitter {
	return &Transmitter{transport: transport, topic: topic}
}

func (t *Transmitter) Send(_ context.Context, payload contract.RFPayload) error {
	encoded, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return t.transport.Publish(t.topic, encoded)
}
