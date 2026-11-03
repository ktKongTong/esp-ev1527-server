package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type fakeGateway struct {
	pressPayload RFCode
	pressErr     error
	killErr      error
	bootErr      error
	status       Status

	pressCalls int
	killCalls  int
	bootCalls  int
	lastCode   uint64
	lastLabel  string

	burstFrames   int
	burstInterval time.Duration
	transmitCalls int
	transmitFrame TXFrame
	transmitErr   error
}

func (f *fakeGateway) PressCode(code uint64, label string) (RFCode, error) {
	return f.PressBurst(code, label, 1, 0)
}

func (f *fakeGateway) PressBurst(code uint64, label string, frames int, interval time.Duration) (RFCode, error) {
	f.pressCalls++
	f.lastCode = code
	f.lastLabel = label
	f.burstFrames = frames
	f.burstInterval = interval
	if f.pressErr != nil {
		return RFCode{}, f.pressErr
	}
	if f.pressPayload.Value == 0 {
		f.pressPayload = newRFCode(code)
	}
	return f.pressPayload, nil
}

func (f *fakeGateway) TransmitCode(value uint64, protocol, length, repeat int, label string) (TXFrame, error) {
	f.transmitCalls++
	if f.transmitErr != nil {
		return TXFrame{}, f.transmitErr
	}
	f.transmitFrame = TXFrame{Value: value, Protocol: protocol, Length: length, Repeat: repeat}
	return f.transmitFrame, nil
}

func (f *fakeGateway) Kill() error {
	f.killCalls++
	return f.killErr
}

func (f *fakeGateway) Boot() error {
	f.bootCalls++
	return f.bootErr
}

func (f *fakeGateway) Status() Status {
	return f.status
}

func newTestHandler(f *fakeGateway) http.Handler {
	log := NewLogger(io.Discard)
	return NewHTTPServer(Config{TopicPrefix: defaultTopicPrefix}, f, log).Handler()
}

func doRequest(t *testing.T, h http.Handler, method, path, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	return rr
}

func TestPressButtonSuccess(t *testing.T) {
	f := &fakeGateway{}
	h := newTestHandler(f)

	rr := doRequest(t, h, http.MethodPost, "/mock/press-button", `{"code":1714739}`)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rr.Code, rr.Body.String())
	}
	if f.pressCalls != 1 || f.lastCode != 1714739 {
		t.Fatalf("gateway called with code=%d calls=%d", f.lastCode, f.pressCalls)
	}
	if got := rr.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Fatalf("CORS header = %q", got)
	}

	var resp pressButtonResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if !resp.OK {
		t.Fatal("response not ok")
	}
	if resp.Topic != "home/OpenMQTTGateway/315toMQTT" {
		t.Fatalf("topic = %q", resp.Topic)
	}
	if resp.Hex != "0x1A2A33" {
		t.Fatalf("hex = %q", resp.Hex)
	}
	want := RFCode{Value: 1714739, Protocol: 1, Length: 24, Delay: 330}
	if resp.Payload != want {
		t.Fatalf("payload = %#v, want %#v", resp.Payload, want)
	}
}

func TestPressButtonBurstDefaults(t *testing.T) {
	f := &fakeGateway{}
	h := newTestHandler(f)

	rr := doRequest(t, h, http.MethodPost, "/mock/press-button", `{"code":1714739,"frames":3}`)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rr.Code, rr.Body.String())
	}
	if f.pressCalls != 1 || f.burstFrames != 3 {
		t.Fatalf("gateway burst calls=%d frames=%d, want 1/3", f.pressCalls, f.burstFrames)
	}
	if f.burstInterval != 80*time.Millisecond {
		t.Fatalf("interval = %s, want 80ms", f.burstInterval)
	}

	var resp pressButtonResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if resp.Frames != 3 || resp.IntervalMS != 80 {
		t.Fatalf("response frames/interval = %d/%d, want 3/80", resp.Frames, resp.IntervalMS)
	}
}

func TestPressButtonRejectsInvalidBurst(t *testing.T) {
	f := &fakeGateway{}
	h := newTestHandler(f)

	cases := []string{
		`{"code":1,"frames":0}`,
		`{"code":1,"frames":21}`,
		`{"code":1,"interval_ms":-1}`,
		`{"code":1,"interval_ms":1001}`,
		`{"code":1,"frames":20,"interval_ms":1000}`,
	}
	for _, body := range cases {
		rr := doRequest(t, h, http.MethodPost, "/mock/press-button", body)
		if rr.Code != http.StatusBadRequest {
			t.Errorf("body %q: status = %d, want 400 (response %s)", body, rr.Code, rr.Body.String())
		}
	}
	if f.pressCalls != 0 {
		t.Fatalf("gateway should not be called, got %d calls", f.pressCalls)
	}
}

func TestTransmitSuccess(t *testing.T) {
	f := &fakeGateway{}
	h := newTestHandler(f)

	rr := doRequest(t, h, http.MethodPost, "/mock/transmit", `{"code":1714739,"repeat":5}`)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rr.Code, rr.Body.String())
	}
	if f.transmitCalls != 1 {
		t.Fatalf("transmit calls = %d, want 1", f.transmitCalls)
	}
	want := TXFrame{Value: 1714739, Protocol: 1, Length: 24, Repeat: 5}
	if f.transmitFrame != want {
		t.Fatalf("frame = %#v, want %#v", f.transmitFrame, want)
	}

	var resp transmitResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if !resp.OK || resp.Hex != "0x1A2A33" || resp.Payload != want {
		t.Fatalf("unexpected response: %#v", resp)
	}
}

func TestTransmitBadRequests(t *testing.T) {
	f := &fakeGateway{}
	h := newTestHandler(f)

	cases := []string{
		``,
		`not-json`,
		`{}`,
		`{"code":1,"repeat":0}`,
		`{"code":1,"repeat":51}`,
		`{"code":16777216}`,
		`{"code":1,"length":0}`,
		`{"code":2,"length":1}`,
		`{"code":1}{"code":2}`,
	}
	for _, body := range cases {
		rr := doRequest(t, h, http.MethodPost, "/mock/transmit", body)
		if rr.Code != http.StatusBadRequest {
			t.Errorf("body %q: status = %d, want 400 (response %s)", body, rr.Code, rr.Body.String())
		}
	}
	if f.transmitCalls != 0 {
		t.Fatalf("gateway should not be called, got %d calls", f.transmitCalls)
	}
}

func TestTransmitOffline(t *testing.T) {
	f := &fakeGateway{transmitErr: ErrPoweredOff}
	h := newTestHandler(f)

	rr := doRequest(t, h, http.MethodPost, "/mock/transmit", `{"code":1714739}`)
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rr.Code)
	}
}

func TestTransmitWrongMethod(t *testing.T) {
	h := newTestHandler(&fakeGateway{})
	rr := doRequest(t, h, http.MethodGet, "/mock/transmit", "")
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rr.Code)
	}
}

func TestPressButtonBadRequests(t *testing.T) {
	f := &fakeGateway{}
	h := newTestHandler(f)

	cases := []string{
		``,
		`not-json`,
		`{}`,
		`{"code":"1714739"}`,
		`{"code":-1}`,
		`{"code":16777216}`,
		`{"code":1}{"code":2}`,
	}
	for _, body := range cases {
		rr := doRequest(t, h, http.MethodPost, "/mock/press-button", body)
		if rr.Code != http.StatusBadRequest {
			t.Errorf("body %q: status = %d, want 400 (response %s)", body, rr.Code, rr.Body.String())
		}
	}
	if f.pressCalls != 0 {
		t.Fatalf("gateway should not be called for invalid requests, got %d calls", f.pressCalls)
	}
}

func TestPressButtonOffline(t *testing.T) {
	f := &fakeGateway{pressErr: ErrPoweredOff}
	h := newTestHandler(f)

	rr := doRequest(t, h, http.MethodPost, "/mock/press-button", `{"code":1714739}`)
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rr.Code)
	}
	var resp errorResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.OK || resp.Error == "" {
		t.Fatalf("unexpected error response: %#v", resp)
	}
}

func TestPressButtonWrongMethod(t *testing.T) {
	h := newTestHandler(&fakeGateway{})
	rr := doRequest(t, h, http.MethodGet, "/mock/press-button", "")
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rr.Code)
	}
	if got := rr.Header().Get("Allow"); got != http.MethodPost {
		t.Fatalf("Allow = %q", got)
	}
}

func TestCORSPreflight(t *testing.T) {
	h := newTestHandler(&fakeGateway{})
	rr := doRequest(t, h, http.MethodOptions, "/mock/press-button", "")
	if rr.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", rr.Code)
	}
	if got := rr.Header().Get("Access-Control-Allow-Methods"); got == "" {
		t.Fatal("missing Access-Control-Allow-Methods")
	}
}

func TestKillAndBootIdempotent(t *testing.T) {
	f := &fakeGateway{killErr: ErrAlreadyOff, bootErr: ErrAlreadyOn}
	h := newTestHandler(f)

	rr := doRequest(t, h, http.MethodPost, "/mock/kill", "")
	if rr.Code != http.StatusOK {
		t.Fatalf("kill status = %d", rr.Code)
	}
	var killed powerResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &killed); err != nil {
		t.Fatalf("decode kill: %v", err)
	}
	if !killed.Already || killed.Powered {
		t.Fatalf("unexpected kill response: %#v", killed)
	}

	rr = doRequest(t, h, http.MethodPost, "/mock/boot", "")
	if rr.Code != http.StatusOK {
		t.Fatalf("boot status = %d", rr.Code)
	}
	var booted powerResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &booted); err != nil {
		t.Fatalf("decode boot: %v", err)
	}
	if !booted.Already || !booted.Powered {
		t.Fatalf("unexpected boot response: %#v", booted)
	}
}

func TestKillInternalError(t *testing.T) {
	f := &fakeGateway{killErr: errors.New("boom")}
	h := newTestHandler(f)
	rr := doRequest(t, h, http.MethodPost, "/mock/kill", "")
	if rr.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", rr.Code)
	}
}

func TestStatusAndHealth(t *testing.T) {
	f := &fakeGateway{status: Status{
		Powered:       true,
		MQTTConnected: true,
		ClientID:      defaultClientID,
		Broker:        defaultMQTTURL,
		LWT:           "home/OpenMQTTGateway/LWT",
		CommandTopic:  "home/OpenMQTTGateway/commands/MQTTto315",
		RXTopic:       "home/OpenMQTTGateway/315toMQTT",
	}}
	h := newTestHandler(f)

	rr := doRequest(t, h, http.MethodGet, "/mock/status", "")
	if rr.Code != http.StatusOK {
		t.Fatalf("status endpoint = %d", rr.Code)
	}
	var st Status
	if err := json.Unmarshal(rr.Body.Bytes(), &st); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	if !st.MQTTConnected || st.ClientID != defaultClientID {
		t.Fatalf("unexpected status: %#v", st)
	}

	rr = doRequest(t, h, http.MethodGet, "/mock/health", "")
	if rr.Code != http.StatusOK {
		t.Fatalf("health endpoint = %d", rr.Code)
	}
	var health healthResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &health); err != nil {
		t.Fatalf("decode health: %v", err)
	}
	if !health.OK || !health.MQTTConnected {
		t.Fatalf("unexpected health: %#v", health)
	}
}

func TestUnknownPath(t *testing.T) {
	h := newTestHandler(&fakeGateway{})
	rr := doRequest(t, h, http.MethodGet, "/nope", "")
	if rr.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rr.Code)
	}
}
