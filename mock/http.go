package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

// gatewayAPI keeps the HTTP layer testable without a live broker.
type gatewayAPI interface {
	PressCode(code uint64, label string) (RFCode, error)
	PressBurst(code uint64, label string, frames int, interval time.Duration) (RFCode, error)
	TransmitCode(value uint64, protocol, length, repeat int, label string) (TXFrame, error)
	Kill() error
	Boot() error
	Status() Status
}

type HTTPServer struct {
	topics Topics
	gw     gatewayAPI
	log    *Logger
}

func NewHTTPServer(cfg Config, gw gatewayAPI, log *Logger) *HTTPServer {
	return &HTTPServer{
		topics: NewTopics(cfg.TopicPrefix),
		gw:     gw,
		log:    log,
	}
}

func (h *HTTPServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", h.handleRoot)
	mux.HandleFunc("/mock/press-button", h.handlePressButton)
	mux.HandleFunc("/mock/transmit", h.handleTransmit)
	mux.HandleFunc("/mock/health", h.handleHealth)
	mux.HandleFunc("/mock/status", h.handleStatus)
	mux.HandleFunc("/mock/kill", h.handleKill)
	mux.HandleFunc("/mock/boot", h.handleBoot)
	return withCORS(mux)
}

func (h *HTTPServer) handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		methodNotAllowed(w, http.MethodGet+", "+http.MethodHead)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	fmt.Fprint(w, "OpenMQTTGateway 315MHz Mock\n\n")
	fmt.Fprint(w, "POST /mock/press-button  {\"code\":1714739,\"frames\":1}\n")
	fmt.Fprint(w, "POST /mock/transmit      {\"code\":1714739,\"repeat\":5}\n")
	fmt.Fprint(w, "GET  /mock/health\n")
	fmt.Fprint(w, "GET  /mock/status\n")
	fmt.Fprint(w, "POST /mock/kill\n")
	fmt.Fprint(w, "POST /mock/boot\n")
}

type pressButtonRequest struct {
	Code       *uint64 `json:"code"`
	Frames     *int    `json:"frames"`
	IntervalMS *int    `json:"interval_ms"`
}

type pressButtonResponse struct {
	OK         bool   `json:"ok"`
	Topic      string `json:"topic"`
	Payload    RFCode `json:"payload"`
	Hex        string `json:"hex"`
	Frames     int    `json:"frames"`
	IntervalMS int    `json:"interval_ms"`
}

func (h *HTTPServer) handlePressButton(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	defer r.Body.Close()

	var req pressButtonRequest
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, `请求体必须是 JSON，例如 {"code": 1714739}: `+err.Error())
		return
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		writeError(w, http.StatusBadRequest, "请求体只能包含一个 JSON 对象")
		return
	}
	if req.Code == nil {
		writeError(w, http.StatusBadRequest, "缺少 code 字段")
		return
	}
	code := *req.Code
	if code > rfMax24Bit {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("code=%d 超出 24 位范围 (0-%d)", code, rfMax24Bit))
		return
	}

	frames := 1
	if req.Frames != nil {
		frames = *req.Frames
	}
	if frames < 1 || frames > rfMaxPressFrames {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("frames 必须在 1-%d 之间", rfMaxPressFrames))
		return
	}
	intervalMS := 0
	if req.IntervalMS != nil {
		intervalMS = *req.IntervalMS
	}
	if intervalMS < 0 || intervalMS > 1000 {
		writeError(w, http.StatusBadRequest, "interval_ms 必须在 0-1000 之间")
		return
	}
	if frames > 1 && intervalMS*(frames-1) > 3000 {
		writeError(w, http.StatusBadRequest, "多帧总时长不能超过 3000ms")
		return
	}
	interval := time.Duration(intervalMS) * time.Millisecond
	if frames > 1 && interval == 0 {
		intervalMS = 80
		interval = 80 * time.Millisecond
	}

	payload, err := h.gw.PressBurst(code, "HTTP /mock/press-button 触发按键", frames, interval)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, err.Error())
		return
	}

	h.log.Printf("[HTTP] POST /mock/press-button code=%d (HEX: %s) frames=%d interval=%s -> %s",
		code, formatHex(code), frames, interval, h.topics.RX)
	writeJSON(w, http.StatusOK, pressButtonResponse{
		OK:         true,
		Topic:      h.topics.RX,
		Payload:    payload,
		Hex:        formatHex(code),
		Frames:     frames,
		IntervalMS: intervalMS,
	})
}

type transmitRequest struct {
	Code     *uint64 `json:"code"`
	Protocol *int    `json:"protocol"`
	Length   *int    `json:"length"`
	Repeat   *int    `json:"repeat"`
}

type transmitResponse struct {
	OK      bool    `json:"ok"`
	Payload TXFrame `json:"payload"`
	Hex     string  `json:"hex"`
}

func (h *HTTPServer) handleTransmit(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	defer r.Body.Close()

	var req transmitRequest
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, `请求体必须是 JSON，例如 {"code":1714739,"repeat":5}: `+err.Error())
		return
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		writeError(w, http.StatusBadRequest, "请求体只能包含一个 JSON 对象")
		return
	}
	if req.Code == nil {
		writeError(w, http.StatusBadRequest, "缺少 code 字段")
		return
	}

	protocol, length, repeat := rfProtocol, rfBitLength, 5
	if req.Protocol != nil {
		protocol = *req.Protocol
	}
	if req.Length != nil {
		length = *req.Length
	}
	if req.Repeat != nil {
		repeat = *req.Repeat
	}
	if err := validateRFParams(*req.Code, protocol, length); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if repeat < 1 || repeat > rfMaxRepeat {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("repeat 必须在 1-%d 之间", rfMaxRepeat))
		return
	}

	frame, err := h.gw.TransmitCode(*req.Code, protocol, length, repeat, "HTTP /mock/transmit")
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, err.Error())
		return
	}

	h.log.Printf("[HTTP] POST /mock/transmit code=%d (HEX: %s) repeat=%d", *req.Code, formatHex(*req.Code), repeat)
	writeJSON(w, http.StatusOK, transmitResponse{
		OK:      true,
		Payload: frame,
		Hex:     formatHex(frame.Value),
	})
}

type healthResponse struct {
	OK            bool   `json:"ok"`
	Powered       bool   `json:"powered"`
	MQTTConnected bool   `json:"mqtt_connected"`
	ClientID      string `json:"client_id"`
	UptimeSeconds int64  `json:"uptime_seconds"`
}

func (h *HTTPServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		methodNotAllowed(w, http.MethodGet+", "+http.MethodHead)
		return
	}
	st := h.gw.Status()
	writeJSON(w, http.StatusOK, healthResponse{
		OK:            true,
		Powered:       st.Powered,
		MQTTConnected: st.MQTTConnected,
		ClientID:      st.ClientID,
		UptimeSeconds: st.UptimeSeconds,
	})
}

func (h *HTTPServer) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		methodNotAllowed(w, http.MethodGet+", "+http.MethodHead)
		return
	}
	writeJSON(w, http.StatusOK, h.gw.Status())
}

type powerResponse struct {
	OK      bool `json:"ok"`
	Powered bool `json:"powered"`
	Already bool `json:"already,omitempty"`
}

func (h *HTTPServer) handleKill(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	if err := h.gw.Kill(); err != nil {
		if errors.Is(err, ErrAlreadyOff) {
			writeJSON(w, http.StatusOK, powerResponse{OK: true, Powered: false, Already: true})
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, powerResponse{OK: true, Powered: false})
}

func (h *HTTPServer) handleBoot(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	if err := h.gw.Boot(); err != nil {
		if errors.Is(err, ErrAlreadyOn) {
			writeJSON(w, http.StatusOK, powerResponse{OK: true, Powered: true, Already: true})
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, powerResponse{OK: true, Powered: true})
}

type errorResponse struct {
	OK    bool   `json:"ok"`
	Error string `json:"error"`
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{OK: false, Error: message})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

func methodNotAllowed(w http.ResponseWriter, allowed string) {
	w.Header().Set("Allow", allowed)
	writeError(w, http.StatusMethodNotAllowed, "method not allowed")
}

func withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		w.Header().Set("Access-Control-Max-Age", "86400")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
