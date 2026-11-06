package main

import (
	"context"
	"sync"
	"testing"

	"gorm.io/gorm"
)

func newTestDB(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := openDatabase(":memory:")
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() {
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
	})
	return db
}

func newOnlineGateway() *gatewayStatusStore {
	gateway := newGatewayStatusStore("test-gateway")
	gateway.setGateway(gatewayOnline, nil)
	return gateway
}

type fakeTransmitter struct {
	mu       sync.Mutex
	payloads []rfPayload
	err      error
}

func (f *fakeTransmitter) Send(_ context.Context, payload rfPayload) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err != nil {
		return f.err
	}
	f.payloads = append(f.payloads, payload)
	return nil
}

func (f *fakeTransmitter) calls() []rfPayload {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]rfPayload(nil), f.payloads...)
}

func requireAPIError(t *testing.T, err error, status int, code string) *apiError {
	t.Helper()
	apiErr, ok := err.(*apiError)
	if !ok {
		t.Fatalf("error = %v, want apiError %s", err, code)
	}
	if apiErr.Status != status || apiErr.Code != code {
		t.Fatalf("error = %d/%s, want %d/%s", apiErr.Status, apiErr.Code, status, code)
	}
	return apiErr
}

func sampleDevice() device {
	const codeOn int64 = 1714739
	codeOff := (codeOn &^ 0xF) | ((codeOn & 0xF) ^ 1)
	return device{
		ID:               "dev_test",
		Name:             "pump",
		Location:         "pond",
		CodeOn:           codeOn,
		CodeOff:          codeOff,
		RF:               rfParameters{Protocol: 1, Length: 24, Repeat: 5},
		CreatedAtEpochMs: 1000,
	}
}
