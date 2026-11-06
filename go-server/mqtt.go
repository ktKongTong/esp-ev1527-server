package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"strconv"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

const (
	mqttPublishTimeout = 3 * time.Second
	mqttQoS            = 1
)

func brokerURL(cfg mqttConfig) string {
	address := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
	if cfg.Transport == mqttTransportWebSocket {
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

type mqttTransport struct {
	cfg            mqttConfig
	broker         string
	topicFilter    string
	client         mqtt.Client
	onMessage      func(topic string, payload []byte)
	onDisconnected func()
}

func newMQTTTransport(
	cfg mqttConfig,
	topicFilter string,
	onMessage func(topic string, payload []byte),
	onDisconnected func(),
) *mqttTransport {
	transport := &mqttTransport{
		cfg:            cfg,
		broker:         brokerURL(cfg),
		topicFilter:    topicFilter,
		onMessage:      onMessage,
		onDisconnected: onDisconnected,
	}

	options := mqtt.NewClientOptions()
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

	options.SetOnConnectHandler(func(client mqtt.Client) {
		infof("[mqtt] session established: %s subscribed=%s", transport.broker, transport.topicFilter)
		token := client.Subscribe(transport.topicFilter, mqttQoS, transport.handleMessage)
		if !token.WaitTimeout(5 * time.Second) {
			errorf("[mqtt] subscribe timed out: %s", transport.topicFilter)
			return
		}
		if err := token.Error(); err != nil {
			errorf("[mqtt] subscribe failed for %s: %v", transport.topicFilter, err)
		}
	})
	options.SetReconnectingHandler(func(client mqtt.Client, _ *mqtt.ClientOptions) {
		infof("[mqtt] reconnecting to %s", transport.broker)
	})
	options.SetConnectionLostHandler(func(_ mqtt.Client, err error) {
		errorf("[mqtt] connection lost for %s: %v", transport.broker, err)
		transport.onDisconnected()
	})

	transport.client = mqtt.NewClient(options)
	return transport
}

func (t *mqttTransport) handleMessage(_ mqtt.Client, message mqtt.Message) {
	payload := message.Payload()
	debugf(
		"[mqtt] <- %s qos=%d retain=%t payload=%s",
		message.Topic(), message.Qos(), message.Retained(), logPreview(payload, 240),
	)
	if t.onMessage != nil {
		t.onMessage(message.Topic(), payload)
	}
}

// start initiates the connection without blocking HTTP startup. paho keeps
// retrying in the background, so the API stays available while the broker is
// down and the gateway state remains UNKNOWN until the retained LWT arrives.
func (t *mqttTransport) start() {
	infof("[mqtt] connecting to %s for %s", t.broker, t.topicFilter)
	token := t.client.Connect()
	go func() {
		if !token.WaitTimeout(10 * time.Second) {
			warnf("[mqtt] initial connection still in progress for %s", t.broker)
			return
		}
		if err := token.Error(); err != nil {
			errorf("[mqtt] initial connection failed for %s: %v", t.broker, err)
		}
	}()
}

func (t *mqttTransport) publish(topic string, payload []byte) error {
	debugf("[mqtt] -> %s payload=%s", topic, logPreview(payload, 240))
	token := t.client.Publish(topic, mqttQoS, false, payload)
	if !token.WaitTimeout(mqttPublishTimeout) {
		return fmt.Errorf("MQTT publish timed out for %s", topic)
	}
	if err := token.Error(); err != nil {
		return fmt.Errorf("MQTT publish failed for %s: %w", topic, err)
	}
	return nil
}

func (t *mqttTransport) close() {
	t.client.Disconnect(250)
}

type mqttTransmitter struct {
	transport *mqttTransport
	topic     string
}

func newMQTTTransmitter(transport *mqttTransport, topic string) *mqttTransmitter {
	return &mqttTransmitter{transport: transport, topic: topic}
}

func (t *mqttTransmitter) Send(_ context.Context, payload rfPayload) error {
	encoded, err := encodeRFPayload(payload)
	if err != nil {
		return err
	}
	return t.transport.publish(t.topic, encoded)
}
