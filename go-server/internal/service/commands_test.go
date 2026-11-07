package service

import (
	"context"
	"errors"
	"testing"

	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/repository"
)

func newTestCommandService(t *testing.T) (
	*CommandService,
	*repository.DeviceRepository,
	*repository.IdempotencyRepository,
	*GatewayStatusStore,
	*fakeTransmitter,
	*int64,
) {
	t.Helper()
	db := newTestDB(t)
	devices := repository.NewDeviceRepository(db)
	idempotency := repository.NewIdempotencyRepository(db)
	gateway := newOnlineGateway()
	transmitter := &fakeTransmitter{}
	now := int64(1_000_000)
	service := NewCommandService(
		db, devices, idempotency, gateway, transmitter,
		WithNow(func() int64 { return now }),
	)
	return service, devices, idempotency, gateway, transmitter, &now
}

func TestCommandDispatchAndReplay(t *testing.T) {
	service, devices, idempotency, _, transmitter, now := newTestCommandService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.Insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}

	first, replayed, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-1")
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
	second, replayed, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-1")
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if !replayed {
		t.Fatal("second dispatch with the same key must be a replay")
	}
	if second.DispatchedAtEpochMs != first.DispatchedAtEpochMs {
		t.Fatalf(
			"replay timestamp = %d, want %d",
			second.DispatchedAtEpochMs, first.DispatchedAtEpochMs,
		)
	}
	if len(transmitter.calls()) != 1 {
		t.Fatal("replay must not publish again")
	}

	_, _, err = service.Issue(ctx, sample.ID, contract.ActionTurnOff, "key-1")
	requireAPIError(t, err, 409, "idempotency_conflict")

	_, _, err = service.Issue(ctx, sample.ID, contract.ActionTurnOn, "")
	requireAPIError(t, err, 400, "invalid_request")

	_, _, err = service.Issue(ctx, sample.ID, "toggle", "key-2")
	requireAPIError(t, err, 400, "invalid_request")

	// The key expires with the 10-minute idempotency window.
	*now += idempotencyTTLMs + 1
	third, replayed, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-1")
	if err != nil {
		t.Fatalf("issue after expiry: %v", err)
	}
	if replayed || third.DispatchedAtEpochMs != *now {
		t.Fatalf("unexpected post-expiry result: replayed=%t result=%+v", replayed, third)
	}
	if len(transmitter.calls()) != 2 {
		t.Fatalf("publishes = %d, want 2", len(transmitter.calls()))
	}

	record, err := idempotency.Find(ctx, "key-1")
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
	if err := devices.Insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	gateway.SetGateway(contract.GatewayOffline, nil)

	_, _, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-offline")
	requireAPIError(t, err, 503, "gateway_unavailable")
	if len(transmitter.calls()) != 0 {
		t.Fatal("offline gateway must not publish")
	}
	record, err := idempotency.Find(ctx, "key-offline")
	if err != nil {
		t.Fatalf("find idempotency record: %v", err)
	}
	if record != nil {
		t.Fatal("a rejected command must not reserve its idempotency key")
	}

	gateway.SetGateway(contract.GatewayOnline, nil)
	if _, _, err := service.Issue(
		ctx, sample.ID, contract.ActionTurnOn, "key-offline",
	); err != nil {
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
	if err := devices.Insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	transmitter.err = errors.New("broker is down")

	_, _, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-fail")
	requireAPIError(t, err, 503, "gateway_unavailable")

	record, err := idempotency.Find(ctx, "key-fail")
	if err != nil {
		t.Fatalf("find idempotency record: %v", err)
	}
	if record != nil {
		t.Fatal("publish failure must release the idempotency key")
	}

	transmitter.err = nil
	if _, _, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-fail"); err != nil {
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
	if err := devices.Insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	if err := idempotency.Reserve(ctx, repository.IdempotencyRecord{
		IdempotencyKey:   "key-progress",
		DeviceID:         sample.ID,
		Action:           contract.ActionTurnOn,
		CreatedAtEpochMs: *now,
	}); err != nil {
		t.Fatalf("reserve: %v", err)
	}

	_, _, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-progress")
	requireAPIError(t, err, 409, "command_in_progress")
}

func TestCommandRejectsRemovedAndUnknownDevices(t *testing.T) {
	service, devices, _, _, _, now := newTestCommandService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.Insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	if err := devices.Delete(ctx, sample.ID); err != nil {
		t.Fatalf("delete device: %v", err)
	}
	if err := devices.AddTombstone(ctx, sample.ID, *now); err != nil {
		t.Fatalf("add tombstone: %v", err)
	}

	_, _, err := service.Issue(ctx, sample.ID, contract.ActionTurnOn, "key-removed")
	apiErr := requireAPIError(t, err, 410, "device_removed")
	if apiErr.Details["removedAtEpochMs"] != "1000000" {
		t.Fatalf("details = %+v", apiErr.Details)
	}

	_, _, err = service.Issue(ctx, "dev_missing", contract.ActionTurnOn, "key-missing")
	requireAPIError(t, err, 404, "device_not_found")

	_, _, err = service.Issue(ctx, "bad id!", contract.ActionTurnOn, "key-invalid")
	requireAPIError(t, err, 400, "invalid_device_id")
}
