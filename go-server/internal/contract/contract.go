// Package contract mirrors the wire contract shared with the Kotlin server,
// the Compose clients and docs/api-contract-v1.md. Field names and enum values
// must stay byte-for-byte compatible with
// contract/src/commonMain/.../Models.kt.
package contract

const (
	ContractVersion = "2.2.0"
	APIVersion      = "1"
	ServerVersion   = "0.2.0"
	ServiceName     = "esp32-control-server"
)

const (
	GatewayOnline  = "online"
	GatewayOffline = "offline"
	GatewayUnknown = "unknown"
)

const (
	ReceiverUnknown     = "unknown"
	ReceiverReceiving   = "receiving"
	ReceiverUnavailable = "unavailable"
)

const (
	ActionTurnOn  = "turn_on"
	ActionTurnOff = "turn_off"
)

const (
	LearningCollectingOn      = "collecting_on"
	LearningCandidateOnReady  = "candidate_on_ready"
	LearningCollectingOff     = "collecting_off"
	LearningCandidateOffReady = "candidate_off_ready"
	LearningReady             = "ready"
	LearningCompleted         = "completed"
	LearningCancelled         = "cancelled"
	LearningExpired           = "expired"
	LearningFailed            = "failed"
	LearningSlotTurnOn        = "turn_on"
	LearningSlotTurnOff       = "turn_off"
)

type RFParameters struct {
	Protocol int `json:"protocol"`
	Length   int `json:"length"`
	Repeat   int `json:"repeat"`
}

type Device struct {
	ID               string       `json:"id"`
	Name             string       `json:"name"`
	Location         string       `json:"location"`
	CodeOn           int64        `json:"codeOn"`
	CodeOff          int64        `json:"codeOff"`
	RF               RFParameters `json:"rf"`
	CreatedAtEpochMs int64        `json:"createdAtEpochMs"`
}

type DeviceListResponse struct {
	Devices           []Device `json:"devices"`
	ServerTimeEpochMs int64    `json:"serverTimeEpochMs"`
}

// UpdateDeviceRequest is a partial update. A nil field stays unchanged;
// codes and RF parameters are immutable after learning.
type UpdateDeviceRequest struct {
	Name     *string `json:"name"`
	Location *string `json:"location"`
}

type ReceiverStatus struct {
	State                string `json:"state"`
	LastCaptureAtEpochMs *int64 `json:"lastCaptureAtEpochMs"`
	CaptureCount         int64  `json:"captureCount"`
}

type GatewayStatus struct {
	State                string         `json:"state"`
	Version              *string        `json:"version"`
	ClientID             *string        `json:"clientId"`
	LastChangedAtEpochMs int64          `json:"lastChangedAtEpochMs"`
	Receiver             ReceiverStatus `json:"receiver"`
}

type HealthResponse struct {
	Status            string        `json:"status"`
	Service           string        `json:"service"`
	APIVersion        string        `json:"apiVersion"`
	Version           string        `json:"version"`
	Runtime           string        `json:"runtime"`
	UptimeSeconds     int64         `json:"uptimeSeconds"`
	ServerTimeEpochMs int64         `json:"serverTimeEpochMs"`
	Gateway           GatewayStatus `json:"gateway"`
}

type CommandRequest struct {
	Action string `json:"action"`
}

type CommandDispatched struct {
	DeviceID            string `json:"deviceId"`
	Action              string `json:"action"`
	DispatchedAtEpochMs int64  `json:"dispatchedAtEpochMs"`
}

// RFPayload is the command frame published to the OpenMQTTGateway command
// topic. The gateway decodes it into a 315MHz transmission.
type RFPayload struct {
	Value    int64 `json:"value"`
	Protocol int   `json:"protocol"`
	Length   int   `json:"length"`
	Repeat   int   `json:"repeat"`
}

type LearningCandidate struct {
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

type LearningSession struct {
	ID                         string              `json:"id"`
	State                      string              `json:"state"`
	Revision                   int64               `json:"revision"`
	CreatedAtEpochMs           int64               `json:"createdAtEpochMs"`
	SessionExpiresAtEpochMs    int64               `json:"sessionExpiresAtEpochMs"`
	CollectionExpiresAtEpochMs *int64              `json:"collectionExpiresAtEpochMs"`
	CandidateExpiresAtEpochMs  *int64              `json:"candidateExpiresAtEpochMs"`
	RequiredHits               int                 `json:"requiredHits"`
	Candidates                 []LearningCandidate `json:"candidates"`
	AcceptedOnCandidateID      *string             `json:"acceptedOnCandidateId"`
	AcceptedOffCandidateID     *string             `json:"acceptedOffCandidateId"`
	FailureReason              *string             `json:"failureReason"`
}

type StartLearningRequest struct {
	NameHint     *string `json:"nameHint"`
	LocationHint *string `json:"locationHint"`
}

type ConfirmLearningRequest struct {
	Name     string `json:"name"`
	Location string `json:"location"`
}

type LearningConfirmation struct {
	Device  Device          `json:"device"`
	Session LearningSession `json:"session"`
}

type TestLearningRequest struct {
	Slot string `json:"slot"`
}

type LearningTestResult struct {
	TestID              string `json:"testId"`
	SessionID           string `json:"sessionId"`
	Slot                string `json:"slot"`
	Value               int64  `json:"value"`
	DispatchedAtEpochMs int64  `json:"dispatchedAtEpochMs"`
	Status              string `json:"status"`
}

type APIErrorBody struct {
	Code      string            `json:"code"`
	Message   string            `json:"message"`
	RequestID string            `json:"requestId"`
	Details   map[string]string `json:"details"`
}

type APIErrorEnvelope struct {
	Error APIErrorBody `json:"error"`
}
