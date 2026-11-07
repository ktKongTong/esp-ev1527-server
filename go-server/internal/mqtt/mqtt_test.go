package mqtt

import (
	"testing"

	"esp32controlserver/internal/config"
)

func TestBrokerURL(t *testing.T) {
	cases := []struct {
		name string
		cfg  config.MQTTConfig
		want string
	}{
		{
			name: "tcp default",
			cfg:  config.MQTTConfig{Host: "broker", Port: 1883},
			want: "tcp://broker:1883",
		},
		{
			name: "websocket default path",
			cfg: config.MQTTConfig{
				Host: "broker", Port: 8083,
				Transport: config.MQTTTransportWebSocket,
			},
			want: "ws://broker:8083/mqtt",
		},
		{
			name: "secure websocket",
			cfg: config.MQTTConfig{
				Host: "broker", Port: 8084,
				Transport:     config.MQTTTransportWebSocket,
				WebSocketPath: "/mqtt",
				UseTLS:        true,
			},
			want: "wss://broker:8084/mqtt",
		},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := BrokerURL(testCase.cfg); got != testCase.want {
				t.Fatalf("BrokerURL = %q, want %q", got, testCase.want)
			}
		})
	}
}
