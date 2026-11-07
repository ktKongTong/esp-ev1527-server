package main

import "testing"

func TestParseCapture(t *testing.T) {
	cases := []struct {
		name   string
		text   string
		want   *rfCapture
		absent bool
	}{
		{
			name: "full payload",
			text: `{"value":1714739,"protocol":1,"length":24,"delay":330}`,
			want: &rfCapture{Value: 1714739, Protocol: 1, Length: 24, DelayMs: 330},
		},
		{
			name: "delay defaults",
			text: `{"value":1714739,"protocol":1,"length":24}`,
			want: &rfCapture{Value: 1714739, Protocol: 1, Length: 24, DelayMs: 330},
		},
		{
			name: "negative delay clamps",
			text: `{"value":0,"protocol":1,"length":24,"delay":-5}`,
			want: &rfCapture{Value: 0, Protocol: 1, Length: 24, DelayMs: 0},
		},
		{
			name:   "missing value",
			text:   `{"protocol":1,"length":24}`,
			absent: true,
		},
		{
			name:   "missing protocol",
			text:   `{"value":1714739,"length":24}`,
			absent: true,
		},
		{
			name:   "missing length",
			text:   `{"value":1714739,"protocol":1}`,
			absent: true,
		},
		{
			name: "other protocol allowed",
			text: `{"value":1714739,"protocol":22,"length":24}`,
			want: &rfCapture{Value: 1714739, Protocol: 22, Length: 24, DelayMs: 330},
		},
		{
			name:   "wrong length",
			text:   `{"value":1714739,"protocol":1,"length":23}`,
			absent: true,
		},
		{
			name:   "value out of range",
			text:   `{"value":16777216,"protocol":1,"length":24}`,
			absent: true,
		},
		{
			name:   "not json",
			text:   `not json`,
			absent: true,
		},
	}

	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			got := parseCapture("home/OpenMQTTGateway/315_433/Pulses", testCase.text)
			if testCase.absent {
				if got != nil {
					t.Fatalf("capture = %+v, want nil", got)
				}
				return
			}
			if got == nil || *got != *testCase.want {
				t.Fatalf("capture = %+v, want %+v", got, testCase.want)
			}
		})
	}
}

func TestGatewayIngestTransitions(t *testing.T) {
	db := newTestDB(t)
	devices := &deviceRepository{db: db}
	gateway := newGatewayStatusStore("gw-test")
	learning := newLearningService(db, devices, gateway, &fakeTransmitter{})
	ingest := newGatewayIngest(
		config{SubscribedTopic: "home/OpenMQTTGateway/#"},
		gateway,
		learning,
	)

	if state := gateway.snapshot().State; state != gatewayUnknown {
		t.Fatalf("initial state = %s, want unknown", state)
	}

	ingest.onMessage("home/OpenMQTTGateway/version", []byte(`"1.8.0"`))
	snapshot := gateway.snapshot()
	if snapshot.State != gatewayOnline {
		t.Fatalf("state = %s, want online", snapshot.State)
	}
	if snapshot.Version == nil || *snapshot.Version != "1.8.0" {
		t.Fatalf("version = %v", snapshot.Version)
	}

	ingest.onMessage(
		"home/OpenMQTTGateway/315_433/Pulses",
		[]byte(`{"value":1714739,"protocol":1,"length":24,"delay":330}`),
	)
	snapshot = gateway.snapshot()
	if snapshot.Receiver.State != receiverReceiving || snapshot.Receiver.CaptureCount != 1 {
		t.Fatalf("receiver = %+v", snapshot.Receiver)
	}
	if snapshot.Receiver.LastCaptureAtEpochMs == nil {
		t.Fatal("lastCaptureAtEpochMs must be set")
	}

	// The protocol number is gateway metadata, so a different value still counts.
	ingest.onMessage(
		"home/OpenMQTTGateway/315_433/Pulses",
		[]byte(`{"value":1714739,"protocol":22,"length":24}`),
	)
	if snapshot = gateway.snapshot(); snapshot.Receiver.CaptureCount != 2 {
		t.Fatalf("capture count = %d, want 2", snapshot.Receiver.CaptureCount)
	}

	// A frame with the wrong length must not reach the receiver counter.
	ingest.onMessage(
		"home/OpenMQTTGateway/315_433/Pulses",
		[]byte(`{"value":1714739,"protocol":1,"length":23}`),
	)
	if snapshot = gateway.snapshot(); snapshot.Receiver.CaptureCount != 2 {
		t.Fatalf("capture count = %d, want 2", snapshot.Receiver.CaptureCount)
	}

	// Command echoes and topics outside the prefix are ignored.
	ingest.onMessage(
		"home/OpenMQTTGateway/commands/MQTTto433",
		[]byte(`{"value":1714739,"protocol":1,"length":24}`),
	)
	ingest.onMessage(
		"other/prefix/Pulses",
		[]byte(`{"value":1714739,"protocol":1,"length":24}`),
	)
	if snapshot = gateway.snapshot(); snapshot.Receiver.CaptureCount != 2 {
		t.Fatalf("capture count = %d, want 2", snapshot.Receiver.CaptureCount)
	}

	ingest.onMessage("home/OpenMQTTGateway/LWT", []byte(`"offline"`))
	snapshot = gateway.snapshot()
	if snapshot.State != gatewayOffline {
		t.Fatalf("state = %s, want offline", snapshot.State)
	}
	if snapshot.Receiver.State != receiverUnknown {
		t.Fatalf("receiver = %s, want unknown", snapshot.Receiver.State)
	}

	// Reconnecting the broker alone keeps the gateway offline; only the
	// retained LWT brings it back.
	ingest.onDisconnected()
	if snapshot = gateway.snapshot(); snapshot.State != gatewayOffline {
		t.Fatalf("state = %s, want offline", snapshot.State)
	}
	ingest.onMessage("home/OpenMQTTGateway/LWT", []byte(`"online"`))
	snapshot = gateway.snapshot()
	if snapshot.State != gatewayOnline {
		t.Fatalf("state = %s, want online", snapshot.State)
	}
}

func TestGatewayCaptureFeedsLearning(t *testing.T) {
	db := newTestDB(t)
	devices := &deviceRepository{db: db}
	gateway := newOnlineGateway()
	learning := newLearningService(db, devices, gateway, &fakeTransmitter{})
	ingest := newGatewayIngest(
		config{SubscribedTopic: "home/OpenMQTTGateway/#"},
		gateway,
		learning,
	)

	session, err := learning.start(startLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	ingest.onMessage(
		"home/OpenMQTTGateway/315_433/Pulses",
		[]byte(`{"value":1714739,"protocol":1,"length":24,"delay":330}`),
	)
	current, err := learning.getLearning(session.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if len(current.Candidates) != 1 || current.Candidates[0].HitCount != 1 {
		t.Fatalf("unexpected candidates: %+v", current.Candidates)
	}
}

func TestGatewayOfflineFailsActiveCollection(t *testing.T) {
	db := newTestDB(t)
	devices := &deviceRepository{db: db}
	gateway := newOnlineGateway()
	learning := newLearningService(db, devices, gateway, &fakeTransmitter{})
	ingest := newGatewayIngest(
		config{SubscribedTopic: "home/OpenMQTTGateway/#"},
		gateway,
		learning,
	)

	session, err := learning.start(startLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	ingest.onMessage("home/OpenMQTTGateway/LWT", []byte(`"offline"`))

	current, err := learning.getLearning(session.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if current.State != learningFailed || current.FailureReason == nil ||
		*current.FailureReason != "gateway_offline" {
		t.Fatalf("unexpected session: %+v", current)
	}
}
