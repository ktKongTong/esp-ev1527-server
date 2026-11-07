package main

import (
	"fmt"
	"net/http"
	"regexp"
	"strings"
)

// apiError mirrors the server-side ApiException in the Kotlin implementation.
type apiError struct {
	Status  int
	Code    string
	Message string
	Details map[string]string
}

func (e *apiError) Error() string { return e.Message }

func newAPIError(status int, code, message string) *apiError {
	return &apiError{Status: status, Code: code, Message: message}
}

func newAPIErrorDetails(status int, code, message string, details map[string]string) *apiError {
	return &apiError{Status: status, Code: code, Message: message, Details: details}
}

var deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func requireDeviceID(deviceID string) error {
	if !deviceIDPattern.MatchString(deviceID) {
		return newAPIError(http.StatusBadRequest, "invalid_device_id", "Invalid device id")
	}
	return nil
}

func invalidDeviceID() *apiError {
	return newAPIError(http.StatusBadRequest, "invalid_device_id", "Invalid device id")
}

func deviceRemoved(removedAtEpochMs int64) *apiError {
	return newAPIErrorDetails(
		http.StatusGone,
		"device_removed",
		"Device has been removed",
		map[string]string{"removedAtEpochMs": fmt.Sprintf("%d", removedAtEpochMs)},
	)
}

func deviceNotFound() *apiError {
	return newAPIError(http.StatusNotFound, "device_not_found", "Device not found")
}

func learningNotFound() *apiError {
	return newAPIError(http.StatusNotFound, "learning_session_not_found", "Learning session not found")
}

func invalidRequest(message string) *apiError {
	return newAPIError(http.StatusBadRequest, "invalid_request", message)
}

func unavailable(code, message string) *apiError {
	return newAPIError(http.StatusServiceUnavailable, code, message)
}

func codePairInvalid(reason string) *apiError {
	return newAPIErrorDetails(
		http.StatusUnprocessableEntity,
		"code_pair_invalid",
		"The learned ON/OFF pair is invalid",
		map[string]string{"reason": reason},
	)
}

func runeLength(value string) int {
	return len([]rune(strings.TrimSpace(value)))
}

// normalizeDeviceLabel trims a name or location and reports whether the
// result is 1-64 characters. Learning confirmation and device editing share
// this rule so the two paths cannot drift.
func normalizeDeviceLabel(value string) (string, bool) {
	trimmed := strings.TrimSpace(value)
	length := runeLength(trimmed)
	return trimmed, length >= 1 && length <= 64
}
