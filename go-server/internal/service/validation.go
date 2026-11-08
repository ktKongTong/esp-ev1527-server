package service

import (
	"regexp"
	"strings"

	"esp32controlserver/internal/apierror"
)

var resourceIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func requireDeviceID(deviceID string) error {
	if !resourceIDPattern.MatchString(deviceID) {
		return apierror.InvalidDeviceID()
	}
	return nil
}

func requireGroupID(groupID string) error {
	if !resourceIDPattern.MatchString(groupID) {
		return apierror.InvalidGroupID()
	}
	return nil
}

func requireScheduleID(scheduleID string) error {
	if !resourceIDPattern.MatchString(scheduleID) {
		return apierror.InvalidScheduleID()
	}
	return nil
}

func runeLength(value string) int {
	return len([]rune(strings.TrimSpace(value)))
}

// normalizeLabel trims a user label and reports whether the result is 1-64
// characters. Learning confirmation, device editing and group naming share
// this rule so the paths cannot drift.
func normalizeLabel(value string) (string, bool) {
	trimmed := strings.TrimSpace(value)
	length := runeLength(trimmed)
	return trimmed, length >= 1 && length <= 64
}

// normalizeOptionalLabel allows an empty optional label such as a schedule
// name, but still caps it at 64 characters.
func normalizeOptionalLabel(value string) (string, bool) {
	trimmed := strings.TrimSpace(value)
	return trimmed, runeLength(trimmed) <= 64
}
