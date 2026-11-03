package main

import (
	"testing"
)

func TestFormatHex(t *testing.T) {
	tests := []struct {
		value uint64
		want  string
	}{
		{1714739, "0x1A2A33"}, // task text says 0x1A2B33, but that is 1714995
		{1714740, "0x1A2A34"},
		{0, "0x000000"},
		{1, "0x000001"},
		{rfMax24Bit, "0xFFFFFF"},
	}
	for _, tt := range tests {
		if got := formatHex(tt.value); got != tt.want {
			t.Errorf("formatHex(%d) = %q, want %q", tt.value, got, tt.want)
		}
	}
}

func TestRXPayloadJSONExact(t *testing.T) {
	raw, err := newRFCode(1714739).marshal()
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	const want = `{"value":1714739,"protocol":1,"length":24,"delay":330}`
	if string(raw) != want {
		t.Fatalf("payload = %s, want %s", raw, want)
	}
}

func TestParseTXCommand(t *testing.T) {
	cmd, err := parseTXCommand([]byte(`{"value":1714739,"protocol":1,"length":24,"repeat":5}`))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if cmd.Value == nil || *cmd.Value != 1714739 {
		t.Fatalf("unexpected value: %#v", cmd.Value)
	}
	if got := cmd.repeatCount(); got != 5 {
		t.Fatalf("repeatCount() = %d, want 5", got)
	}
}

func TestTXPayloadJSONExact(t *testing.T) {
	raw, err := (TXFrame{Value: 1714739, Protocol: 1, Length: 24, Repeat: 5}).marshal()
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	const want = `{"value":1714739,"protocol":1,"length":24,"repeat":5}`
	if string(raw) != want {
		t.Fatalf("payload = %s, want %s", raw, want)
	}
}

func TestValidateRFParams(t *testing.T) {
	if err := validateRFParams(1714739, 1, 24); err != nil {
		t.Fatalf("valid params rejected: %v", err)
	}
	cases := []struct {
		value    uint64
		protocol int
		length   int
	}{
		{1714739, 0, 24},
		{1714739, 1, 0},
		{1714739, 1, 33},
		{1 << 24, 1, 24},
	}
	for _, tc := range cases {
		if err := validateRFParams(tc.value, tc.protocol, tc.length); err == nil {
			t.Errorf("validateRFParams(%d,%d,%d) unexpectedly succeeded", tc.value, tc.protocol, tc.length)
		}
	}
}

func TestParseTXCommandDefaultsRepeat(t *testing.T) {
	cmd, err := parseTXCommand([]byte(`{"value":42}`))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got := cmd.repeatCount(); got != 1 {
		t.Fatalf("repeatCount() = %d, want 1", got)
	}
	frame := cmd.frame()
	if frame.Value != 42 || frame.Protocol != 1 || frame.Length != 24 || frame.Repeat != 1 {
		t.Fatalf("frame defaults = %#v", frame)
	}
}

func TestParseTXCommandRejectsInvalid(t *testing.T) {
	cases := []string{
		`{}`,
		`[]`,
		`not-json`,
		`{"value":1,"repeat":-1}`,
		`{"value":1,"protocol":0}`,
		`{"value":1,"length":-24}`,
	}
	for _, raw := range cases {
		if _, err := parseTXCommand([]byte(raw)); err == nil {
			t.Errorf("parseTXCommand(%s) unexpectedly succeeded", raw)
		}
	}
}

func TestRandomRFCodeIs24BitAndUnknown(t *testing.T) {
	for i := 0; i < 200; i++ {
		code, err := randomRFCode()
		if err != nil {
			t.Fatalf("randomRFCode: %v", err)
		}
		if code > rfMax24Bit {
			t.Fatalf("code %d exceeds 24 bits", code)
		}
		if code == 0 {
			t.Fatal("random code must not be zero")
		}
		if isKnownRFCode(code) {
			t.Fatalf("random code %d collides with a predefined device", code)
		}
	}
}

func TestKnownDeviceMapping(t *testing.T) {
	btn, ok := lookupDevice(1)
	if !ok {
		t.Fatal("device 1 must be predefined")
	}
	if btn.On != 1714739 || btn.Off != 1714740 {
		t.Fatalf("device 1 codes = on:%d off:%d", btn.On, btn.Off)
	}
	if _, ok := lookupDevice(2); ok {
		t.Fatal("device 2 must not be predefined")
	}
}
