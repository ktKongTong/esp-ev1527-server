package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
)

const testAuthToken = "test-auth-token"

func newTestAPI(t *testing.T) (*echo.Echo, *deviceRepository, *fakeTransmitter, *int64) {
	t.Helper()
	db := newTestDB(t)
	devices := &deviceRepository{db: db}
	idempotency := &idempotencyRepository{db: db}
	gateway := newOnlineGateway()
	transmitter := &fakeTransmitter{}
	now := int64(1_000_000)

	deviceService := newDeviceService(db, devices)
	deviceService.now = func() int64 { return now }
	commandService := newCommandService(db, devices, idempotency, gateway, transmitter)
	commandService.now = func() int64 { return now }
	learning := newLearningService(db, devices, gateway, transmitter)
	learning.now = func() int64 { return now }
	counter := 0
	learning.newID = func(prefix string) string {
		counter++
		return prefix + "_http_test_" + strconv.Itoa(counter)
	}

	api := &apiServer{
		devices:          deviceService,
		commands:         commandService,
		learning:         learning,
		gateway:          gateway,
		startedAtEpochMs: nowEpochMs() - 65_000,
	}
	return newEchoServer(api, authConfig{Token: testAuthToken}), devices, transmitter, &now
}

// doRequest sends an authenticated request. Tests that exercise the auth
// middleware itself use doRequestWithAuth.
func doRequest(
	t *testing.T,
	e *echo.Echo,
	method, target, body, contentType string,
	headers map[string]string,
) *httptest.ResponseRecorder {
	t.Helper()
	return doRequestWithAuth(
		t, e, method, target, body, contentType, headers,
		"Bearer "+testAuthToken,
	)
}

func doRequestWithAuth(
	t *testing.T,
	e *echo.Echo,
	method, target, body, contentType string,
	headers map[string]string,
	authorization string,
) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(method, target, strings.NewReader(body))
	if contentType != "" {
		request.Header.Set(echo.HeaderContentType, contentType)
	}
	if authorization != "" {
		request.Header.Set(echo.HeaderAuthorization, authorization)
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	recorder := httptest.NewRecorder()
	e.ServeHTTP(recorder, request)
	return recorder
}

func decodeErrorEnvelope(t *testing.T, recorder *httptest.ResponseRecorder) apiErrorEnvelope {
	t.Helper()
	var envelope apiErrorEnvelope
	if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
		t.Fatalf("decode error envelope %q: %v", recorder.Body.String(), err)
	}
	return envelope
}

func TestHTTPHealth(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(t, e, http.MethodGet, "/api/v1/health", "", "", map[string]string{
		"X-Request-Id": "client-request-1",
	})
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if got := recorder.Header().Get("X-Request-Id"); got != "client-request-1" {
		t.Fatalf("X-Request-Id = %q", got)
	}
	if got := recorder.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q", got)
	}

	var health healthResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &health); err != nil {
		t.Fatalf("decode health: %v", err)
	}
	if health.Status != "ok" || health.Service != serviceName || health.APIVersion != apiVersion {
		t.Fatalf("unexpected health: %+v", health)
	}
	if health.Runtime != "Go" || health.Version != serverVersion {
		t.Fatalf("unexpected runtime/version: %+v", health)
	}
	if health.UptimeSeconds < 64 || health.UptimeSeconds > 66 {
		t.Fatalf("uptimeSeconds = %d, want about 65", health.UptimeSeconds)
	}
	if health.Gateway.State != gatewayOnline || health.Gateway.Receiver.State != receiverUnknown {
		t.Fatalf("unexpected gateway: %+v", health.Gateway)
	}
}

func TestHTTPHealthIsPublic(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequestWithAuth(
		t, e, http.MethodGet, "/api/v1/health", "", "", nil, "",
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
}

func TestHTTPRequiresBearerToken(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	cases := []struct {
		name          string
		authorization string
	}{
		{"missing header", ""},
		{"wrong token", "Bearer wrong-token"},
		{"wrong scheme", "Basic " + testAuthToken},
		{"bare token", testAuthToken},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			recorder := doRequestWithAuth(
				t, e, http.MethodGet, "/api/v1/devices", "", "", nil, tc.authorization,
			)
			if recorder.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
			}
			if got := recorder.Header().Get("X-Request-Id"); got == "" {
				t.Fatal("unauthorized response has no X-Request-Id")
			}
			envelope := decodeErrorEnvelope(t, recorder)
			if envelope.Error.Code != "unauthorized" || envelope.Error.RequestID == "" {
				t.Fatalf("unexpected envelope: %+v", envelope)
			}
		})
	}
}

func TestHTTPProtectsWrites(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequestWithAuth(
		t, e, http.MethodPost, "/api/v1/learning/sessions", "", "", nil, "",
	)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
}

func TestHTTPAcceptsBearerSchemeCaseInsensitively(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequestWithAuth(
		t, e, http.MethodGet, "/api/v1/devices", "", "", nil,
		"bearer "+testAuthToken,
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
}

func TestHTTPGeneratesRequestID(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(t, e, http.MethodGet, "/api/v1/health", "", "", map[string]string{
		"X-Request-Id": strings.Repeat("x", 129),
	})
	requestID := recorder.Header().Get("X-Request-Id")
	if len(requestID) != len("req_")+24 || !strings.HasPrefix(requestID, "req_") {
		t.Fatalf("generated request id = %q", requestID)
	}
}

func TestHTTPDeviceListUsesEmptyArray(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(t, e, http.MethodGet, "/api/v1/devices", "", "", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), `"devices":[]`) {
		t.Fatalf("body must encode devices as []: %s", recorder.Body.String())
	}
}

func TestHTTPUnknownRoute(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(t, e, http.MethodGet, "/api/v1/nope", "", "", nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d", recorder.Code)
	}
	envelope := decodeErrorEnvelope(t, recorder)
	if envelope.Error.Code != "route_not_found" || envelope.Error.RequestID == "" {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}
}

func TestHTTPMethodNotAllowed(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(t, e, http.MethodDelete, "/api/v1/health", "", "", nil)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d", recorder.Code)
	}
	if envelope := decodeErrorEnvelope(t, recorder); envelope.Error.Code != "method_not_allowed" {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}
}

func TestHTTPMalformedJSON(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(
		t, e, http.MethodPost, "/api/v1/learning/sessions", "{", echo.MIMEApplicationJSON, nil,
	)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if envelope := decodeErrorEnvelope(t, recorder); envelope.Error.Code != "malformed_json" {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}
}

func TestHTTPUnsupportedMediaType(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(
		t, e, http.MethodPost, "/api/v1/learning/sessions", "{}", echo.MIMETextPlain, nil,
	)
	if recorder.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if envelope := decodeErrorEnvelope(t, recorder); envelope.Error.Code != "unsupported_media_type" {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}
}

func TestHTTPLearningLifecycle(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(t, e, http.MethodPost, "/api/v1/learning/sessions", "", "", nil)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	var session learningSession
	if err := json.Unmarshal(recorder.Body.Bytes(), &session); err != nil {
		t.Fatalf("decode session: %v", err)
	}
	if session.State != learningCollectingOn || session.ID == "" {
		t.Fatalf("unexpected session: %+v", session)
	}
	if !strings.Contains(recorder.Body.String(), `"candidates":[]`) {
		t.Fatalf("body must encode candidates as []: %s", recorder.Body.String())
	}

	recorder = doRequest(
		t, e, http.MethodGet, "/api/v1/learning/sessions/"+session.ID, "", "", nil,
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("get status = %d, body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = doRequest(
		t, e, http.MethodGet, "/api/v1/learning/sessions/ls_missing", "", "", nil,
	)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing status = %d", recorder.Code)
	}
	if envelope := decodeErrorEnvelope(t, recorder); envelope.Error.Code != "learning_session_not_found" {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}

	recorder = doRequest(
		t, e, http.MethodDelete, "/api/v1/learning/sessions/"+session.ID, "", "", nil,
	)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("cancel status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = doRequest(
		t, e, http.MethodGet, "/api/v1/learning/sessions/"+session.ID, "", "", nil,
	)
	var cancelled learningSession
	if err := json.Unmarshal(recorder.Body.Bytes(), &cancelled); err != nil {
		t.Fatalf("decode cancelled session: %v", err)
	}
	if cancelled.State != learningCancelled {
		t.Fatalf("state = %s, want cancelled", cancelled.State)
	}
}

func TestHTTPCommandIdempotency(t *testing.T) {
	e, devices, transmitter, _ := newTestAPI(t)
	if err := devices.insert(t.Context(), sampleDevice()); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	headers := map[string]string{"Idempotency-Key": "http-key-1"}
	body := `{"action":"turn_on"}`

	recorder := doRequest(
		t, e, http.MethodPost, "/api/v1/devices/dev_test/commands",
		body, echo.MIMEApplicationJSON, headers,
	)
	if recorder.Code != http.StatusAccepted {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if got := recorder.Header().Get("Idempotent-Replayed"); got != "" {
		t.Fatalf("first dispatch replayed = %q", got)
	}
	var first commandDispatched
	if err := json.Unmarshal(recorder.Body.Bytes(), &first); err != nil {
		t.Fatalf("decode dispatch: %v", err)
	}
	if first.DeviceID != "dev_test" || first.Action != actionTurnOn {
		t.Fatalf("unexpected dispatch: %+v", first)
	}

	recorder = doRequest(
		t, e, http.MethodPost, "/api/v1/devices/dev_test/commands",
		body, echo.MIMEApplicationJSON, headers,
	)
	if recorder.Code != http.StatusAccepted {
		t.Fatalf("replay status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if got := recorder.Header().Get("Idempotent-Replayed"); got != "true" {
		t.Fatalf("replay header = %q", got)
	}
	if len(transmitter.calls()) != 1 {
		t.Fatalf("publishes = %d, want 1", len(transmitter.calls()))
	}

	recorder = doRequest(
		t, e, http.MethodPost, "/api/v1/devices/dev_test/commands",
		body, echo.MIMEApplicationJSON, nil,
	)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("missing key status = %d", recorder.Code)
	}
	if envelope := decodeErrorEnvelope(t, recorder); envelope.Error.Code != "invalid_request" {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}
}

func TestHTTPDeleteDeviceLeavesTombstone(t *testing.T) {
	e, devices, _, _ := newTestAPI(t)
	if err := devices.insert(t.Context(), sampleDevice()); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	recorder := doRequest(t, e, http.MethodDelete, "/api/v1/devices/dev_test", "", "", nil)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = doRequest(t, e, http.MethodGet, "/api/v1/devices/dev_test", "", "", nil)
	if recorder.Code != http.StatusGone {
		t.Fatalf("get status = %d", recorder.Code)
	}
	envelope := decodeErrorEnvelope(t, recorder)
	if envelope.Error.Code != "device_removed" || envelope.Error.Details["removedAtEpochMs"] == "" {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}
}

func TestHTTPUpdateDeviceLabels(t *testing.T) {
	e, devices, _, _ := newTestAPI(t)
	original := sampleDevice()
	if err := devices.insert(t.Context(), original); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	jsonType := echo.MIMEApplicationJSON

	recorder := doRequest(
		t, e, http.MethodPatch, "/api/v1/devices/dev_test",
		`{"name":"  new pump  ","location":"north pond"}`, jsonType, nil,
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("patch status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	var updated device
	if err := json.Unmarshal(recorder.Body.Bytes(), &updated); err != nil {
		t.Fatalf("decode device: %v", err)
	}
	if updated.Name != "new pump" || updated.Location != "north pond" {
		t.Fatalf("labels = %q/%q", updated.Name, updated.Location)
	}
	if updated.CodeOn != original.CodeOn || updated.CodeOff != original.CodeOff ||
		updated.RF != original.RF {
		t.Fatalf("patch changed RF data: %+v", updated)
	}

	recorder = doRequest(
		t, e, http.MethodPatch, "/api/v1/devices/dev_test",
		`{"location":"south pond"}`, jsonType, nil,
	)
	if recorder.Code != http.StatusOK {
		t.Fatalf("partial patch status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &updated); err != nil {
		t.Fatalf("decode partial device: %v", err)
	}
	if updated.Name != "new pump" || updated.Location != "south pond" {
		t.Fatalf("partial labels = %q/%q", updated.Name, updated.Location)
	}

	recorder = doRequest(t, e, http.MethodGet, "/api/v1/devices/dev_test", "", "", nil)
	var fetched device
	if err := json.Unmarshal(recorder.Body.Bytes(), &fetched); err != nil {
		t.Fatalf("decode fetched device: %v", err)
	}
	if fetched.Name != updated.Name || fetched.Location != updated.Location {
		t.Fatalf("persisted labels = %q/%q", fetched.Name, fetched.Location)
	}
}

func TestHTTPUpdateDeviceRejectsInvalidInput(t *testing.T) {
	e, devices, _, _ := newTestAPI(t)
	if err := devices.insert(t.Context(), sampleDevice()); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	jsonType := echo.MIMEApplicationJSON

	cases := []struct {
		name   string
		target string
		body   string
		status int
		code   string
	}{
		{"empty body", "/api/v1/devices/dev_test", `{}`, http.StatusBadRequest, "invalid_request"},
		{"blank name", "/api/v1/devices/dev_test", `{"name":"   "}`, http.StatusBadRequest, "invalid_request"},
		{"name too long", "/api/v1/devices/dev_test", `{"name":"` + strings.Repeat("a", 65) + `"}`, http.StatusBadRequest, "invalid_request"},
		{"unknown device", "/api/v1/devices/dev_missing", `{"name":"pump"}`, http.StatusNotFound, "device_not_found"},
		{"invalid id", "/api/v1/devices/bad%20id", `{"name":"pump"}`, http.StatusBadRequest, "invalid_device_id"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			recorder := doRequest(t, e, http.MethodPatch, tc.target, tc.body, jsonType, nil)
			if recorder.Code != tc.status {
				t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
			}
			envelope := decodeErrorEnvelope(t, recorder)
			if envelope.Error.Code != tc.code {
				t.Fatalf("code = %q, want %q", envelope.Error.Code, tc.code)
			}
		})
	}
}

func TestHTTPUpdateRemovedDeviceReturnsGone(t *testing.T) {
	e, devices, _, _ := newTestAPI(t)
	if err := devices.insert(t.Context(), sampleDevice()); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	recorder := doRequest(t, e, http.MethodDelete, "/api/v1/devices/dev_test", "", "", nil)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d", recorder.Code)
	}
	recorder = doRequest(
		t, e, http.MethodPatch, "/api/v1/devices/dev_test",
		`{"name":"pump"}`, echo.MIMEApplicationJSON, nil,
	)
	if recorder.Code != http.StatusGone {
		t.Fatalf("patch status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	envelope := decodeErrorEnvelope(t, recorder)
	if envelope.Error.Code != "device_removed" {
		t.Fatalf("code = %q", envelope.Error.Code)
	}
}

func TestHTTPCORS(t *testing.T) {
	e, _, _, _ := newTestAPI(t)
	recorder := doRequest(t, e, http.MethodOptions, "/api/v1/devices", "", "", map[string]string{
		"Origin":                         "http://localhost:8080",
		"Access-Control-Request-Method":  http.MethodGet,
		"Access-Control-Request-Headers": echo.HeaderAuthorization,
	})
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("preflight status = %d", recorder.Code)
	}
	if got := recorder.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Fatalf("allow origin = %q", got)
	}
	allowed := recorder.Header().Get("Access-Control-Allow-Methods")
	if !strings.Contains(allowed, http.MethodPost) || !strings.Contains(allowed, http.MethodDelete) {
		t.Fatalf("allow methods = %q", allowed)
	}
	allowedHeaders := recorder.Header().Get("Access-Control-Allow-Headers")
	if !strings.Contains(allowedHeaders, echo.HeaderAuthorization) {
		t.Fatalf("allow headers = %q, want Authorization", allowedHeaders)
	}
}
