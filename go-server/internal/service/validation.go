package service

import (
	"regexp"
	"strings"

	"esp32controlserver/internal/apierror"
)

var deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func requireDeviceID(deviceID string) error {
	if !deviceIDPattern.MatchString(deviceID) {
		return apierror.InvalidDeviceID()
	}
	return nil
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
