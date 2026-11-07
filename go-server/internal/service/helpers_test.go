package service

import (
	"context"
	"errors"
	"sync"
	"testing"

	"gorm.io/gorm"

	"esp32controlserver/internal/apierror"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/repository"
)

func newTestDB(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := repository.Open(":memory:", "")
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

func newOnlineGateway() *GatewayStatusStore {
	gateway := NewGatewayStatusStore("test-gateway")
	gateway.SetGateway(contract.GatewayOnline, nil)
	return gateway
}

type fakeTransmitter struct {
	mu       sync.Mutex
	payloads []contract.RFPayload
	err      error
}

func (f *fakeTransmitter) Send(_ context.Context, payload contract.RFPayload) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err != nil {
		return f.err
	}
	f.payloads = append(f.payloads, payload)
	return nil
}

func (f *fakeTransmitter) calls() []contract.RFPayload {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]contract.RFPayload(nil), f.payloads...)
}

func requireAPIError(t *testing.T, err error, status int, code string) *apierror.Error {
	t.Helper()
	var apiErr *apierror.Error
	if !errors.As(err, &apiErr) {
		t.Fatalf("error = %v, want apiError %s", err, code)
	}
	if apiErr.Status != status || apiErr.Code != code {
		t.Fatalf("error = %d/%s, want %d/%s", apiErr.Status, apiErr.Code, status, code)
	}
	return apiErr
}

func sampleDevice() contract.Device {
	const codeOn int64 = 1714739
	codeOff := (codeOn &^ 0xF) | ((codeOn & 0xF) ^ 1)
	return contract.Device{
		ID:               "dev_test",
		Name:             "pump",
		Location:         "pond",
		CodeOn:           codeOn,
		CodeOff:          codeOff,
		RF:               contract.RFParameters{Protocol: 1, Length: 24, Repeat: 5},
		CreatedAtEpochMs: 1000,
	}
}
