package service

import (
	"context"
	"encoding/json"
	"strings"
	"sync"

	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/logging"
)

type rfCapture struct {
	Value    int64
	Protocol int
	Length   int
	DelayMs  int
}

// GatewayStatusStore holds the live MQTT link and receiver state. It is not
// persisted: both values describe the current process and reset on restart.
type GatewayStatusStore struct {
	mu                           sync.Mutex
	gatewayClientID              string
	state                        string
	version                      *string
	changedAtEpochMs             int64
	receiverState                string
	receiverLastCaptureAtEpochMs *int64
	receiverCaptureCount         int64
	now                          func() int64
}

func NewGatewayStatusStore(clientID string, opts ...Option) *GatewayStatusStore {
	resolved := resolveOptions(opts)
	return &GatewayStatusStore{
		gatewayClientID:  clientID,
		state:            contract.GatewayUnknown,
		receiverState:    contract.ReceiverUnknown,
		changedAtEpochMs: resolved.now(),
		now:              resolved.now,
	}
}

func (s *GatewayStatusStore) Snapshot() contract.GatewayStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	version := s.version
	clientID := s.gatewayClientID
	lastCapture := s.receiverLastCaptureAtEpochMs
	return contract.GatewayStatus{
		State:                s.state,
		Version:              version,
		ClientID:             &clientID,
		LastChangedAtEpochMs: s.changedAtEpochMs,
		Receiver: contract.ReceiverStatus{
			State:                s.receiverState,
			LastCaptureAtEpochMs: lastCapture,
			CaptureCount:         s.receiverCaptureCount,
		},
	}
}

func (s *GatewayStatusStore) IsOnline() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state == contract.GatewayOnline
}

// SetGateway returns true when the state or version changed, so the caller can
// run transition side effects once. The broker connection alone does not make
// the gateway online; only the retained LWT or version message does.
func (s *GatewayStatusStore) SetGateway(state string, version *string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	previous := s.state
	stateChanged := previous != state
	versionChanged := version != nil && (s.version == nil || *version != *s.version)
	if !stateChanged && !versionChanged {
		return false
	}

	s.state = state
	if version != nil {
		s.version = version
	}
	s.changedAtEpochMs = now

	if state == contract.GatewayOnline && previous != contract.GatewayOnline {
		s.receiverState = contract.ReceiverUnknown
		s.receiverLastCaptureAtEpochMs = nil
		s.receiverCaptureCount = 0
	} else if state != contract.GatewayOnline {
		s.receiverState = contract.ReceiverUnknown
	}

	versionLabel := "n/a"
	if s.version != nil {
		versionLabel = *s.version
	}
	logging.Infof(
		"[gateway] %s -> %s clientId=%s version=%s",
		previous, state, s.gatewayClientID, versionLabel,
	)
	return true
}

func (s *GatewayStatusStore) OnCapture(now int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.receiverState = contract.ReceiverReceiving
	s.receiverLastCaptureAtEpochMs = &now
	s.receiverCaptureCount++
}

type GatewayIngest struct {
	prefix   string
	gateway  *GatewayStatusStore
	learning *LearningService
}

func NewGatewayIngest(
	prefix string,
	gateway *GatewayStatusStore,
	learning *LearningService,
) *GatewayIngest {
	return &GatewayIngest{
		prefix:   strings.TrimSuffix(prefix, "/"),
		gateway:  gateway,
		learning: learning,
	}
}

func (g *GatewayIngest) OnDisconnected() {
	g.setGateway(contract.GatewayOffline, nil)
}

func (g *GatewayIngest) OnMessage(topic string, payload []byte) {
	if !strings.HasPrefix(topic, g.prefix+"/") {
		logging.Debugf("[ingest] ignored topic outside prefix %s: %s", g.prefix, topic)
		return
	}
	text := strings.TrimSpace(string(payload))

	switch {
	case topic == g.prefix+"/LWT":
		state := strings.Trim(text, "\"")
		logging.Infof("[ingest] gateway LWT=%s", state)
		switch state {
		case contract.GatewayOnline:
			g.setGateway(contract.GatewayOnline, nil)
		case contract.GatewayOffline:
			g.setGateway(contract.GatewayOffline, nil)
		}
	case topic == g.prefix+"/version":
		version := strings.Trim(text, "\"")
		var versionPtr *string
		if version != "" {
			versionPtr = &version
		}
		label := "<empty>"
		if versionPtr != nil {
			label = *versionPtr
		}
		logging.Infof("[ingest] gateway version=%s", label)
		g.setGateway(contract.GatewayOnline, versionPtr)
	case strings.HasPrefix(topic, g.prefix+"/commands/"):
		logging.Debugf("[ingest] ignore command echo on %s", topic)
	default:
		if capture := parseCapture(topic, text); capture != nil {
			g.ingestCapture(*capture)
		}
	}
}

func (g *GatewayIngest) setGateway(state string, version *string) bool {
	changed := g.gateway.SetGateway(state, version)
	if changed && state != contract.GatewayOnline {
		g.learning.OnGatewayOffline(g.gateway.now())
	}
	return changed
}

func (g *GatewayIngest) ingestCapture(capture rfCapture) {
	now := g.gateway.now()
	logging.Debugf(
		"[ingest] capture value=%d protocol=%d length=%d delay=%dms",
		capture.Value, capture.Protocol, capture.Length, capture.DelayMs,
	)
	g.gateway.OnCapture(now)
	g.learning.OnCapture(context.Background(), capture, now)
}

type gatewayCapturePayload struct {
	Value    *int64 `json:"value"`
	Protocol *int   `json:"protocol"`
	Length   *int   `json:"length"`
	Delay    *int   `json:"delay"`
}

func parseCapture(topic, text string) *rfCapture {
	var payload gatewayCapturePayload
	if err := json.Unmarshal([]byte(text), &payload); err != nil {
		logging.Debugf(
			"[ingest] drop %s: not a capture payload (%v): %s",
			topic, err, previewText(text, 240),
		)
		return nil
	}
	if payload.Value == nil || payload.Protocol == nil || payload.Length == nil {
		logging.Debugf("[ingest] drop %s: capture payload is missing value/protocol/length", topic)
		return nil
	}
	if !ev1527AcceptsLength(*payload.Length) {
		logging.Debugf(
			"[ingest] drop %s: length=%d, EV1527 expects %d bits",
			topic, *payload.Length, ev1527Length,
		)
		return nil
	}
	if !ev1527Valid(*payload.Value) {
		logging.Debugf(
			"[ingest] drop %s: value=%d outside 0..%d",
			topic, *payload.Value, ev1527MaxValue,
		)
		return nil
	}
	delay := 330
	if payload.Delay != nil {
		delay = *payload.Delay
	}
	if delay < 0 {
		delay = 0
	}
	return &rfCapture{
		Value:    *payload.Value,
		Protocol: *payload.Protocol,
		Length:   *payload.Length,
		DelayMs:  delay,
	}
}

func previewText(text string, limit int) string {
	if len(text) <= limit {
		return text
	}
	return text[:limit] + "..."
}
