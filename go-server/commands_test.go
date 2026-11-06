package main

import (
	"context"
	"errors"
	"testing"
)

func newTestCommandService(t *testing.T) (*commandService, *deviceRepository, *idempotencyRepository, *gatewayStatusStore, *fakeTransmitter, *int64) {
	t.Helper()
	db := newTestDB(t)
	devices := &deviceRepository{db: db}
	idempotency := &idempotencyRepository{db: db}
	gateway := newOnlineGateway()
	transmitter := &fakeTransmitter{}
	service := newCommandService(db, devices, idempotency, gateway, transmitter)
	now := int64(1_000_000)
	service.now = func() int64 { return now }
	return service, devices, idempotency, gateway, transmitter, &now
}

func TestCommandDispatchAndReplay(t *testing.T) {
	service, devices, idempotency, _, transmitter, now := newTestCommandService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}

	first, replayed, err := service.issue(ctx, sample.ID, actionTurnOn, "key-1")
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	if replayed {
		t.Fatal("first dispatch must not be a replay")
	}
	if first.DispatchedAtEpochMs != *now {
		t.Fatalf("dispatchedAt = %d, want %d", first.DispatchedAtEpochMs, *now)
	}
	calls := transmitter.calls()
	if len(calls) != 1 || calls[0].Value != sample.CodeOn || calls[0].Repeat != 5 {
		t.Fatalf("unexpected publish: %+v", calls)
	}

	*now += 5_000
	second, replayed, err := service.issue(ctx, sample.ID, actionTurnOn, "key-1")
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if !replayed {
		t.Fatal("second dispatch with the same key must be a replay")
	}
	if second.DispatchedAtEpochMs != first.DispatchedAtEpochMs {
		t.Fatalf("replay timestamp = %d, want %d", second.DispatchedAtEpochMs, first.DispatchedAtEpochMs)
	}
	if len(transmitter.calls()) != 1 {
		t.Fatal("replay must not publish again")
	}

	_, _, err = service.issue(ctx, sample.ID, actionTurnOff, "key-1")
	requireAPIError(t, err, 409, "idempotency_conflict")

	_, _, err = service.issue(ctx, sample.ID, actionTurnOn, "")
	requireAPIError(t, err, 400, "invalid_request")

	_, _, err = service.issue(ctx, sample.ID, "toggle", "key-2")
	requireAPIError(t, err, 400, "invalid_request")

	// The key expires with the 10-minute idempotency window.
	*now += idempotencyTTLMs + 1
	third, replayed, err := service.issue(ctx, sample.ID, actionTurnOn, "key-1")
	if err != nil {
		t.Fatalf("issue after expiry: %v", err)
	}
	if replayed || third.DispatchedAtEpochMs != *now {
		t.Fatalf("unexpected post-expiry result: replayed=%t result=%+v", replayed, third)
	}
	if len(transmitter.calls()) != 2 {
		t.Fatalf("publishes = %d, want 2", len(transmitter.calls()))
	}

	record, err := idempotency.find(ctx, "key-1")
	if err != nil {
		t.Fatalf("find idempotency record: %v", err)
	}
	if record == nil || record.DispatchedAtEpochMs == nil {
		t.Fatalf("idempotency record not marked dispatched: %+v", record)
	}
}

func TestCommandRejectsOfflineGateway(t *testing.T) {
	service, devices, idempotency, gateway, transmitter, _ := newTestCommandService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	gateway.setGateway(gatewayOffline, nil)

	_, _, err := service.issue(ctx, sample.ID, actionTurnOn, "key-offline")
	requireAPIError(t, err, 503, "gateway_unavailable")
	if len(transmitter.calls()) != 0 {
		t.Fatal("offline gateway must not publish")
	}
	record, err := idempotency.find(ctx, "key-offline")
	if err != nil {
		t.Fatalf("find idempotency record: %v", err)
	}
	if record != nil {
		t.Fatal("a rejected command must not reserve its idempotency key")
	}

	gateway.setGateway(gatewayOnline, nil)
	if _, _, err := service.issue(ctx, sample.ID, actionTurnOn, "key-offline"); err != nil {
		t.Fatalf("issue after reconnect: %v", err)
	}
	if len(transmitter.calls()) != 1 {
		t.Fatalf("publishes = %d, want 1", len(transmitter.calls()))
	}
}

func TestCommandPublishFailureReleasesKey(t *testing.T) {
	service, devices, idempotency, _, transmitter, _ := newTestCommandService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	transmitter.err = errors.New("broker is down")

	_, _, err := service.issue(ctx, sample.ID, actionTurnOn, "key-fail")
	requireAPIError(t, err, 503, "gateway_unavailable")

	record, err := idempotency.find(ctx, "key-fail")
	if err != nil {
		t.Fatalf("find idempotency record: %v", err)
	}
	if record != nil {
		t.Fatal("publish failure must release the idempotency key")
	}

	transmitter.err = nil
	if _, _, err := service.issue(ctx, sample.ID, actionTurnOn, "key-fail"); err != nil {
		t.Fatalf("retry: %v", err)
	}
	if len(transmitter.calls()) != 1 {
		t.Fatalf("publishes = %d, want 1", len(transmitter.calls()))
	}
}

func TestCommandInProgress(t *testing.T) {
	service, devices, idempotency, _, _, now := newTestCommandService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	if err := idempotency.reserve(ctx, idempotencyRecord{
		IdempotencyKey:   "key-progress",
		DeviceID:         sample.ID,
		Action:           actionTurnOn,
		CreatedAtEpochMs: *now,
	}); err != nil {
		t.Fatalf("reserve: %v", err)
	}

	_, _, err := service.issue(ctx, sample.ID, actionTurnOn, "key-progress")
	requireAPIError(t, err, 409, "command_in_progress")
}

func TestCommandRejectsRemovedAndUnknownDevices(t *testing.T) {
	service, devices, _, _, _, now := newTestCommandService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	if err := devices.delete(ctx, sample.ID); err != nil {
		t.Fatalf("delete device: %v", err)
	}
	if err := devices.addTombstone(ctx, sample.ID, *now); err != nil {
		t.Fatalf("add tombstone: %v", err)
	}

	_, _, err := service.issue(ctx, sample.ID, actionTurnOn, "key-removed")
	apiErr := requireAPIError(t, err, 410, "device_removed")
	if apiErr.Details["removedAtEpochMs"] != "1000000" {
		t.Fatalf("details = %+v", apiErr.Details)
	}

	_, _, err = service.issue(ctx, "dev_missing", actionTurnOn, "key-missing")
	requireAPIError(t, err, 404, "device_not_found")

	_, _, err = service.issue(ctx, "bad id!", actionTurnOn, "key-invalid")
	requireAPIError(t, err, 400, "invalid_device_id")
}
