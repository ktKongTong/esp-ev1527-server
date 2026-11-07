package service

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"

	"gorm.io/gorm"

	"esp32controlserver/internal/apierror"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/logging"
	"esp32controlserver/internal/repository"
)

const (
	sessionTTLMs        = 10 * 60 * 1000
	collectionWindowMs  = 60 * 1000
	candidateWindowMs   = 30 * 1000
	readyWindowMs       = 5 * 60 * 1000
	terminalRetentionMs = 5 * 60 * 1000
	requiredHits        = 3
	pressGapMs          = 600
	maxCandidates       = 32
)

func isTerminalLearningState(state string) bool {
	switch state {
	case contract.LearningCompleted, contract.LearningCancelled,
		contract.LearningExpired, contract.LearningFailed:
		return true
	default:
		return false
	}
}

// learningRecord adds the server-only lifecycle fields that are not part of
// the wire contract.
type learningRecord struct {
	session           contract.LearningSession
	active            bool
	terminalAtEpochMs *int64
	deviceID          *string
}

type LearningService struct {
	mu          sync.Mutex
	db          *gorm.DB
	devices     *repository.DeviceRepository
	gateway     *GatewayStatusStore
	transmitter RFTransmitter
	sessions    map[string]*learningRecord
	now         func() int64
	newID       func(string) string
}

func NewLearningService(
	db *gorm.DB,
	devices *repository.DeviceRepository,
	gateway *GatewayStatusStore,
	transmitter RFTransmitter,
	opts ...Option,
) *LearningService {
	resolved := resolveOptions(opts)
	return &LearningService{
		db:          db,
		devices:     devices,
		gateway:     gateway,
		transmitter: transmitter,
		sessions:    make(map[string]*learningRecord),
		now:         resolved.now,
		newID:       resolved.newID,
	}
}

func (s *LearningService) Start(
	request contract.StartLearningRequest,
) (contract.LearningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeLocked(now)

	status := s.gateway.Snapshot()
	if status.State != contract.GatewayOnline {
		logging.Warnf(
			"[learning] start rejected: gateway=%s receiver=%s",
			status.State, status.Receiver.State,
		)
		return contract.LearningSession{}, apierror.Unavailable(
			"gateway_unavailable",
			"Gateway is not online",
		)
	}
	if status.Receiver.State == contract.ReceiverUnavailable {
		logging.Warnf("[learning] start rejected: receiver unavailable")
		return contract.LearningSession{}, apierror.Unavailable(
			"receiver_unavailable",
			"The 315MHz receiver is unavailable",
		)
	}
	if active := s.activeLocked(); active != nil {
		logging.Warnf(
			"[learning] start rejected: session %s already active (%s)",
			active.session.ID, active.session.State,
		)
		return contract.LearningSession{}, apierror.NewDetails(
			http.StatusConflict,
			"learning_busy",
			"A learning session is already active",
			map[string]string{"activeSessionId": active.session.ID},
		)
	}
	if request.NameHint != nil && utf8.RuneCountInString(*request.NameHint) > 64 {
		return contract.LearningSession{}, apierror.InvalidRequest("nameHint is too long")
	}
	if request.LocationHint != nil && utf8.RuneCountInString(*request.LocationHint) > 64 {
		return contract.LearningSession{}, apierror.InvalidRequest("locationHint is too long")
	}

	session := contract.LearningSession{
		ID:                         s.newID("ls"),
		State:                      contract.LearningCollectingOn,
		Revision:                   1,
		CreatedAtEpochMs:           now,
		SessionExpiresAtEpochMs:    now + sessionTTLMs,
		CollectionExpiresAtEpochMs: int64Ptr(now + collectionWindowMs),
		Candidates:                 []contract.LearningCandidate{},
		RequiredHits:               requiredHits,
	}
	s.sessions[session.ID] = &learningRecord{session: session, active: true}
	logging.Infof(
		"[learning] session %s started: collect window %ds",
		session.ID, collectionWindowMs/1000,
	)
	return session, nil
}

func (s *LearningService) GetLearning(
	sessionID string,
) (contract.LearningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.purgeLocked(s.now())
	record, ok := s.sessions[sessionID]
	if !ok {
		return contract.LearningSession{}, apierror.LearningNotFound()
	}
	return record.session, nil
}

func (s *LearningService) AcceptCandidate(
	ctx context.Context,
	sessionID, candidateID string,
) (contract.LearningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	record, err := s.activeRecordLocked(sessionID)
	if err != nil {
		return contract.LearningSession{}, err
	}
	session := record.session
	index := -1
	for i, candidate := range session.Candidates {
		if candidate.ID == candidateID {
			index = i
			break
		}
	}
	if index < 0 {
		return contract.LearningSession{}, apierror.New(
			http.StatusNotFound,
			"candidate_not_found",
			"Candidate not found",
		)
	}
	candidate := session.Candidates[index]

	alreadyAccepted := candidate.Accepted &&
		((session.AcceptedOnCandidateID != nil && *session.AcceptedOnCandidateID == candidate.ID) ||
			(session.AcceptedOffCandidateID != nil && *session.AcceptedOffCandidateID == candidate.ID))
	if alreadyAccepted {
		logging.Debugf("[learning] accept %s ignored: already accepted", candidate.ID)
		return session, nil
	}

	state := session.State
	if state != contract.LearningCandidateOnReady && state != contract.LearningCandidateOffReady {
		logging.Warnf(
			"[learning] accept %s rejected: session %s state=%s",
			candidate.ID, session.ID, state,
		)
		return contract.LearningSession{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"The session is not waiting for candidate confirmation",
		)
	}
	currentSlot := contract.LearningSlotTurnOn
	if state == contract.LearningCandidateOffReady {
		currentSlot = contract.LearningSlotTurnOff
	}
	if candidate.Slot != currentSlot || !candidate.Qualified || candidate.Accepted {
		logging.Warnf(
			"[learning] accept %s rejected: slot=%s qualified=%t accepted=%t currentSlot=%s",
			candidate.ID, candidate.Slot, candidate.Qualified, candidate.Accepted, currentSlot,
		)
		return contract.LearningSession{}, apierror.New(
			http.StatusConflict,
			"candidate_not_ready",
			"Candidate cannot be accepted in the current state",
		)
	}

	owner, err := s.devices.FindOwner(ctx, candidate.Length, candidate.Value)
	if err != nil {
		return contract.LearningSession{}, err
	}
	if owner != "" {
		logging.Warnf(
			"[learning] accept %s rejected: code %d owned by %s",
			candidate.ID, candidate.Value, owner,
		)
		return contract.LearningSession{}, apierror.NewDetails(
			http.StatusConflict,
			"code_already_registered",
			"RF code is already owned by another device",
			map[string]string{
				"deviceId": owner,
				"value":    int64String(candidate.Value),
			},
		)
	}
	if currentSlot == contract.LearningSlotTurnOn {
		status := s.gateway.Snapshot()
		if status.State != contract.GatewayOnline {
			logging.Warnf(
				"[learning] accept %s rejected: gateway=%s",
				candidate.ID, status.State,
			)
			return contract.LearningSession{}, apierror.Unavailable(
				"gateway_unavailable",
				"Gateway is not online",
			)
		}
		if status.Receiver.State == contract.ReceiverUnavailable {
			logging.Warnf("[learning] accept %s rejected: receiver unavailable", candidate.ID)
			return contract.LearningSession{}, apierror.Unavailable(
				"receiver_unavailable",
				"The 315MHz receiver is unavailable",
			)
		}
	}

	acceptedCandidate := candidate
	acceptedCandidate.Accepted = true
	remaining := make([]contract.LearningCandidate, 0, len(session.Candidates))
	for _, value := range session.Candidates {
		if value.ID == candidate.ID {
			remaining = append(remaining, acceptedCandidate)
		} else if value.Accepted {
			remaining = append(remaining, value)
		}
	}

	updated := session
	updated.Revision = session.Revision + 1
	updated.Candidates = remaining
	updated.CollectionExpiresAtEpochMs = nil
	updated.CandidateExpiresAtEpochMs = nil
	if currentSlot == contract.LearningSlotTurnOn {
		updated.State = contract.LearningCollectingOff
		updated.CollectionExpiresAtEpochMs = int64Ptr(now + collectionWindowMs)
		updated.AcceptedOnCandidateID = stringPtr(candidate.ID)
	} else {
		updated.State = contract.LearningReady
		updated.SessionExpiresAtEpochMs = now + readyWindowMs
		updated.AcceptedOffCandidateID = stringPtr(candidate.ID)
	}

	record.session = updated
	logging.Infof(
		"[learning] session %s accepted %s slot=%s value=%d -> %s",
		sessionID, candidate.ID, currentSlot, candidate.Value, updated.State,
	)
	return updated, nil
}

func (s *LearningService) Rescan(
	ctx context.Context,
	sessionID string,
) (contract.LearningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	record, err := s.activeRecordLocked(sessionID)
	if err != nil {
		return contract.LearningSession{}, err
	}
	session := record.session
	var slot string
	switch session.State {
	case contract.LearningCollectingOn, contract.LearningCandidateOnReady:
		slot = contract.LearningSlotTurnOn
	case contract.LearningCollectingOff, contract.LearningCandidateOffReady:
		slot = contract.LearningSlotTurnOff
	default:
		logging.Warnf("[learning] rescan %s rejected: state=%s", session.ID, session.State)
		return contract.LearningSession{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"The session cannot be rescanned in its current state",
		)
	}
	status := s.gateway.Snapshot()
	if status.State != contract.GatewayOnline {
		logging.Warnf("[learning] rescan %s rejected: gateway=%s", session.ID, status.State)
		return contract.LearningSession{}, apierror.Unavailable(
			"gateway_unavailable",
			"Gateway is not online",
		)
	}
	if status.Receiver.State == contract.ReceiverUnavailable {
		logging.Warnf("[learning] rescan %s rejected: receiver unavailable", session.ID)
		return contract.LearningSession{}, apierror.Unavailable(
			"receiver_unavailable",
			"The 315MHz receiver is unavailable",
		)
	}

	candidates := make([]contract.LearningCandidate, 0, len(session.Candidates))
	for _, candidate := range session.Candidates {
		if candidate.Slot != slot || candidate.Accepted {
			candidates = append(candidates, candidate)
		}
	}
	updated := session
	if slot == contract.LearningSlotTurnOn {
		updated.State = contract.LearningCollectingOn
	} else {
		updated.State = contract.LearningCollectingOff
	}
	updated.Revision = session.Revision + 1
	updated.CollectionExpiresAtEpochMs = int64Ptr(now + collectionWindowMs)
	updated.CandidateExpiresAtEpochMs = nil
	updated.Candidates = candidates

	record.session = updated
	logging.Infof(
		"[learning] session %s rescanned: slot=%s -> %s",
		sessionID, slot, updated.State,
	)
	return updated, nil
}

func (s *LearningService) TestLearning(
	ctx context.Context,
	sessionID, slot string,
) (contract.LearningTestResult, error) {
	if slot != contract.LearningSlotTurnOn && slot != contract.LearningSlotTurnOff {
		return contract.LearningTestResult{}, apierror.InvalidRequest(
			"slot must be turn_on or turn_off",
		)
	}
	testID := s.newID("test")

	s.mu.Lock()
	record, err := s.activeRecordLocked(sessionID)
	if err != nil {
		s.mu.Unlock()
		return contract.LearningTestResult{}, err
	}
	session := record.session
	if session.State != contract.LearningReady {
		logging.Warnf("[learning] test %s rejected: state=%s", session.ID, session.State)
		s.mu.Unlock()
		return contract.LearningTestResult{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"The session is not ready for testing",
		)
	}
	if !s.gateway.IsOnline() {
		logging.Warnf("[learning] test %s rejected: gateway offline", session.ID)
		s.mu.Unlock()
		return contract.LearningTestResult{}, apierror.Unavailable(
			"gateway_unavailable",
			"Gateway is not online",
		)
	}
	acceptedID := session.AcceptedOnCandidateID
	if slot == contract.LearningSlotTurnOff {
		acceptedID = session.AcceptedOffCandidateID
	}
	candidate := findAcceptedCandidate(session, acceptedID)
	if candidate == nil {
		logging.Warnf(
			"[learning] test %s rejected: no accepted candidate for slot=%s",
			session.ID, slot,
		)
		s.mu.Unlock()
		return contract.LearningTestResult{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"The requested slot has no accepted candidate",
		)
	}
	candidateCopy := *candidate
	s.mu.Unlock()

	dispatchedAt := s.now()
	if err := s.transmitter.Send(ctx, contract.RFPayload{
		Value:    candidateCopy.Value,
		Protocol: candidateCopy.Protocol,
		Length:   candidateCopy.Length,
		Repeat:   5,
	}); err != nil {
		logging.Errorf("[learning] test dispatch failed for session %s: %v", sessionID, err)
		return contract.LearningTestResult{}, apierror.Unavailable(
			"gateway_unavailable",
			"Failed to publish test code",
		)
	}

	logging.Infof(
		"[learning] session %s test dispatched slot=%s value=%d",
		sessionID, slot, candidateCopy.Value,
	)
	return contract.LearningTestResult{
		TestID:              testID,
		SessionID:           sessionID,
		Slot:                slot,
		Value:               candidateCopy.Value,
		DispatchedAtEpochMs: dispatchedAt,
		Status:              "dispatched",
	}, nil
}

func (s *LearningService) ConfirmLearning(
	ctx context.Context,
	sessionID string,
	request contract.ConfirmLearningRequest,
) (contract.LearningConfirmation, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	existing, ok := s.sessions[sessionID]
	if !ok {
		return contract.LearningConfirmation{}, apierror.LearningNotFound()
	}

	if existing.session.State == contract.LearningCompleted {
		if existing.deviceID == nil {
			return contract.LearningConfirmation{}, apierror.New(
				http.StatusConflict,
				"learning_not_ready",
				"Learning session is already completed",
			)
		}
		found, err := s.devices.Find(ctx, *existing.deviceID)
		if err != nil {
			return contract.LearningConfirmation{}, err
		}
		if found != nil {
			return contract.LearningConfirmation{
				Device:  *found,
				Session: existing.session,
			}, nil
		}
		tombstone, err := s.devices.FindTombstone(ctx, *existing.deviceID)
		if err != nil {
			return contract.LearningConfirmation{}, err
		}
		if tombstone != nil {
			return contract.LearningConfirmation{}, apierror.DeviceRemoved(
				tombstone.RemovedAtEpochMs,
			)
		}
		return contract.LearningConfirmation{}, apierror.New(
			http.StatusGone,
			"device_removed",
			"Device was removed",
		)
	}
	if !existing.active {
		return contract.LearningConfirmation{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"Learning session is no longer active",
		)
	}

	session := existing.session
	if session.State != contract.LearningReady {
		logging.Warnf("[learning] confirm %s rejected: state=%s", session.ID, session.State)
		return contract.LearningConfirmation{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"The session is not ready to save",
		)
	}

	name := strings.TrimSpace(request.Name)
	location := strings.TrimSpace(request.Location)
	if utf8.RuneCountInString(name) == 0 || utf8.RuneCountInString(name) > 64 ||
		utf8.RuneCountInString(location) == 0 || utf8.RuneCountInString(location) > 64 {
		logging.Warnf("[learning] confirm %s rejected: invalid name or location", session.ID)
		return contract.LearningConfirmation{}, apierror.InvalidRequest(
			"name and location must be 1-64 characters",
		)
	}

	on := findAcceptedCandidateForSlot(
		session,
		session.AcceptedOnCandidateID,
		contract.LearningSlotTurnOn,
	)
	if on == nil {
		return contract.LearningConfirmation{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"Accepted ON candidate is missing",
		)
	}
	off := findAcceptedCandidateForSlot(
		session,
		session.AcceptedOffCandidateID,
		contract.LearningSlotTurnOff,
	)
	if off == nil {
		return contract.LearningConfirmation{}, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"Accepted OFF candidate is missing",
		)
	}
	if on.Value == off.Value {
		return contract.LearningConfirmation{}, apierror.CodePairInvalid("same_code")
	}
	if !ev1527Valid(on.Value) || !ev1527Valid(off.Value) {
		return contract.LearningConfirmation{}, apierror.CodePairInvalid("code_out_of_range")
	}
	if !ev1527SameAddress(on.Value, off.Value) {
		return contract.LearningConfirmation{}, apierror.CodePairInvalid("address_mismatch")
	}

	var created contract.Device
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		devices := s.devices.WithDB(tx)
		for _, code := range []contract.LearningCandidate{*on, *off} {
			owner, err := devices.FindOwner(ctx, code.Length, code.Value)
			if err != nil {
				return err
			}
			if owner != "" {
				return apierror.NewDetails(
					http.StatusConflict,
					"code_already_registered",
					"RF code is already owned by another device",
					map[string]string{
						"deviceId": owner,
						"value":    int64String(code.Value),
					},
				)
			}
		}
		created = contract.Device{
			ID:               s.newID("dev"),
			Name:             name,
			Location:         location,
			CodeOn:           on.Value,
			CodeOff:          off.Value,
			RF:               contract.RFParameters{Protocol: on.Protocol, Length: on.Length, Repeat: 5},
			CreatedAtEpochMs: now,
		}
		return devices.Insert(ctx, created)
	})
	if err != nil {
		return contract.LearningConfirmation{}, err
	}

	completed := session
	completed.State = contract.LearningCompleted
	completed.Revision = session.Revision + 1
	completed.CollectionExpiresAtEpochMs = nil
	completed.CandidateExpiresAtEpochMs = nil

	s.sessions[sessionID] = &learningRecord{
		session:           completed,
		active:            false,
		terminalAtEpochMs: int64Ptr(now),
		deviceID:          stringPtr(created.ID),
	}
	logging.Infof(
		"[learning] session %s completed: device %s on=%d off=%d",
		sessionID, created.ID, on.Value, off.Value,
	)
	return contract.LearningConfirmation{Device: created, Session: completed}, nil
}

func (s *LearningService) CancelLearning(sessionID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.sessions[sessionID]
	if !ok {
		logging.Debugf("[learning] cancel %s ignored: session not found", sessionID)
		return
	}
	if isTerminalLearningState(record.session.State) {
		logging.Debugf(
			"[learning] cancel %s ignored: state=%s",
			sessionID, record.session.State,
		)
		return
	}

	cancelled := record.session
	cancelled.State = contract.LearningCancelled
	cancelled.Revision = record.session.Revision + 1
	cancelled.CollectionExpiresAtEpochMs = nil
	cancelled.CandidateExpiresAtEpochMs = nil
	now := s.now()
	record.session = cancelled
	record.active = false
	record.terminalAtEpochMs = int64Ptr(now)
	logging.Infof("[learning] session %s cancelled", sessionID)
}

// OnGatewayOffline fails a session that is still collecting. Candidate
// confirmation and ready states keep their evidence and their own timers.
func (s *LearningService) OnGatewayOffline(now int64) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record := s.activeLocked()
	if record == nil {
		return
	}
	state := record.session.State
	if state == contract.LearningCollectingOn || state == contract.LearningCollectingOff {
		s.failLocked(record, "gateway_offline", now)
	} else {
		logging.Infof(
			"[learning] gateway offline during session %s state=%s, session kept",
			record.session.ID, state,
		)
	}
}

func (s *LearningService) OnCapture(
	ctx context.Context,
	capture rfCapture,
	now int64,
) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record := s.activeLocked()
	if record == nil {
		logging.Debugf("[learning] capture %d ignored: no active session", capture.Value)
		return
	}
	session := record.session
	state := session.State
	if state != contract.LearningCollectingOn && state != contract.LearningCollectingOff {
		logging.Debugf(
			"[learning] capture %d ignored: session %s state=%s (slot closed)",
			capture.Value, session.ID, state,
		)
		return
	}
	if session.CollectionExpiresAtEpochMs == nil {
		logging.Debugf(
			"[learning] capture %d ignored: session %s has no collection window",
			capture.Value, session.ID,
		)
		return
	}
	if now >= *session.CollectionExpiresAtEpochMs {
		logging.Debugf(
			"[learning] capture %d ignored: collection window ended %dms ago",
			capture.Value, now-*session.CollectionExpiresAtEpochMs,
		)
		return
	}
	if !ev1527Valid(capture.Value) {
		logging.Debugf(
			"[learning] capture %d ignored: value out of EV1527 range",
			capture.Value,
		)
		return
	}

	slot := contract.LearningSlotTurnOn
	if state == contract.LearningCollectingOff {
		slot = contract.LearningSlotTurnOff
	}

	existingIndex := -1
	for i, candidate := range session.Candidates {
		if candidate.Slot == slot && candidate.Value == capture.Value {
			existingIndex = i
			break
		}
	}
	if existingIndex < 0 && len(session.Candidates) >= maxCandidates {
		logging.Warnf(
			"[learning] capture %d dropped: candidate limit %d reached",
			capture.Value, maxCandidates,
		)
		return
	}

	owner, err := s.devices.FindOwner(ctx, capture.Length, capture.Value)
	if err != nil {
		logging.Errorf("[learning] capture %d owner lookup failed: %v", capture.Value, err)
		return
	}
	var conflict *string
	if owner != "" {
		conflict = stringPtr(owner)
	}

	var updatedCandidate contract.LearningCandidate
	if existingIndex < 0 {
		updatedCandidate = contract.LearningCandidate{
			ID:                 s.newID("cand"),
			Slot:               slot,
			Value:              capture.Value,
			Protocol:           capture.Protocol,
			Length:             capture.Length,
			DelayMs:            capture.DelayMs,
			HitCount:           1,
			FrameCount:         1,
			Qualified:          requiredHits <= 1,
			FirstSeenAtEpochMs: now,
			LastSeenAtEpochMs:  now,
			ConflictDeviceID:   conflict,
		}
	} else {
		existing := session.Candidates[existingIndex]
		newHits := existing.HitCount
		if now-existing.LastSeenAtEpochMs >= pressGapMs {
			newHits++
		}
		updatedCandidate = existing
		updatedCandidate.DelayMs = capture.DelayMs
		updatedCandidate.HitCount = newHits
		updatedCandidate.FrameCount = existing.FrameCount + 1
		updatedCandidate.Qualified = newHits >= requiredHits
		updatedCandidate.LastSeenAtEpochMs = now
		updatedCandidate.ConflictDeviceID = conflict
	}

	conflictLabel := ""
	if conflict != nil {
		conflictLabel = " conflict=" + *conflict
	}
	logging.Debugf(
		"[learning] capture %d slot=%s hits=%d/%d frames=%d%s",
		capture.Value, slot, updatedCandidate.HitCount, requiredHits,
		updatedCandidate.FrameCount, conflictLabel,
	)

	candidates := append([]contract.LearningCandidate(nil), session.Candidates...)
	if existingIndex < 0 {
		candidates = append(candidates, updatedCandidate)
	} else {
		candidates[existingIndex] = updatedCandidate
	}

	qualifiedNow := false
	for i, candidate := range candidates {
		if candidate.Slot != slot {
			continue
		}
		if candidate.HitCount >= requiredHits {
			qualifiedNow = true
			candidate.Qualified = true
			candidates[i] = candidate
		}
	}

	updated := session
	if qualifiedNow {
		if slot == contract.LearningSlotTurnOn {
			updated.State = contract.LearningCandidateOnReady
		} else {
			updated.State = contract.LearningCandidateOffReady
		}
	}
	updated.Revision = session.Revision + 1
	updated.Candidates = candidates
	if qualifiedNow {
		updated.CollectionExpiresAtEpochMs = nil
		updated.CandidateExpiresAtEpochMs = int64Ptr(now + candidateWindowMs)
	} else {
		updated.CandidateExpiresAtEpochMs = nil
	}
	record.session = updated
	if qualifiedNow {
		logging.Infof(
			"[learning] session %s slot=%s qualified value=%d -> %s",
			session.ID, slot, capture.Value, updated.State,
		)
	}
}

func (s *LearningService) Tick() {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeLocked(now)
	record := s.activeLocked()
	if record == nil {
		return
	}
	active := record.session
	switch active.State {
	case contract.LearningCollectingOn, contract.LearningCollectingOff:
		if active.CollectionExpiresAtEpochMs != nil && now >= *active.CollectionExpiresAtEpochMs {
			slot := contract.LearningSlotTurnOn
			if active.State == contract.LearningCollectingOff {
				slot = contract.LearningSlotTurnOff
			}
			hadSignal := false
			for _, candidate := range active.Candidates {
				if candidate.Slot == slot {
					hadSignal = true
					break
				}
			}
			if hadSignal {
				s.expireLocked(record, "insufficient_hits", now)
			} else {
				s.expireLocked(record, "no_signal", now)
			}
			return
		}
		if now >= active.SessionExpiresAtEpochMs {
			s.expireLocked(record, "session_timeout", now)
		}
	case contract.LearningCandidateOnReady, contract.LearningCandidateOffReady:
		if active.CandidateExpiresAtEpochMs != nil && now >= *active.CandidateExpiresAtEpochMs {
			s.expireLocked(record, "candidate_timeout", now)
		} else if now >= active.SessionExpiresAtEpochMs {
			s.expireLocked(record, "session_timeout", now)
		}
	case contract.LearningReady:
		if now >= active.SessionExpiresAtEpochMs {
			s.expireLocked(record, "ready_timeout", now)
		}
	}
}

func (s *LearningService) activeLocked() *learningRecord {
	for _, record := range s.sessions {
		if record.active {
			return record
		}
	}
	return nil
}

func (s *LearningService) activeRecordLocked(
	sessionID string,
) (*learningRecord, error) {
	s.purgeLocked(s.now())
	record, ok := s.sessions[sessionID]
	if !ok {
		return nil, apierror.LearningNotFound()
	}
	if isTerminalLearningState(record.session.State) || !record.active {
		return nil, apierror.New(
			http.StatusConflict,
			"learning_not_ready",
			"Learning session is no longer active",
		)
	}
	return record, nil
}

func (s *LearningService) failLocked(record *learningRecord, reason string, now int64) {
	failed := record.session
	failed.State = contract.LearningFailed
	failed.Revision = record.session.Revision + 1
	failed.CollectionExpiresAtEpochMs = nil
	failed.CandidateExpiresAtEpochMs = nil
	failed.FailureReason = stringPtr(reason)

	logging.Warnf(
		"[learning] session %s failed: %s (was %s)",
		record.session.ID, reason, record.session.State,
	)
	record.session = failed
	record.active = false
	record.terminalAtEpochMs = int64Ptr(now)
}

func (s *LearningService) expireLocked(record *learningRecord, reason string, now int64) {
	expired := record.session
	expired.State = contract.LearningExpired
	expired.Revision = record.session.Revision + 1
	expired.CollectionExpiresAtEpochMs = nil
	expired.CandidateExpiresAtEpochMs = nil
	expired.FailureReason = stringPtr(reason)

	logging.Infof(
		"[learning] session %s expired: %s (was %s)",
		record.session.ID, reason, record.session.State,
	)
	record.session = expired
	record.active = false
	record.terminalAtEpochMs = int64Ptr(now)
}

func (s *LearningService) purgeLocked(now int64) {
	cutoff := now - terminalRetentionMs
	for id, record := range s.sessions {
		if record.active || record.terminalAtEpochMs == nil {
			continue
		}
		if *record.terminalAtEpochMs < cutoff {
			delete(s.sessions, id)
		}
	}
}

func findAcceptedCandidate(
	session contract.LearningSession,
	id *string,
) *contract.LearningCandidate {
	if id == nil {
		return nil
	}
	for i := range session.Candidates {
		candidate := &session.Candidates[i]
		if candidate.ID == *id && candidate.Accepted {
			return candidate
		}
	}
	return nil
}

func findAcceptedCandidateForSlot(
	session contract.LearningSession,
	id *string,
	slot string,
) *contract.LearningCandidate {
	candidate := findAcceptedCandidate(session, id)
	if candidate == nil || candidate.Slot != slot {
		return nil
	}
	return candidate
}

func int64Ptr(value int64) *int64 { return &value }

func stringPtr(value string) *string { return &value }

func int64String(value int64) string {
	return strconv.FormatInt(value, 10)
}
