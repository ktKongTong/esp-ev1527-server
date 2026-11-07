// Package apierror defines the API error type and the error constructors
// shared by the service and HTTP layers.
package apierror

import (
	"fmt"
	"net/http"
)

// Error mirrors the server-side ApiException in the Kotlin implementation.
type Error struct {
	Status  int
	Code    string
	Message string
	Details map[string]string
}

func (e *Error) Error() string { return e.Message }

func New(status int, code, message string) *Error {
	return &Error{Status: status, Code: code, Message: message}
}

func NewDetails(status int, code, message string, details map[string]string) *Error {
	return &Error{Status: status, Code: code, Message: message, Details: details}
}

func InvalidDeviceID() *Error {
	return New(http.StatusBadRequest, "invalid_device_id", "Invalid device id")
}

func Unauthorized() *Error {
	return New(
		http.StatusUnauthorized,
		"unauthorized",
		"Missing or invalid access token",
	)
}

func DeviceRemoved(removedAtEpochMs int64) *Error {
	return NewDetails(
		http.StatusGone,
		"device_removed",
		"Device has been removed",
		map[string]string{"removedAtEpochMs": fmt.Sprintf("%d", removedAtEpochMs)},
	)
}

func DeviceNotFound() *Error {
	return New(http.StatusNotFound, "device_not_found", "Device not found")
}

func LearningNotFound() *Error {
	return New(http.StatusNotFound, "learning_session_not_found", "Learning session not found")
}

func InvalidRequest(message string) *Error {
	return New(http.StatusBadRequest, "invalid_request", message)
}

func Unavailable(code, message string) *Error {
	return New(http.StatusServiceUnavailable, code, message)
}

func CodePairInvalid(reason string) *Error {
	return NewDetails(
		http.StatusUnprocessableEntity,
		"code_pair_invalid",
		"The learned ON/OFF pair is invalid",
		map[string]string{"reason": reason},
	)
}
