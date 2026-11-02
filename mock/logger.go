package main

import (
	"fmt"
	"io"
	"sync"
)

// Logger serializes all console output. MQTT callbacks, the HTTP server and the
// interactive CLI all write from different goroutines; keeping every write
// behind one mutex prevents interleaved log lines.
type Logger struct {
	mu  sync.Mutex
	out io.Writer
}

func NewLogger(out io.Writer) *Logger {
	return &Logger{out: out}
}

func (l *Logger) Printf(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	fmt.Fprintf(l.out, format+"\n", args...)
}
