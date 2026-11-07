package service

import (
	"time"

	"esp32controlserver/internal/idgen"
)

type options struct {
	now   func() int64
	newID func(string) string
}

// Option overrides a service dependency. Production uses the defaults; tests
// use it to control the clock and generated identifiers.
type Option func(*options)

func WithNow(now func() int64) Option {
	return func(o *options) {
		o.now = now
	}
}

func WithIDGenerator(newID func(string) string) Option {
	return func(o *options) {
		o.newID = newID
	}
}

func resolveOptions(opts []Option) options {
	resolved := options{
		now:   func() int64 { return time.Now().UnixMilli() },
		newID: idgen.New,
	}
	for _, opt := range opts {
		opt(&resolved)
	}
	return resolved
}
