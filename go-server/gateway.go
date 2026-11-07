package main

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
)

type rfCapture struct {
	Value    int64
	Protocol int
	Length   int
	DelayMs  int
}

// gatewayStatusStore holds the live MQTT link and receiver state. It is not
// persisted: both values describe the current process and reset on restart.
type gatewayStatusStore struct {
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

func newGatewayStatusStore(clientID string) *gatewayStatusStore {
	return &gatewayStatusStore{
		gatewayClientID:  clientID,
		state:            gatewayUnknown,
		receiverState:    receiverUnknown,
		changedAtEpochMs: nowEpochMs(),
		now:              nowEpochMs,
	}
}

func (s *gatewayStatusStore) snapshot() gatewayStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	version := s.version
	clientID := s.gatewayClientID
	lastCapture := s.receiverLastCaptureAtEpochMs
	return gatewayStatus{
		State:                s.state,
		Version:              version,
		ClientID:             &clientID,
		LastChangedAtEpochMs: s.changedAtEpochMs,
		Receiver: receiverStatus{
			State:                s.receiverState,
			LastCaptureAtEpochMs: lastCapture,
			CaptureCount:         s.receiverCaptureCount,
		},
	}
}

func (s *gatewayStatusStore) isOnline() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state == gatewayOnline
}

// setGateway returns true when the state or version changed, so the caller can
// run transition side effects once. The broker connection alone does not make
// the gateway online; only the retained LWT or version message does.
func (s *gatewayStatusStore) setGateway(state string, version *string) bool {
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

	if state == gatewayOnline && previous != gatewayOnline {
		s.receiverState = receiverUnknown
		s.receiverLastCaptureAtEpochMs = nil
		s.receiverCaptureCount = 0
	} else if state != gatewayOnline {
		s.receiverState = receiverUnknown
	}

	versionLabel := "n/a"
	if s.version != nil {
		versionLabel = *s.version
	}
	infof(
		"[gateway] %s -> %s clientId=%s version=%s",
		previous, state, s.gatewayClientID, versionLabel,
	)
	return true
}

func (s *gatewayStatusStore) onCapture(now int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.receiverState = receiverReceiving
	s.receiverLastCaptureAtEpochMs = &now
	s.receiverCaptureCount++
}

type gatewayIngest struct {
	prefix   string
	gateway  *gatewayStatusStore
	learning *learningService
}

func newGatewayIngest(cfg config, gateway *gatewayStatusStore, learning *learningService) *gatewayIngest {
	return &gatewayIngest{
		prefix:   strings.TrimSuffix(cfg.SubscribedTopic, "/#"),
		gateway:  gateway,
		learning: learning,
	}
}

func (g *gatewayIngest) onDisconnected() {
	g.setGateway(gatewayOffline, nil)
}

func (g *gatewayIngest) onMessage(topic string, payload []byte) {
	if !strings.HasPrefix(topic, g.prefix+"/") {
		debugf("[ingest] ignored topic outside prefix %s: %s", g.prefix, topic)
		return
	}
	text := strings.TrimSpace(string(payload))

	switch {
	case topic == g.prefix+"/LWT":
		state := strings.Trim(text, "\"")
		infof("[ingest] gateway LWT=%s", state)
		switch state {
		case gatewayOnline:
			g.setGateway(gatewayOnline, nil)
		case gatewayOffline:
			g.setGateway(gatewayOffline, nil)
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
		infof("[ingest] gateway version=%s", label)
		g.setGateway(gatewayOnline, versionPtr)
	case strings.HasPrefix(topic, g.prefix+"/commands/"):
		debugf("[ingest] ignore command echo on %s", topic)
	default:
		if capture := parseCapture(topic, text); capture != nil {
			g.ingestCapture(*capture)
		}
	}
}

func (g *gatewayIngest) setGateway(state string, version *string) bool {
	changed := g.gateway.setGateway(state, version)
	if changed && state != gatewayOnline {
		g.learning.onGatewayOffline(nowEpochMs())
	}
	return changed
}

func (g *gatewayIngest) ingestCapture(capture rfCapture) {
	now := nowEpochMs()
	debugf(
		"[ingest] capture value=%d protocol=%d length=%d delay=%dms",
		capture.Value, capture.Protocol, capture.Length, capture.DelayMs,
	)
	g.gateway.onCapture(now)
	g.learning.onCapture(context.Background(), capture, now)
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
		debugf("[ingest] drop %s: not a capture payload (%v): %s", topic, err, previewText(text, 240))
		return nil
	}
	if payload.Value == nil || payload.Protocol == nil || payload.Length == nil {
		debugf("[ingest] drop %s: capture payload is missing value/protocol/length", topic)
		return nil
	}
	if !ev1527AcceptsLength(*payload.Length) {
		debugf(
			"[ingest] drop %s: length=%d, EV1527 expects %d bits",
			topic, *payload.Length, ev1527Length,
		)
		return nil
	}
	if !ev1527Valid(*payload.Value) {
		debugf(
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
