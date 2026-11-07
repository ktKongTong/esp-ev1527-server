// Package idgen creates the prefixed random identifiers used by the contract.
package idgen

import (
	"crypto/rand"
	"encoding/hex"
	"strings"
)

// New matches the Kotlin server: a prefix plus 12 random bytes as uppercase
// hexadecimal.
func New(prefix string) string {
	buf := make([]byte, 12)
	if _, err := rand.Read(buf); err != nil {
		panic("crypto/rand failed: " + err.Error())
	}
	return prefix + "_" + strings.ToUpper(hex.EncodeToString(buf))
}
