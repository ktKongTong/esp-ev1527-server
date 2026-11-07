package service

import (
	"context"
	"fmt"
	"testing"

	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/repository"
)

func newTestLearningService(t *testing.T) (
	*LearningService,
	*repository.DeviceRepository,
	*GatewayStatusStore,
	*fakeTransmitter,
	*int64,
) {
	t.Helper()
	db := newTestDB(t)
	devices := repository.NewDeviceRepository(db)
	gateway := newOnlineGateway()
	transmitter := &fakeTransmitter{}
	now := int64(1_000_000)
	counter := 0
	learning := NewLearningService(
		db, devices, gateway, transmitter,
		WithNow(func() int64 { return now }),
		WithIDGenerator(func(prefix string) string {
			counter++
			return fmt.Sprintf("%s_%d", prefix, counter)
		}),
	)
	return learning, devices, gateway, transmitter, &now
}

func press(t *testing.T, learning *LearningService, now *int64, code int64, times int) {
	t.Helper()
	for i := 0; i < times; i++ {
		// Separate each press from the previous frame by at least the gap the
		// state machine treats as a new physical press.
		*now += pressGapMs
		learning.OnCapture(context.Background(), rfCapture{
			Value:    code,
			Protocol: 1,
			Length:   24,
			DelayMs:  330,
		}, *now)
	}
}

func TestLearningHappyPath(t *testing.T) {
	service, devices, _, transmitter, now := newTestLearningService(t)
	ctx := context.Background()
	sample := sampleDevice()

	session, err := service.Start(contract.StartLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	if session.State != contract.LearningCollectingOn || session.RequiredHits != 3 {
		t.Fatalf("unexpected session: %+v", session)
	}
	if session.Candidates == nil {
		t.Fatal("candidates must encode as an empty array, not null")
	}

	// One physical press produces three frames within 400ms. It counts once.
	for i := 0; i < 3; i++ {
		service.OnCapture(ctx, rfCapture{
			Value: sample.CodeOn, Protocol: 1, Length: 24, DelayMs: 330,
		}, *now)
		*now += 100
	}
	current, err := service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get after first press: %v", err)
	}
	if len(current.Candidates) != 1 {
		t.Fatalf("candidates = %d, want 1", len(current.Candidates))
	}
	candidate := current.Candidates[0]
	if candidate.HitCount != 1 || candidate.FrameCount != 3 {
		t.Fatalf(
			"hitCount=%d frameCount=%d, want 1/3",
			candidate.HitCount, candidate.FrameCount,
		)
	}
	if current.State != contract.LearningCollectingOn {
		t.Fatalf("state = %s, want collecting_on", current.State)
	}

	// Two more independent presses reach the required hit count.
	press(t, service, now, sample.CodeOn, 2)
	current, err = service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get after qualification: %v", err)
	}
	if current.State != contract.LearningCandidateOnReady {
		t.Fatalf("state = %s, want candidate_on_ready", current.State)
	}
	if !current.Candidates[0].Qualified || current.Candidates[0].HitCount != 3 {
		t.Fatalf("unexpected candidate: %+v", current.Candidates[0])
	}
	if current.CollectionExpiresAtEpochMs != nil || current.CandidateExpiresAtEpochMs == nil {
		t.Fatal("candidate window must replace the collection window")
	}

	current, err = service.AcceptCandidate(ctx, session.ID, current.Candidates[0].ID)
	if err != nil {
		t.Fatalf("accept on: %v", err)
	}
	if current.State != contract.LearningCollectingOff {
		t.Fatalf("state = %s, want collecting_off", current.State)
	}

	press(t, service, now, sample.CodeOff, 3)
	current, err = service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get after off presses: %v", err)
	}
	if current.State != contract.LearningCandidateOffReady {
		t.Fatalf("state = %s, want candidate_off_ready", current.State)
	}
	offCandidate := current.Candidates[len(current.Candidates)-1]
	if offCandidate.Slot != contract.LearningSlotTurnOff || !offCandidate.Qualified {
		t.Fatalf("unexpected off candidate: %+v", offCandidate)
	}

	current, err = service.AcceptCandidate(ctx, session.ID, offCandidate.ID)
	if err != nil {
		t.Fatalf("accept off: %v", err)
	}
	if current.State != contract.LearningReady {
		t.Fatalf("state = %s, want ready", current.State)
	}

	testResult, err := service.TestLearning(ctx, session.ID, contract.LearningSlotTurnOn)
	if err != nil {
		t.Fatalf("test: %v", err)
	}
	if testResult.Status != "dispatched" || testResult.Value != sample.CodeOn {
		t.Fatalf("unexpected test result: %+v", testResult)
	}
	calls := transmitter.calls()
	if len(calls) != 1 || calls[0].Value != sample.CodeOn || calls[0].Repeat != 5 {
		t.Fatalf("unexpected test publish: %+v", calls)
	}

	confirmation, err := service.ConfirmLearning(
		ctx, session.ID, contract.ConfirmLearningRequest{
			Name:     "  pump  ",
			Location: "  pond  ",
		},
	)
	if err != nil {
		t.Fatalf("confirm: %v", err)
	}
	if confirmation.Device.Name != "pump" || confirmation.Device.Location != "pond" {
		t.Fatalf("name/location not trimmed: %+v", confirmation.Device)
	}
	if confirmation.Device.CodeOn != sample.CodeOn || confirmation.Device.CodeOff != sample.CodeOff {
		t.Fatalf("unexpected codes: %+v", confirmation.Device)
	}
	if confirmation.Device.RF.Repeat != 5 {
		t.Fatalf("repeat = %d, want 5", confirmation.Device.RF.Repeat)
	}
	if confirmation.Session.State != contract.LearningCompleted {
		t.Fatalf("state = %s, want completed", confirmation.Session.State)
	}

	stored, err := devices.List(ctx)
	if err != nil {
		t.Fatalf("list devices: %v", err)
	}
	if len(stored) != 1 || stored[0].ID != confirmation.Device.ID {
		t.Fatalf("unexpected stored devices: %+v", stored)
	}
}

func TestLearningExpiresWithoutSignal(t *testing.T) {
	service, _, _, _, now := newTestLearningService(t)
	session, err := service.Start(contract.StartLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	*now += collectionWindowMs + 1
	service.Tick()

	current, err := service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if current.State != contract.LearningExpired || current.FailureReason == nil ||
		*current.FailureReason != "no_signal" {
		t.Fatalf("unexpected expired session: %+v", current)
	}
}

func TestLearningExpiresInsufficientHits(t *testing.T) {
	service, _, _, _, now := newTestLearningService(t)
	session, err := service.Start(contract.StartLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	press(t, service, now, sampleDevice().CodeOn, 1)
	*now += collectionWindowMs + 1
	service.Tick()

	current, err := service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if current.State != contract.LearningExpired || current.FailureReason == nil ||
		*current.FailureReason != "insufficient_hits" {
		t.Fatalf("unexpected expired session: %+v", current)
	}
}

func TestLearningBusy(t *testing.T) {
	service, _, _, _, _ := newTestLearningService(t)
	if _, err := service.Start(contract.StartLearningRequest{}); err != nil {
		t.Fatalf("first start: %v", err)
	}
	_, err := service.Start(contract.StartLearningRequest{})
	apiErr := requireAPIError(t, err, 409, "learning_busy")
	if apiErr.Details["activeSessionId"] == "" {
		t.Fatal("learning_busy must carry the active session id")
	}
}

func TestLearningGatewayOfflineFailsCollection(t *testing.T) {
	service, _, gateway, _, now := newTestLearningService(t)
	session, err := service.Start(contract.StartLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	gateway.SetGateway(contract.GatewayOffline, nil)
	service.OnGatewayOffline(*now)

	current, err := service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if current.State != contract.LearningFailed || current.FailureReason == nil ||
		*current.FailureReason != "gateway_offline" {
		t.Fatalf("unexpected failed session: %+v", current)
	}
}

func TestLearningRejectsOwnedCode(t *testing.T) {
	service, devices, _, _, now := newTestLearningService(t)
	ctx := context.Background()
	sample := sampleDevice()
	if err := devices.Insert(ctx, sample); err != nil {
		t.Fatalf("insert device: %v", err)
	}
	session, err := service.Start(contract.StartLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	press(t, service, now, sample.CodeOn, 3)
	current, err := service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	_, err = service.AcceptCandidate(ctx, session.ID, current.Candidates[0].ID)
	apiErr := requireAPIError(t, err, 409, "code_already_registered")
	if apiErr.Details["deviceId"] != sample.ID {
		t.Fatalf("details = %+v", apiErr.Details)
	}
}

func TestLearningConfirmRejectsAddressMismatch(t *testing.T) {
	service, _, _, _, now := newTestLearningService(t)
	ctx := context.Background()
	session, err := service.Start(contract.StartLearningRequest{})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	on := int64(1714739)
	off := int64(0x500000)
	press(t, service, now, on, 3)
	current, err := service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	current, err = service.AcceptCandidate(ctx, session.ID, current.Candidates[0].ID)
	if err != nil {
		t.Fatalf("accept on: %v", err)
	}
	press(t, service, now, off, 3)
	current, err = service.GetLearning(session.ID)
	if err != nil {
		t.Fatalf("get off: %v", err)
	}
	offCandidate := current.Candidates[len(current.Candidates)-1]
	current, err = service.AcceptCandidate(ctx, session.ID, offCandidate.ID)
	if err != nil {
		t.Fatalf("accept off: %v", err)
	}
	_, err = service.ConfirmLearning(ctx, session.ID, contract.ConfirmLearningRequest{
		Name:     "pump",
		Location: "pond",
	})
	apiErr := requireAPIError(t, err, 422, "code_pair_invalid")
	if apiErr.Details["reason"] != "address_mismatch" {
		t.Fatalf("details = %+v", apiErr.Details)
	}
}
