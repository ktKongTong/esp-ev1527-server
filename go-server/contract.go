package main

// Wire contract shared with the Kotlin server, the Compose clients and the
// documentation in docs/api-contract-v1.md. Field names and enum values must
// stay byte-for-byte compatible with contract/src/commonMain/.../Models.kt.

const (
	contractVersion = "2.0.0"
	apiVersion      = "1"
	serverVersion   = "0.2.0"
	serviceName     = "esp32-control-server"
)

const (
	gatewayOnline  = "online"
	gatewayOffline = "offline"
	gatewayUnknown = "unknown"
)

const (
	receiverUnknown     = "unknown"
	receiverReceiving   = "receiving"
	receiverUnavailable = "unavailable"
)

const (
	actionTurnOn  = "turn_on"
	actionTurnOff = "turn_off"
)

const (
	learningCollectingOn      = "collecting_on"
	learningCandidateOnReady  = "candidate_on_ready"
	learningCollectingOff     = "collecting_off"
	learningCandidateOffReady = "candidate_off_ready"
	learningReady             = "ready"
	learningCompleted         = "completed"
	learningCancelled         = "cancelled"
	learningExpired           = "expired"
	learningFailed            = "failed"
	learningSlotTurnOn        = "turn_on"
	learningSlotTurnOff       = "turn_off"
)

type rfParameters struct {
	Protocol int `json:"protocol"`
	Length   int `json:"length"`
	Repeat   int `json:"repeat"`
}

type device struct {
	ID               string       `json:"id"`
	Name             string       `json:"name"`
	Location         string       `json:"location"`
	CodeOn           int64        `json:"codeOn"`
	CodeOff          int64        `json:"codeOff"`
	RF               rfParameters `json:"rf"`
	CreatedAtEpochMs int64        `json:"createdAtEpochMs"`
}

type deviceListResponse struct {
	Devices           []device `json:"devices"`
	ServerTimeEpochMs int64    `json:"serverTimeEpochMs"`
}

type receiverStatus struct {
	State                string `json:"state"`
	LastCaptureAtEpochMs *int64 `json:"lastCaptureAtEpochMs"`
	CaptureCount         int64  `json:"captureCount"`
}

type gatewayStatus struct {
	State                string         `json:"state"`
	Version              *string        `json:"version"`
	ClientID             *string        `json:"clientId"`
	LastChangedAtEpochMs int64          `json:"lastChangedAtEpochMs"`
	Receiver             receiverStatus `json:"receiver"`
}

type healthResponse struct {
	Status            string        `json:"status"`
	Service           string        `json:"service"`
	APIVersion        string        `json:"apiVersion"`
	Version           string        `json:"version"`
	Runtime           string        `json:"runtime"`
	UptimeSeconds     int64         `json:"uptimeSeconds"`
	ServerTimeEpochMs int64         `json:"serverTimeEpochMs"`
	Gateway           gatewayStatus `json:"gateway"`
}

type commandRequest struct {
	Action string `json:"action"`
}

type commandDispatched struct {
	DeviceID            string `json:"deviceId"`
	Action              string `json:"action"`
	DispatchedAtEpochMs int64  `json:"dispatchedAtEpochMs"`
}

type learningCandidate struct {
	ID                 string  `json:"id"`
	Slot               string  `json:"slot"`
	Value              int64   `json:"value"`
	Protocol           int     `json:"protocol"`
	Length             int     `json:"length"`
	DelayMs            int     `json:"delayMs"`
	HitCount           int     `json:"hitCount"`
	FrameCount         int     `json:"frameCount"`
	Qualified          bool    `json:"qualified"`
	Accepted           bool    `json:"accepted"`
	FirstSeenAtEpochMs int64   `json:"firstSeenAtEpochMs"`
	LastSeenAtEpochMs  int64   `json:"lastSeenAtEpochMs"`
	ConflictDeviceID   *string `json:"conflictDeviceId"`
}

type learningSession struct {
	ID                         string              `json:"id"`
	State                      string              `json:"state"`
	Revision                   int64               `json:"revision"`
	CreatedAtEpochMs           int64               `json:"createdAtEpochMs"`
	SessionExpiresAtEpochMs    int64               `json:"sessionExpiresAtEpochMs"`
	CollectionExpiresAtEpochMs *int64              `json:"collectionExpiresAtEpochMs"`
	CandidateExpiresAtEpochMs  *int64              `json:"candidateExpiresAtEpochMs"`
	RequiredHits               int                 `json:"requiredHits"`
	Candidates                 []learningCandidate `json:"candidates"`
	AcceptedOnCandidateID      *string             `json:"acceptedOnCandidateId"`
	AcceptedOffCandidateID     *string             `json:"acceptedOffCandidateId"`
	FailureReason              *string             `json:"failureReason"`
}

type startLearningRequest struct {
	NameHint     *string `json:"nameHint"`
	LocationHint *string `json:"locationHint"`
}

type confirmLearningRequest struct {
	Name     string `json:"name"`
	Location string `json:"location"`
}

type learningConfirmation struct {
	Device  device          `json:"device"`
	Session learningSession `json:"session"`
}

type testLearningRequest struct {
	Slot string `json:"slot"`
}

type learningTestResult struct {
	TestID              string `json:"testId"`
	SessionID           string `json:"sessionId"`
	Slot                string `json:"slot"`
	Value               int64  `json:"value"`
	DispatchedAtEpochMs int64  `json:"dispatchedAtEpochMs"`
	Status              string `json:"status"`
}

type apiErrorBody struct {
	Code      string            `json:"code"`
	Message   string            `json:"message"`
	RequestID string            `json:"requestId"`
	Details   map[string]string `json:"details"`
}

type apiErrorEnvelope struct {
	Error apiErrorBody `json:"error"`
}
