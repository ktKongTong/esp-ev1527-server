package main

import (
	"context"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"

	"gorm.io/gorm"
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
	case learningCompleted, learningCancelled, learningExpired, learningFailed:
		return true
	default:
		return false
	}
}

// learningRecord adds the server-only lifecycle fields that are not part of
// the wire contract.
type learningRecord struct {
	session           learningSession
	active            bool
	terminalAtEpochMs *int64
	deviceID          *string
}

type learningService struct {
	mu          sync.Mutex
	db          *gorm.DB
	devices     *deviceRepository
	gateway     *gatewayStatusStore
	transmitter rfTransmitter
	sessions    map[string]*learningRecord
	now         func() int64
	newID       func(string) string
}

func newLearningService(
	db *gorm.DB,
	devices *deviceRepository,
	gateway *gatewayStatusStore,
	transmitter rfTransmitter,
) *learningService {
	return &learningService{
		db:          db,
		devices:     devices,
		gateway:     gateway,
		transmitter: transmitter,
		sessions:    make(map[string]*learningRecord),
		now:         nowEpochMs,
		newID:       newID,
	}
}

func (s *learningService) start(request startLearningRequest) (learningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeLocked(now)

	status := s.gateway.snapshot()
	if status.State != gatewayOnline {
		warnf(
			"[learning] start rejected: gateway=%s receiver=%s",
			status.State, status.Receiver.State,
		)
		return learningSession{}, unavailable("gateway_unavailable", "Gateway is not online")
	}
	if status.Receiver.State == receiverUnavailable {
		warnf("[learning] start rejected: receiver unavailable")
		return learningSession{}, unavailable(
			"receiver_unavailable",
			"The 315MHz receiver is unavailable",
		)
	}
	if active := s.activeLocked(); active != nil {
		warnf(
			"[learning] start rejected: session %s already active (%s)",
			active.session.ID, active.session.State,
		)
		return learningSession{}, newAPIErrorDetails(
			409,
			"learning_busy",
			"A learning session is already active",
			map[string]string{"activeSessionId": active.session.ID},
		)
	}
	if request.NameHint != nil && utf8.RuneCountInString(*request.NameHint) > 64 {
		return learningSession{}, invalidRequest("nameHint is too long")
	}
	if request.LocationHint != nil && utf8.RuneCountInString(*request.LocationHint) > 64 {
		return learningSession{}, invalidRequest("locationHint is too long")
	}

	session := learningSession{
		ID:                         s.newID("ls"),
		State:                      learningCollectingOn,
		Revision:                   1,
		CreatedAtEpochMs:           now,
		SessionExpiresAtEpochMs:    now + sessionTTLMs,
		CollectionExpiresAtEpochMs: int64Ptr(now + collectionWindowMs),
		Candidates:                 []learningCandidate{},
		RequiredHits:               requiredHits,
	}
	s.sessions[session.ID] = &learningRecord{session: session, active: true}
	infof(
		"[learning] session %s started: collect window %ds",
		session.ID, collectionWindowMs/1000,
	)
	return session, nil
}

func (s *learningService) getLearning(sessionID string) (learningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.purgeLocked(s.now())
	record, ok := s.sessions[sessionID]
	if !ok {
		return learningSession{}, learningNotFound()
	}
	return record.session, nil
}

func (s *learningService) acceptCandidate(
	ctx context.Context,
	sessionID, candidateID string,
) (learningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	record, err := s.activeRecordLocked(sessionID)
	if err != nil {
		return learningSession{}, err
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
		return learningSession{}, newAPIError(
			404,
			"candidate_not_found",
			"Candidate not found",
		)
	}
	candidate := session.Candidates[index]

	alreadyAccepted := candidate.Accepted &&
		((session.AcceptedOnCandidateID != nil && *session.AcceptedOnCandidateID == candidate.ID) ||
			(session.AcceptedOffCandidateID != nil && *session.AcceptedOffCandidateID == candidate.ID))
	if alreadyAccepted {
		debugf("[learning] accept %s ignored: already accepted", candidate.ID)
		return session, nil
	}

	state := session.State
	if state != learningCandidateOnReady && state != learningCandidateOffReady {
		warnf(
			"[learning] accept %s rejected: session %s state=%s",
			candidate.ID, session.ID, state,
		)
		return learningSession{}, newAPIError(
			409,
			"learning_not_ready",
			"The session is not waiting for candidate confirmation",
		)
	}
	currentSlot := learningSlotTurnOn
	if state == learningCandidateOffReady {
		currentSlot = learningSlotTurnOff
	}
	if candidate.Slot != currentSlot || !candidate.Qualified || candidate.Accepted {
		warnf(
			"[learning] accept %s rejected: slot=%s qualified=%t accepted=%t currentSlot=%s",
			candidate.ID, candidate.Slot, candidate.Qualified, candidate.Accepted, currentSlot,
		)
		return learningSession{}, newAPIError(
			409,
			"candidate_not_ready",
			"Candidate cannot be accepted in the current state",
		)
	}

	owner, err := s.devices.findOwner(ctx, candidate.Length, candidate.Value)
	if err != nil {
		return learningSession{}, err
	}
	if owner != "" {
		warnf(
			"[learning] accept %s rejected: code %d owned by %s",
			candidate.ID, candidate.Value, owner,
		)
		return learningSession{}, newAPIErrorDetails(
			409,
			"code_already_registered",
			"RF code is already owned by another device",
			map[string]string{
				"deviceId": owner,
				"value":    int64String(candidate.Value),
			},
		)
	}
	if currentSlot == learningSlotTurnOn {
		status := s.gateway.snapshot()
		if status.State != gatewayOnline {
			warnf("[learning] accept %s rejected: gateway=%s", candidate.ID, status.State)
			return learningSession{}, unavailable("gateway_unavailable", "Gateway is not online")
		}
		if status.Receiver.State == receiverUnavailable {
			warnf("[learning] accept %s rejected: receiver unavailable", candidate.ID)
			return learningSession{}, unavailable(
				"receiver_unavailable",
				"The 315MHz receiver is unavailable",
			)
		}
	}

	acceptedCandidate := candidate
	acceptedCandidate.Accepted = true
	remaining := make([]learningCandidate, 0, len(session.Candidates))
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
	if currentSlot == learningSlotTurnOn {
		updated.State = learningCollectingOff
		updated.CollectionExpiresAtEpochMs = int64Ptr(now + collectionWindowMs)
		updated.AcceptedOnCandidateID = stringPtr(candidate.ID)
	} else {
		updated.State = learningReady
		updated.SessionExpiresAtEpochMs = now + readyWindowMs
		updated.AcceptedOffCandidateID = stringPtr(candidate.ID)
	}

	record.session = updated
	infof(
		"[learning] session %s accepted %s slot=%s value=%d -> %s",
		sessionID, candidate.ID, currentSlot, candidate.Value, updated.State,
	)
	return updated, nil
}

func (s *learningService) rescan(ctx context.Context, sessionID string) (learningSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	record, err := s.activeRecordLocked(sessionID)
	if err != nil {
		return learningSession{}, err
	}
	session := record.session
	var slot string
	switch session.State {
	case learningCollectingOn, learningCandidateOnReady:
		slot = learningSlotTurnOn
	case learningCollectingOff, learningCandidateOffReady:
		slot = learningSlotTurnOff
	default:
		warnf("[learning] rescan %s rejected: state=%s", session.ID, session.State)
		return learningSession{}, newAPIError(
			409,
			"learning_not_ready",
			"The session cannot be rescanned in its current state",
		)
	}
	status := s.gateway.snapshot()
	if status.State != gatewayOnline {
		warnf("[learning] rescan %s rejected: gateway=%s", session.ID, status.State)
		return learningSession{}, unavailable("gateway_unavailable", "Gateway is not online")
	}
	if status.Receiver.State == receiverUnavailable {
		warnf("[learning] rescan %s rejected: receiver unavailable", session.ID)
		return learningSession{}, unavailable(
			"receiver_unavailable",
			"The 315MHz receiver is unavailable",
		)
	}

	candidates := make([]learningCandidate, 0, len(session.Candidates))
	for _, candidate := range session.Candidates {
		if candidate.Slot != slot || candidate.Accepted {
			candidates = append(candidates, candidate)
		}
	}
	updated := session
	if slot == learningSlotTurnOn {
		updated.State = learningCollectingOn
	} else {
		updated.State = learningCollectingOff
	}
	updated.Revision = session.Revision + 1
	updated.CollectionExpiresAtEpochMs = int64Ptr(now + collectionWindowMs)
	updated.CandidateExpiresAtEpochMs = nil
	updated.Candidates = candidates

	record.session = updated
	infof("[learning] session %s rescanned: slot=%s -> %s", sessionID, slot, updated.State)
	return updated, nil
}

func (s *learningService) testLearning(
	ctx context.Context,
	sessionID, slot string,
) (learningTestResult, error) {
	if slot != learningSlotTurnOn && slot != learningSlotTurnOff {
		return learningTestResult{}, invalidRequest("slot must be turn_on or turn_off")
	}
	testID := s.newID("test")

	s.mu.Lock()
	record, err := s.activeRecordLocked(sessionID)
	if err != nil {
		s.mu.Unlock()
		return learningTestResult{}, err
	}
	session := record.session
	if session.State != learningReady {
		warnf("[learning] test %s rejected: state=%s", session.ID, session.State)
		s.mu.Unlock()
		return learningTestResult{}, newAPIError(
			409,
			"learning_not_ready",
			"The session is not ready for testing",
		)
	}
	if !s.gateway.isOnline() {
		warnf("[learning] test %s rejected: gateway offline", session.ID)
		s.mu.Unlock()
		return learningTestResult{}, unavailable("gateway_unavailable", "Gateway is not online")
	}
	acceptedID := session.AcceptedOnCandidateID
	if slot == learningSlotTurnOff {
		acceptedID = session.AcceptedOffCandidateID
	}
	candidate := findAcceptedCandidate(session, acceptedID)
	if candidate == nil {
		warnf(
			"[learning] test %s rejected: no accepted candidate for slot=%s",
			session.ID, slot,
		)
		s.mu.Unlock()
		return learningTestResult{}, newAPIError(
			409,
			"learning_not_ready",
			"The requested slot has no accepted candidate",
		)
	}
	candidateCopy := *candidate
	s.mu.Unlock()

	dispatchedAt := s.now()
	if err := s.transmitter.Send(ctx, rfPayload{
		Value:    candidateCopy.Value,
		Protocol: candidateCopy.Protocol,
		Length:   candidateCopy.Length,
		Repeat:   5,
	}); err != nil {
		errorf("[learning] test dispatch failed for session %s: %v", sessionID, err)
		return learningTestResult{}, unavailable("gateway_unavailable", "Failed to publish test code")
	}

	infof(
		"[learning] session %s test dispatched slot=%s value=%d",
		sessionID, slot, candidateCopy.Value,
	)
	return learningTestResult{
		TestID:              testID,
		SessionID:           sessionID,
		Slot:                slot,
		Value:               candidateCopy.Value,
		DispatchedAtEpochMs: dispatchedAt,
		Status:              "dispatched",
	}, nil
}

func (s *learningService) confirmLearning(
	ctx context.Context,
	sessionID string,
	request confirmLearningRequest,
) (learningConfirmation, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	existing, ok := s.sessions[sessionID]
	if !ok {
		return learningConfirmation{}, learningNotFound()
	}

	if existing.session.State == learningCompleted {
		if existing.deviceID == nil {
			return learningConfirmation{}, newAPIError(
				409,
				"learning_not_ready",
				"Learning session is already completed",
			)
		}
		found, err := s.devices.find(ctx, *existing.deviceID)
		if err != nil {
			return learningConfirmation{}, err
		}
		if found != nil {
			return learningConfirmation{Device: *found, Session: existing.session}, nil
		}
		tombstone, err := s.devices.findTombstone(ctx, *existing.deviceID)
		if err != nil {
			return learningConfirmation{}, err
		}
		if tombstone != nil {
			return learningConfirmation{}, deviceRemoved(tombstone.RemovedAtEpochMs)
		}
		return learningConfirmation{}, newAPIError(
			410,
			"device_removed",
			"Device was removed",
		)
	}
	if !existing.active {
		return learningConfirmation{}, newAPIError(
			409,
			"learning_not_ready",
			"Learning session is no longer active",
		)
	}

	session := existing.session
	if session.State != learningReady {
		warnf("[learning] confirm %s rejected: state=%s", session.ID, session.State)
		return learningConfirmation{}, newAPIError(
			409,
			"learning_not_ready",
			"The session is not ready to save",
		)
	}

	name := strings.TrimSpace(request.Name)
	location := strings.TrimSpace(request.Location)
	if utf8.RuneCountInString(name) == 0 || utf8.RuneCountInString(name) > 64 ||
		utf8.RuneCountInString(location) == 0 || utf8.RuneCountInString(location) > 64 {
		warnf("[learning] confirm %s rejected: invalid name or location", session.ID)
		return learningConfirmation{}, invalidRequest("name and location must be 1-64 characters")
	}

	on := findAcceptedCandidateForSlot(session, session.AcceptedOnCandidateID, learningSlotTurnOn)
	if on == nil {
		return learningConfirmation{}, newAPIError(
			409,
			"learning_not_ready",
			"Accepted ON candidate is missing",
		)
	}
	off := findAcceptedCandidateForSlot(session, session.AcceptedOffCandidateID, learningSlotTurnOff)
	if off == nil {
		return learningConfirmation{}, newAPIError(
			409,
			"learning_not_ready",
			"Accepted OFF candidate is missing",
		)
	}
	if on.Value == off.Value {
		return learningConfirmation{}, codePairInvalid("same_code")
	}
	if !ev1527Valid(on.Value) || !ev1527Valid(off.Value) {
		return learningConfirmation{}, codePairInvalid("code_out_of_range")
	}
	if !ev1527SameAddress(on.Value, off.Value) {
		return learningConfirmation{}, codePairInvalid("address_mismatch")
	}

	var created device
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		devices := s.devices.withDB(tx)
		for _, code := range []learningCandidate{*on, *off} {
			owner, err := devices.findOwner(ctx, code.Length, code.Value)
			if err != nil {
				return err
			}
			if owner != "" {
				return newAPIErrorDetails(
					409,
					"code_already_registered",
					"RF code is already owned by another device",
					map[string]string{
						"deviceId": owner,
						"value":    int64String(code.Value),
					},
				)
			}
		}
		created = device{
			ID:               s.newID("dev"),
			Name:             name,
			Location:         location,
			CodeOn:           on.Value,
			CodeOff:          off.Value,
			RF:               rfParameters{Protocol: on.Protocol, Length: on.Length, Repeat: 5},
			CreatedAtEpochMs: now,
		}
		return devices.insert(ctx, created)
	})
	if err != nil {
		return learningConfirmation{}, err
	}

	completed := session
	completed.State = learningCompleted
	completed.Revision = session.Revision + 1
	completed.CollectionExpiresAtEpochMs = nil
	completed.CandidateExpiresAtEpochMs = nil

	s.sessions[sessionID] = &learningRecord{
		session:           completed,
		active:            false,
		terminalAtEpochMs: int64Ptr(now),
		deviceID:          stringPtr(created.ID),
	}
	infof(
		"[learning] session %s completed: device %s on=%d off=%d",
		sessionID, created.ID, on.Value, off.Value,
	)
	return learningConfirmation{Device: created, Session: completed}, nil
}

func (s *learningService) cancelLearning(sessionID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record, ok := s.sessions[sessionID]
	if !ok {
		debugf("[learning] cancel %s ignored: session not found", sessionID)
		return
	}
	if isTerminalLearningState(record.session.State) {
		debugf("[learning] cancel %s ignored: state=%s", sessionID, record.session.State)
		return
	}

	cancelled := record.session
	cancelled.State = learningCancelled
	cancelled.Revision = record.session.Revision + 1
	cancelled.CollectionExpiresAtEpochMs = nil
	cancelled.CandidateExpiresAtEpochMs = nil
	now := s.now()
	record.session = cancelled
	record.active = false
	record.terminalAtEpochMs = int64Ptr(now)
	infof("[learning] session %s cancelled", sessionID)
}

// onGatewayOffline fails a session that is still collecting. Candidate
// confirmation and ready states keep their evidence and their own timers.
func (s *learningService) onGatewayOffline(now int64) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record := s.activeLocked()
	if record == nil {
		return
	}
	state := record.session.State
	if state == learningCollectingOn || state == learningCollectingOff {
		s.failLocked(record, "gateway_offline", now)
	} else {
		infof(
			"[learning] gateway offline during session %s state=%s, session kept",
			record.session.ID, state,
		)
	}
}

func (s *learningService) onCapture(ctx context.Context, capture rfCapture, now int64) {
	s.mu.Lock()
	defer s.mu.Unlock()

	record := s.activeLocked()
	if record == nil {
		debugf("[learning] capture %d ignored: no active session", capture.Value)
		return
	}
	session := record.session
	state := session.State
	if state != learningCollectingOn && state != learningCollectingOff {
		debugf(
			"[learning] capture %d ignored: session %s state=%s (slot closed)",
			capture.Value, session.ID, state,
		)
		return
	}
	if session.CollectionExpiresAtEpochMs == nil {
		debugf(
			"[learning] capture %d ignored: session %s has no collection window",
			capture.Value, session.ID,
		)
		return
	}
	if now >= *session.CollectionExpiresAtEpochMs {
		debugf(
			"[learning] capture %d ignored: collection window ended %dms ago",
			capture.Value, now-*session.CollectionExpiresAtEpochMs,
		)
		return
	}
	if !ev1527Valid(capture.Value) {
		debugf(
			"[learning] capture %d ignored: value out of EV1527 range",
			capture.Value,
		)
		return
	}

	slot := learningSlotTurnOn
	if state == learningCollectingOff {
		slot = learningSlotTurnOff
	}

	existingIndex := -1
	for i, candidate := range session.Candidates {
		if candidate.Slot == slot && candidate.Value == capture.Value {
			existingIndex = i
			break
		}
	}
	if existingIndex < 0 && len(session.Candidates) >= maxCandidates {
		warnf(
			"[learning] capture %d dropped: candidate limit %d reached",
			capture.Value, maxCandidates,
		)
		return
	}

	owner, err := s.devices.findOwner(ctx, capture.Length, capture.Value)
	if err != nil {
		errorf("[learning] capture %d owner lookup failed: %v", capture.Value, err)
		return
	}
	var conflict *string
	if owner != "" {
		conflict = stringPtr(owner)
	}

	var updatedCandidate learningCandidate
	if existingIndex < 0 {
		updatedCandidate = learningCandidate{
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
	debugf(
		"[learning] capture %d slot=%s hits=%d/%d frames=%d%s",
		capture.Value, slot, updatedCandidate.HitCount, requiredHits,
		updatedCandidate.FrameCount, conflictLabel,
	)

	candidates := append([]learningCandidate(nil), session.Candidates...)
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
		if slot == learningSlotTurnOn {
			updated.State = learningCandidateOnReady
		} else {
			updated.State = learningCandidateOffReady
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
		infof(
			"[learning] session %s slot=%s qualified value=%d -> %s",
			session.ID, slot, capture.Value, updated.State,
		)
	}
}

func (s *learningService) tick() {
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
	case learningCollectingOn, learningCollectingOff:
		if active.CollectionExpiresAtEpochMs != nil && now >= *active.CollectionExpiresAtEpochMs {
			slot := learningSlotTurnOn
			if active.State == learningCollectingOff {
				slot = learningSlotTurnOff
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
	case learningCandidateOnReady, learningCandidateOffReady:
		if active.CandidateExpiresAtEpochMs != nil && now >= *active.CandidateExpiresAtEpochMs {
			s.expireLocked(record, "candidate_timeout", now)
		} else if now >= active.SessionExpiresAtEpochMs {
			s.expireLocked(record, "session_timeout", now)
		}
	case learningReady:
		if now >= active.SessionExpiresAtEpochMs {
			s.expireLocked(record, "ready_timeout", now)
		}
	}
}

func (s *learningService) activeLocked() *learningRecord {
	for _, record := range s.sessions {
		if record.active {
			return record
		}
	}
	return nil
}

func (s *learningService) activeRecordLocked(sessionID string) (*learningRecord, error) {
	s.purgeLocked(s.now())
	record, ok := s.sessions[sessionID]
	if !ok {
		return nil, learningNotFound()
	}
	if isTerminalLearningState(record.session.State) || !record.active {
		return nil, newAPIError(
			409,
			"learning_not_ready",
			"Learning session is no longer active",
		)
	}
	return record, nil
}

func (s *learningService) failLocked(record *learningRecord, reason string, now int64) {
	failed := record.session
	failed.State = learningFailed
	failed.Revision = record.session.Revision + 1
	failed.CollectionExpiresAtEpochMs = nil
	failed.CandidateExpiresAtEpochMs = nil
	failed.FailureReason = stringPtr(reason)

	warnf(
		"[learning] session %s failed: %s (was %s)",
		record.session.ID, reason, record.session.State,
	)
	record.session = failed
	record.active = false
	record.terminalAtEpochMs = int64Ptr(now)
}

func (s *learningService) expireLocked(record *learningRecord, reason string, now int64) {
	expired := record.session
	expired.State = learningExpired
	expired.Revision = record.session.Revision + 1
	expired.CollectionExpiresAtEpochMs = nil
	expired.CandidateExpiresAtEpochMs = nil
	expired.FailureReason = stringPtr(reason)

	infof(
		"[learning] session %s expired: %s (was %s)",
		record.session.ID, reason, record.session.State,
	)
	record.session = expired
	record.active = false
	record.terminalAtEpochMs = int64Ptr(now)
}

func (s *learningService) purgeLocked(now int64) {
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

func findAcceptedCandidate(session learningSession, id *string) *learningCandidate {
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
	session learningSession,
	id *string,
	slot string,
) *learningCandidate {
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
