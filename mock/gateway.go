package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

var (
	ErrPoweredOff   = errors.New("网关已断电（模拟 ESP32 掉电），请先输入 boot 重新上电")
	ErrNotConnected = errors.New("MQTT 尚未连接，无法发送")
	ErrAlreadyOff   = errors.New("网关已经处于断电状态")
	ErrAlreadyOn    = errors.New("网关已经处于上电状态")
)

// Gateway emulates the OpenMQTTGateway ESP32 node. It keeps one MQTT session
// at a time; a session is thrown away after a disconnect and a fresh client is
// created for the next boot. That mirrors a rebooted ESP32 and avoids reusing
// a paho client after a forced socket close.
type Gateway struct {
	cfg    Config
	topics Topics
	log    *Logger
	start  time.Time

	mu        sync.Mutex
	powered   bool
	connected bool
	client    mqtt.Client
	conn      net.Conn
	session   *mqttSession

	rxFrames        uint64
	txFrames        uint64
	lastRX          *RFCode
	lastRXAtEpochMs int64
	lastTX          *TXFrame
	lastTXAtEpochMs int64

	bootCh chan struct{}
}

type mqttSession struct {
	client mqtt.Client

	mu      sync.Mutex
	conn    net.Conn
	lost    chan struct{}
	lostOne sync.Once
	lostErr error
}

func NewGateway(cfg Config, log *Logger) *Gateway {
	return &Gateway{
		cfg:     cfg,
		topics:  NewTopics(cfg.TopicPrefix),
		log:     log,
		start:   time.Now(),
		powered: true,
		bootCh:  make(chan struct{}, 1),
	}
}

// Run owns the MQTT lifecycle: connect, subscribe, announce "online", wait for
// a disconnect, then either retry (normal network fault) or wait for boot
// (simulated power loss).
func (g *Gateway) Run(ctx context.Context) {
	backoff := time.Second
	for {
		if ctx.Err() != nil {
			return
		}
		if !g.isPowered() {
			g.log.Printf("[POWER] 网关已断电，等待 boot 重新上电...")
			select {
			case <-ctx.Done():
				return
			case <-g.bootCh:
				backoff = time.Second
			}
			continue
		}

		s, err := g.connect(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if !g.isPowered() {
				continue
			}
			g.log.Printf("[MQTT] 连接 %s 失败: %v（%s 后重试）", sanitizeBroker(g.cfg.MQTTURL), err, backoff)
			if !g.sleep(ctx, backoff) {
				return
			}
			backoff = nextBackoff(backoff)
			continue
		}
		backoff = time.Second

		if ctx.Err() != nil {
			s.abort()
			return
		}
		if !g.isPowered() {
			s.abort()
			s.markLost(errors.New("上电请求已取消"))
			continue
		}

		g.setSession(s)
		if err := g.startSession(s); err != nil {
			g.log.Printf("[MQTT] 会话初始化失败: %v", err)
			g.clearSession(s)
			s.abort()
			s.markLost(err)
			if !g.sleep(ctx, time.Second) {
				return
			}
			continue
		}

		g.log.Printf("[MQTT] 网关已上线: %s -> %q", g.topics.LWT, "online")

		select {
		case <-ctx.Done():
			g.log.Printf("[MQTT] 正在正常关闭模拟器...")
			g.gracefulClose(s)
			return
		case <-s.lost:
			g.clearSession(s)
			if !g.isPowered() {
				g.log.Printf("[POWER] TCP 连接已断开，Broker 应已发布遗嘱 %s=offline；输入 boot 重新上电", g.topics.LWT)
				continue
			}
			g.log.Printf("[MQTT] 与 Broker 的连接意外断开: %v（2 秒后重连）", s.err())
			if !g.sleep(ctx, 2*time.Second) {
				return
			}
		}
	}
}

func (g *Gateway) connect(ctx context.Context) (*mqttSession, error) {
	s := &mqttSession{lost: make(chan struct{})}

	opts := mqtt.NewClientOptions()
	opts.AddBroker(g.cfg.MQTTURL)
	opts.SetClientID(g.cfg.ClientID)
	opts.SetCleanSession(true)
	opts.SetAutoReconnect(false)
	opts.SetConnectRetry(false)
	opts.SetConnectTimeout(g.cfg.ConnectTimeout)
	opts.SetKeepAlive(g.cfg.KeepAlive)
	opts.SetPingTimeout(10 * time.Second)
	opts.SetOrderMatters(false)
	opts.SetWill(g.topics.LWT, "offline", 1, true)
	if g.cfg.Username != "" {
		opts.SetUsername(g.cfg.Username)
	}
	if g.cfg.Password != "" {
		opts.SetPassword(g.cfg.Password)
	}

	dialer := &net.Dialer{Timeout: g.cfg.ConnectTimeout}
	opts.SetDialer(dialer)
	opts.SetCustomOpenConnectionFn(func(uri *url.URL, o mqtt.ClientOptions) (net.Conn, error) {
		conn, err := g.openConnection(ctx, uri, o, dialer)
		if err != nil {
			return nil, err
		}
		s.setConn(conn)
		g.mu.Lock()
		g.conn = conn
		g.mu.Unlock()
		return conn, nil
	})
	opts.SetConnectionLostHandler(func(_ mqtt.Client, err error) {
		s.markLost(err)
	})

	c := mqtt.NewClient(opts)
	s.client = c

	tok := c.Connect()
	if !tok.WaitTimeout(g.cfg.ConnectTimeout + 3*time.Second) {
		s.abort()
		return nil, errors.New("连接 Broker 超时")
	}
	if err := tok.Error(); err != nil {
		s.abort()
		return nil, err
	}
	if ctx.Err() != nil {
		s.abort()
		return nil, ctx.Err()
	}

	g.log.Printf("[MQTT] 已连接 Broker: %s (ClientID=%s)", sanitizeBroker(g.cfg.MQTTURL), g.cfg.ClientID)
	return s, nil
}

func (g *Gateway) openConnection(ctx context.Context, uri *url.URL, opts mqtt.ClientOptions, dialer *net.Dialer) (net.Conn, error) {
	scheme := strings.ToLower(uri.Scheme)
	tlsEnabled := false
	switch scheme {
	case "tcp", "mqtt":
	case "ssl", "tls", "mqtts", "tcps":
		tlsEnabled = true
	default:
		return nil, fmt.Errorf("Mock 仅支持 tcp:// 或 ssl:// MQTT URL，收到 %q", uri.Scheme)
	}

	dialCtx, cancel := context.WithTimeout(ctx, g.cfg.ConnectTimeout)
	defer cancel()

	conn, err := dialer.DialContext(dialCtx, "tcp", uri.Host)
	if err != nil {
		return nil, err
	}

	if !tlsEnabled {
		return conn, nil
	}

	cfg := opts.TLSConfig
	if cfg == nil {
		cfg = &tls.Config{MinVersion: tls.VersionTLS12}
	}
	if cfg.ServerName == "" {
		cfg = cfg.Clone()
		cfg.ServerName = uri.Hostname()
	}
	tlsConn := tls.Client(conn, cfg)
	if err := tlsConn.HandshakeContext(dialCtx); err != nil {
		_ = conn.Close()
		return nil, err
	}
	return tlsConn, nil
}

func (g *Gateway) startSession(s *mqttSession) error {
	tok := s.client.Subscribe(g.topics.Command, 1, g.handleTXCommand)
	if !tok.WaitTimeout(5 * time.Second) {
		return errors.New("订阅命令主题超时")
	}
	if err := tok.Error(); err != nil {
		return fmt.Errorf("订阅 %s 失败: %w", g.topics.Command, err)
	}
	g.log.Printf("[MQTT] 已订阅命令: %s (QoS 1)", g.topics.Command)

	if err := g.publish(s, g.topics.Version, []byte(gatewayVersion), true); err != nil {
		return fmt.Errorf("发布 version 失败: %w", err)
	}
	g.log.Printf("[MQTT] -> %s = %q (retained)", g.topics.Version, gatewayVersion)

	if err := g.publish(s, g.topics.LWT, []byte("online"), true); err != nil {
		return fmt.Errorf("发布 online 失败: %w", err)
	}
	g.log.Printf("[MQTT] -> %s = %q (retained, LWT)", g.topics.LWT, "online")
	return nil
}

func (g *Gateway) publish(s *mqttSession, topic string, payload []byte, retain bool) error {
	tok := s.client.Publish(topic, 1, retain, payload)
	if !tok.WaitTimeout(3 * time.Second) {
		return errors.New("发布超时")
	}
	return tok.Error()
}

// handleTXCommand is the server -> gateway RF transmit path.
func (g *Gateway) handleTXCommand(_ mqtt.Client, msg mqtt.Message) {
	raw := msg.Payload()
	g.log.Printf("[MQTT] <- %s %s", msg.Topic(), string(raw))

	cmd, err := parseTXCommand(raw)
	if err != nil {
		g.log.Printf("[WARN] 无法解析 315M 发射指令: %v", err)
		return
	}
	frame := cmd.frame()
	if _, err := g.TransmitCode(frame.Value, frame.Protocol, frame.Length, frame.Repeat, "MQTT"); err != nil {
		g.log.Printf("[WARN] 模拟 315M 发射失败: %v", err)
	}
}

// TransmitCode simulates the ESP32's outbound 315MHz transmission. It does not
// publish to MQTT: a real transmitter has no protocol-level acknowledgement, so
// the only observable effect is the simulated RF burst (and the counters).
func (g *Gateway) TransmitCode(value uint64, protocol, length, repeat int, label string) (TXFrame, error) {
	frame := TXFrame{Value: value, Protocol: protocol, Length: length, Repeat: repeat}
	if err := validateRFParams(value, protocol, length); err != nil {
		return frame, err
	}
	if repeat < 1 || repeat > rfMaxRepeat {
		return frame, fmt.Errorf("repeat 必须在 1-%d 之间", rfMaxRepeat)
	}

	g.mu.Lock()
	powered := g.powered
	g.mu.Unlock()
	if !powered {
		return frame, ErrPoweredOff
	}

	g.mu.Lock()
	g.txFrames += uint64(repeat)
	copied := frame
	g.lastTX = &copied
	g.lastTXAtEpochMs = time.Now().UnixMilli()
	g.mu.Unlock()

	g.log.Printf("[315M TX] 模拟天线发射: Value=%d (HEX: %s), 重放=%d次",
		value, formatHex(value), repeat)
	if protocol != rfProtocol || length != rfBitLength {
		g.log.Printf("[315M TX] 非默认射频参数: protocol=%d, length=%d", protocol, length)
	}
	if label != "" {
		g.log.Printf("[315M TX] 来源: %s", label)
	}
	return frame, nil
}

// PressBurst publishes one or more simulated RF frames from a physical remote.
// A real 315MHz button press often produces multiple duplicate frames; sending
// them with a short interval exercises the server's press clustering (frames
// within 400ms count as one physical press). label is only used for logging.
func (g *Gateway) PressBurst(code uint64, label string, frames int, interval time.Duration) (RFCode, error) {
	payload := newRFCode(code)
	if code > rfMax24Bit {
		return payload, fmt.Errorf("value=%d 超出 24 位范围 (0-%d)", code, rfMax24Bit)
	}
	if frames < 1 || frames > rfMaxPressFrames {
		return payload, fmt.Errorf("frames 必须在 1-%d 之间", rfMaxPressFrames)
	}
	if interval < 0 {
		return payload, errors.New("interval 不能为负数")
	}
	if frames > 1 && interval == 0 {
		interval = 80 * time.Millisecond
	}

	for i := 0; i < frames; i++ {
		if err := g.publishCapture(payload); err != nil {
			return payload, err
		}
		if i+1 < frames {
			time.Sleep(interval)
		}
	}

	if label == "" {
		label = "模拟接收到 315M 信号"
	}
	if frames == 1 {
		g.log.Printf("[315M RX] %s: Value=%d (HEX: %s), delay=%dms",
			label, code, formatHex(code), rfReceiveDelayMS)
	} else {
		g.log.Printf("[315M RX] %s: Value=%d (HEX: %s), frames=%d, interval=%s, delay=%dms",
			label, code, formatHex(code), frames, interval, rfReceiveDelayMS)
	}
	raw, _ := payload.marshal()
	g.log.Printf("[MQTT] -> %s %s", g.topics.RX, string(raw))
	return payload, nil
}

// PressCode is the single-frame convenience form used by the CLI and the
// original HTTP endpoint.
func (g *Gateway) PressCode(code uint64, label string) (RFCode, error) {
	return g.PressBurst(code, label, 1, 0)
}

// publishCapture publishes one RX frame and records it only after the broker
// acknowledges the QoS 1 publish.
func (g *Gateway) publishCapture(payload RFCode) error {
	raw, err := payload.marshal()
	if err != nil {
		return err
	}

	g.mu.Lock()
	powered := g.powered
	client := g.client
	connected := g.connected && client != nil && client.IsConnected()
	g.mu.Unlock()

	if !powered {
		return ErrPoweredOff
	}
	if !connected {
		return ErrNotConnected
	}

	tok := client.Publish(g.topics.RX, rfQoS, false, raw)
	if !tok.WaitTimeout(3 * time.Second) {
		return errors.New("发布 315toMQTT 超时")
	}
	if err := tok.Error(); err != nil {
		return err
	}

	g.mu.Lock()
	g.rxFrames++
	copied := payload
	g.lastRX = &copied
	g.lastRXAtEpochMs = time.Now().UnixMilli()
	g.mu.Unlock()
	return nil
}

// Kill simulates an ESP32 power cut: the TCP socket is closed without an MQTT
// DISCONNECT packet, so the broker publishes the retained LWT "offline".
func (g *Gateway) Kill() error {
	g.mu.Lock()
	if !g.powered {
		g.mu.Unlock()
		return ErrAlreadyOff
	}
	g.powered = false
	g.connected = false
	g.client = nil
	s := g.session
	conn := g.conn
	g.mu.Unlock()

	g.log.Printf("[POWER] 模拟 ESP32 意外掉电：不发送 MQTT DISCONNECT，直接切断 TCP；等待 Broker 发布遗嘱 offline")

	if s != nil {
		s.abort()
		// paho suppresses "use of closed network connection" from its own reader,
		// so wake our supervisor explicitly instead of waiting for keepalive.
		s.markLost(errors.New("模拟掉电：TCP 连接已强制关闭"))
	} else if conn != nil {
		_ = conn.Close()
	} else {
		g.log.Printf("[POWER] 当前没有活动连接，网关直接进入离线状态")
	}
	return nil
}

// Boot brings the simulated device back after Kill.
func (g *Gateway) Boot() error {
	g.mu.Lock()
	if g.powered {
		g.mu.Unlock()
		return ErrAlreadyOn
	}
	g.powered = true
	g.mu.Unlock()

	g.log.Printf("[POWER] 模拟 ESP32 重新上电，正在连接 Broker...")
	select {
	case g.bootCh <- struct{}{}:
	default:
	}
	return nil
}

func (g *Gateway) isPowered() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.powered
}

func (g *Gateway) setSession(s *mqttSession) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.session = s
	g.client = s.client
	g.conn = s.connOrNil()
	g.connected = true
}

func (g *Gateway) clearSession(s *mqttSession) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.session == s {
		g.session = nil
		g.client = nil
		g.conn = nil
		g.connected = false
	}
}

func (g *Gateway) gracefulClose(s *mqttSession) {
	if s == nil || s.client == nil {
		return
	}
	if s.client.IsConnected() {
		tok := s.client.Publish(g.topics.LWT, 1, true, []byte("offline"))
		ok := tok.WaitTimeout(2 * time.Second)
		if ok && tok.Error() == nil {
			s.client.Disconnect(250)
			g.log.Printf("[MQTT] 已发布 retained offline 并正常断开")
			return
		}
		g.log.Printf("[MQTT] offline 发布失败，改为强制断开以触发遗嘱")
	}
	s.abort()
}

func (g *Gateway) sleep(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-g.bootCh:
		return true
	case <-t.C:
		return true
	}
}

// Status is safe to read from the HTTP server while the MQTT loop is running.
type Status struct {
	Powered       bool   `json:"powered"`
	MQTTConnected bool   `json:"mqtt_connected"`
	ClientID      string `json:"client_id"`
	Broker        string `json:"broker"`
	UptimeSeconds int64  `json:"uptime_seconds"`
	LWT           string `json:"lwt_topic"`
	VersionTopic  string `json:"version_topic"`
	VersionValue  string `json:"gateway_version"`
	CommandTopic  string `json:"command_topic"`
	RXTopic       string `json:"rx_topic"`

	RXFrames        uint64   `json:"rx_frames"`
	TXFrames        uint64   `json:"tx_frames"`
	LastRX          *RFCode  `json:"last_rx,omitempty"`
	LastRXAtEpochMs int64    `json:"last_rx_at_epoch_ms,omitempty"`
	LastTX          *TXFrame `json:"last_tx,omitempty"`
	LastTXAtEpochMs int64    `json:"last_tx_at_epoch_ms,omitempty"`
}

func (g *Gateway) Status() Status {
	g.mu.Lock()
	powered := g.powered
	client := g.client
	connected := g.connected && client != nil && client.IsConnected()
	rxFrames, txFrames := g.rxFrames, g.txFrames
	lastRX, lastRXAt := copyRFCode(g.lastRX), g.lastRXAtEpochMs
	lastTX, lastTXAt := copyTXFrame(g.lastTX), g.lastTXAtEpochMs
	g.mu.Unlock()

	return Status{
		Powered:         powered,
		MQTTConnected:   connected,
		ClientID:        g.cfg.ClientID,
		Broker:          sanitizeBroker(g.cfg.MQTTURL),
		UptimeSeconds:   int64(time.Since(g.start).Seconds()),
		LWT:             g.topics.LWT,
		VersionTopic:    g.topics.Version,
		VersionValue:    gatewayVersion,
		CommandTopic:    g.topics.Command,
		RXTopic:         g.topics.RX,
		RXFrames:        rxFrames,
		TXFrames:        txFrames,
		LastRX:          lastRX,
		LastRXAtEpochMs: lastRXAt,
		LastTX:          lastTX,
		LastTXAtEpochMs: lastTXAt,
	}
}

func copyRFCode(src *RFCode) *RFCode {
	if src == nil {
		return nil
	}
	v := *src
	return &v
}

func copyTXFrame(src *TXFrame) *TXFrame {
	if src == nil {
		return nil
	}
	v := *src
	return &v
}

func (s *mqttSession) setConn(c net.Conn) {
	s.mu.Lock()
	s.conn = c
	s.mu.Unlock()
}

func (s *mqttSession) connOrNil() net.Conn {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conn
}

func (s *mqttSession) abort() {
	s.mu.Lock()
	c := s.conn
	s.mu.Unlock()
	if c != nil {
		_ = c.Close()
	}
}

func (s *mqttSession) markLost(err error) {
	s.mu.Lock()
	if s.lostErr == nil {
		s.lostErr = err
	}
	s.mu.Unlock()
	s.lostOne.Do(func() {
		close(s.lost)
	})
}

func (s *mqttSession) err() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lostErr
}

func nextBackoff(d time.Duration) time.Duration {
	d *= 2
	if d > 10*time.Second {
		return 10 * time.Second
	}
	return d
}

func sanitizeBroker(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.User == nil {
		return raw
	}
	u.User = nil
	return u.String()
}
