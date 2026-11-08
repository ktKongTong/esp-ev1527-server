package service

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/teambition/rrule-go"
	"gorm.io/gorm"

	"esp32controlserver/internal/apierror"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/logging"
	"esp32controlserver/internal/repository"
)

// ScheduleRegistrar keeps the in-memory timer set in sync with persisted
// schedules. The adapter owns the timer library.
type ScheduleRegistrar interface {
	Register(scheduleID string, at time.Time) error
	Unregister(scheduleID string)
}

// ScheduleService stores alarm-like schedules and dispatches their actions.
// Recurrence is an RFC 5545 RRULE over an IANA time zone; the rule is not
// limited to a fixed once/daily/weekly enum.
type ScheduleService struct {
	db          *gorm.DB
	schedules   *repository.ScheduleRepository
	devices     *repository.DeviceRepository
	groups      *repository.GroupRepository
	commands    *CommandService
	gateway     *GatewayStatusStore
	registrar   ScheduleRegistrar
	missedGrace time.Duration
	now         func() int64
	newID       func(string) string
}

func NewScheduleService(
	db *gorm.DB,
	schedules *repository.ScheduleRepository,
	devices *repository.DeviceRepository,
	groups *repository.GroupRepository,
	commands *CommandService,
	gateway *GatewayStatusStore,
	registrar ScheduleRegistrar,
	missedGrace time.Duration,
	opts ...Option,
) *ScheduleService {
	resolved := resolveOptions(opts)
	return &ScheduleService{
		db:          db,
		schedules:   schedules,
		devices:     devices,
		groups:      groups,
		commands:    commands,
		gateway:     gateway,
		registrar:   registrar,
		missedGrace: missedGrace,
		now:         resolved.now,
		newID:       resolved.newID,
	}
}

func (s *ScheduleService) List(
	ctx context.Context,
) (contract.ScheduleListResponse, error) {
	schedules, err := s.schedules.List(ctx)
	if err != nil {
		return contract.ScheduleListResponse{}, err
	}
	return contract.ScheduleListResponse{
		Schedules:         schedules,
		ServerTimeEpochMs: s.now(),
	}, nil
}

func (s *ScheduleService) Get(
	ctx context.Context,
	scheduleID string,
) (contract.Schedule, error) {
	if err := requireScheduleID(scheduleID); err != nil {
		return contract.Schedule{}, err
	}
	found, err := s.schedules.Find(ctx, scheduleID)
	if err != nil {
		return contract.Schedule{}, err
	}
	if found == nil {
		return contract.Schedule{}, apierror.ScheduleNotFound()
	}
	return *found, nil
}

func (s *ScheduleService) Create(
	ctx context.Context,
	request contract.CreateScheduleRequest,
) (contract.Schedule, error) {
	enabled := true
	if request.Enabled != nil {
		enabled = *request.Enabled
	}
	now := s.now()
	value := contract.Schedule{
		Name:             request.Name,
		TargetType:       request.TargetType,
		TargetID:         request.TargetID,
		Action:           request.Action,
		RRule:            request.RRule,
		TimeZone:         request.TimeZone,
		StartsAtEpochMs:  request.StartsAtEpochMs,
		Enabled:          enabled,
		CreatedAtEpochMs: now,
		UpdatedAtEpochMs: now,
	}
	// A disabled schedule may already be exhausted; only an enabled one needs
	// a future occurrence. This matches the update path.
	validated, err := s.validate(ctx, value, now, enabled, nil, false)
	if err != nil {
		return contract.Schedule{}, err
	}
	validated.ID = s.newID("sch")
	if err := s.schedules.Insert(ctx, validated); err != nil {
		return contract.Schedule{}, err
	}
	if validated.Enabled && validated.NextRunAtEpochMs != nil {
		if err := s.registrar.Register(
			validated.ID, time.UnixMilli(*validated.NextRunAtEpochMs),
		); err != nil {
			// A persisted schedule without a timer would silently never run.
			// Roll the row back so the client can retry.
			_ = s.schedules.Delete(context.Background(), validated.ID)
			return contract.Schedule{}, err
		}
	}
	logging.Infof(
		"[schedule] created id=%s target=%s/%s action=%s next=%v",
		validated.ID, validated.TargetType, validated.TargetID,
		validated.Action, validated.NextRunAtEpochMs,
	)
	return validated, nil
}

func (s *ScheduleService) Update(
	ctx context.Context,
	scheduleID string,
	request contract.UpdateScheduleRequest,
) (contract.Schedule, error) {
	if err := requireScheduleID(scheduleID); err != nil {
		return contract.Schedule{}, err
	}
	if request.Name == nil && request.TargetType == nil && request.TargetID == nil &&
		request.Action == nil && request.RRule == nil && request.TimeZone == nil &&
		request.StartsAtEpochMs == nil && request.Enabled == nil {
		return contract.Schedule{}, apierror.InvalidRequest(
			"at least one schedule field is required",
		)
	}

	found, err := s.schedules.Find(ctx, scheduleID)
	if err != nil {
		return contract.Schedule{}, err
	}
	if found == nil {
		return contract.Schedule{}, apierror.ScheduleNotFound()
	}

	updated := *found
	if request.Name != nil {
		updated.Name = *request.Name
	}
	if request.TargetType != nil {
		updated.TargetType = *request.TargetType
	}
	if request.TargetID != nil {
		updated.TargetID = *request.TargetID
	}
	if request.Action != nil {
		updated.Action = *request.Action
	}
	if request.RRule != nil {
		updated.RRule = *request.RRule
	}
	if request.TimeZone != nil {
		updated.TimeZone = *request.TimeZone
	}
	if request.StartsAtEpochMs != nil {
		updated.StartsAtEpochMs = *request.StartsAtEpochMs
	}
	if request.Enabled != nil {
		updated.Enabled = *request.Enabled
	}
	now := s.now()
	updated.UpdatedAtEpochMs = now

	// Recompute the next occurrence only when the recurrence itself changed or
	// a disabled schedule is enabled again. A name-only edit of an exhausted
	// one-time schedule must not turn into a validation failure, and must not
	// resurrect an occurrence that already fired.
	definitionChanged := request.RRule != nil || request.TimeZone != nil ||
		request.StartsAtEpochMs != nil
	enabling := request.Enabled != nil && *request.Enabled && !found.Enabled
	preserveNext := !definitionChanged && !enabling &&
		(found.NextRunAtEpochMs == nil || *found.NextRunAtEpochMs > now)
	requireFuture := updated.Enabled && (definitionChanged || enabling)
	validated, err := s.validate(
		ctx, updated, now, requireFuture, found.NextRunAtEpochMs, preserveNext,
	)
	if err != nil {
		return contract.Schedule{}, err
	}
	if err := s.schedules.UpdateDefinition(ctx, validated); err != nil {
		return contract.Schedule{}, err
	}

	s.registrar.Unregister(validated.ID)
	if validated.Enabled && validated.NextRunAtEpochMs != nil {
		if err := s.registrar.Register(
			validated.ID, time.UnixMilli(*validated.NextRunAtEpochMs),
		); err != nil {
			// The definition is already persisted. Log instead of returning a
			// failure that would make the client retry a successful edit; the
			// next restart restores the timer from nextRunAtEpochMs.
			logging.Errorf("[schedule] register updated id=%s: %v", validated.ID, err)
		}
	}
	logging.Infof(
		"[schedule] updated id=%s enabled=%t next=%v",
		validated.ID, validated.Enabled, validated.NextRunAtEpochMs,
	)
	return validated, nil
}

func (s *ScheduleService) Delete(ctx context.Context, scheduleID string) error {
	if err := requireScheduleID(scheduleID); err != nil {
		return err
	}
	found, err := s.schedules.Find(ctx, scheduleID)
	if err != nil {
		return err
	}
	if found == nil {
		return apierror.ScheduleNotFound()
	}
	if err := s.schedules.Delete(ctx, scheduleID); err != nil {
		return err
	}
	s.registrar.Unregister(scheduleID)
	logging.Infof("[schedule] deleted id=%s", scheduleID)
	return nil
}

// Start restores timers after a restart. A missed occurrence inside the grace
// window fires once; an older missed occurrence is skipped and the schedule
// resumes after now, so a long outage cannot cause a burst of stale commands.
func (s *ScheduleService) Start(ctx context.Context) error {
	schedules, err := s.schedules.ListEnabled(ctx)
	if err != nil {
		return err
	}
	now := s.now()
	for _, schedule := range schedules {
		if schedule.NextRunAtEpochMs == nil {
			next, computeErr := s.computeNext(schedule, now, true)
			if computeErr != nil {
				logging.Errorf("[schedule] restore id=%s: %v", schedule.ID, computeErr)
				continue
			}
			if next == nil {
				continue
			}
			if err := s.schedules.SetNextRun(ctx, schedule.ID, next, now); err != nil {
				logging.Errorf("[schedule] restore next id=%s: %v", schedule.ID, err)
				continue
			}
			schedule.NextRunAtEpochMs = next
		}

		next := *schedule.NextRunAtEpochMs
		if next > now {
			s.register(schedule)
			continue
		}
		if now-next <= s.missedGrace.Milliseconds() {
			logging.Infof(
				"[schedule] restore id=%s firing missed occurrence at=%d",
				schedule.ID, next,
			)
			go s.Fire(context.Background(), schedule.ID, next)
			continue
		}

		nextAfter, computeErr := s.computeNext(schedule, now, false)
		if computeErr != nil {
			logging.Errorf("[schedule] restore id=%s: %v", schedule.ID, computeErr)
			continue
		}
		if err := s.schedules.SetNextRun(ctx, schedule.ID, nextAfter, now); err != nil {
			logging.Errorf("[schedule] restore next id=%s: %v", schedule.ID, err)
			continue
		}
		if nextAfter != nil {
			s.registerAt(schedule.ID, *nextAfter)
			logging.Infof(
				"[schedule] restore id=%s skipped missed at=%d next=%d",
				schedule.ID, next, *nextAfter,
			)
		} else {
			logging.Infof(
				"[schedule] restore id=%s skipped missed at=%d and is exhausted",
				schedule.ID, next,
			)
		}
	}
	return nil
}

// Fire runs one occurrence. It validates the expected occurrence again so a
// timer left over from an edit cannot dispatch the new definition early.
func (s *ScheduleService) Fire(
	ctx context.Context,
	scheduleID string,
	expectedNextRunAtEpochMs int64,
) {
	found, err := s.schedules.Find(ctx, scheduleID)
	if err != nil {
		logging.Errorf("[schedule] fire load id=%s: %v", scheduleID, err)
		return
	}
	if found == nil {
		s.registrar.Unregister(scheduleID)
		return
	}
	if !found.Enabled || found.NextRunAtEpochMs == nil ||
		*found.NextRunAtEpochMs != expectedNextRunAtEpochMs {
		logging.Debugf(
			"[schedule] ignore stale job id=%s expected=%d current=%v",
			scheduleID, expectedNextRunAtEpochMs, found.NextRunAtEpochMs,
		)
		return
	}

	now := s.now()
	result, lastError := s.dispatch(ctx, *found)
	// Consume the occurrence that just fired even if the clock is slightly
	// behind it, otherwise a COUNT=1 rule would schedule itself again.
	after := now
	if expectedNextRunAtEpochMs > after {
		after = expectedNextRunAtEpochMs
	}
	next, computeErr := s.computeNext(*found, after, false)
	if computeErr != nil {
		result = contract.ScheduleResultFailed
		code := errorCode(computeErr)
		lastError = &code
		next = nil
	}
	if err := s.schedules.UpdateExecution(
		ctx, scheduleID, next, now, result, lastError, now,
	); err != nil {
		logging.Errorf("[schedule] persist run id=%s: %v", scheduleID, err)
	}

	s.registrar.Unregister(scheduleID)
	if next != nil && found.Enabled {
		s.registerAt(scheduleID, *next)
	}
	errorLabel := ""
	if lastError != nil {
		errorLabel = " error=" + *lastError
	}
	logging.Infof(
		"[schedule] fired id=%s target=%s/%s action=%s result=%s next=%v%s",
		scheduleID, found.TargetType, found.TargetID, found.Action,
		result, next, errorLabel,
	)
}

func (s *ScheduleService) dispatch(
	ctx context.Context,
	schedule contract.Schedule,
) (string, *string) {
	if !s.gateway.IsOnline() {
		reason := "gateway_unavailable"
		return contract.ScheduleResultSkipped, &reason
	}

	deviceIDs, skipReason, err := s.resolveTargets(ctx, schedule)
	if err != nil {
		code := errorCode(err)
		return contract.ScheduleResultFailed, &code
	}
	if skipReason != "" {
		return contract.ScheduleResultSkipped, &skipReason
	}
	if len(deviceIDs) == 0 {
		reason := "no_targets"
		return contract.ScheduleResultSkipped, &reason
	}

	batch, err := s.commands.IssueBatch(
		ctx,
		deviceIDs,
		schedule.Action,
		scheduledDispatchSeed(schedule.ID, schedule.NextRunAtEpochMs),
	)
	if err != nil {
		code := errorCode(err)
		return contract.ScheduleResultFailed, &code
	}
	if len(batch.Failures) == 0 {
		return contract.ScheduleResultDispatched, nil
	}
	code := batch.Failures[0].Code
	if len(batch.Dispatched) == 0 {
		return contract.ScheduleResultFailed, &code
	}
	return contract.ScheduleResultPartial, &code
}

func (s *ScheduleService) resolveTargets(
	ctx context.Context,
	schedule contract.Schedule,
) ([]string, string, error) {
	switch schedule.TargetType {
	case contract.ScheduleTargetDevice:
		found, err := s.devices.Find(ctx, schedule.TargetID)
		if err != nil {
			return nil, "", err
		}
		if found == nil {
			return nil, "device_not_found", nil
		}
		return []string{found.ID}, "", nil
	case contract.ScheduleTargetGroup:
		found, err := s.groups.Find(ctx, schedule.TargetID)
		if err != nil {
			return nil, "", err
		}
		if found == nil {
			return nil, "group_not_found", nil
		}
		if len(found.DeviceIDs) == 0 {
			return nil, "group_empty", nil
		}
		return found.DeviceIDs, "", nil
	default:
		return nil, "invalid_target_type", nil
	}
}

// validate normalizes a schedule and resolves its first occurrence. An
// enabled schedule must have a future occurrence; a disabled one may already
// be exhausted.
func (s *ScheduleService) validate(
	ctx context.Context,
	value contract.Schedule,
	now int64,
	requireFuture bool,
	next *int64,
	preserveNext bool,
) (contract.Schedule, error) {
	name, ok := normalizeOptionalLabel(value.Name)
	if !ok {
		return contract.Schedule{}, apierror.InvalidSchedule("name must be at most 64 characters")
	}
	value.Name = name

	switch value.TargetType {
	case contract.ScheduleTargetDevice:
		if err := requireDeviceID(value.TargetID); err != nil {
			return contract.Schedule{}, err
		}
		found, err := s.devices.Find(ctx, value.TargetID)
		if err != nil {
			return contract.Schedule{}, err
		}
		if found == nil {
			return contract.Schedule{}, removedOrNotFound(ctx, s.devices, value.TargetID)
		}
	case contract.ScheduleTargetGroup:
		if err := requireGroupID(value.TargetID); err != nil {
			return contract.Schedule{}, err
		}
		found, err := s.groups.Find(ctx, value.TargetID)
		if err != nil {
			return contract.Schedule{}, err
		}
		if found == nil {
			return contract.Schedule{}, apierror.GroupNotFound()
		}
	default:
		return contract.Schedule{}, apierror.InvalidSchedule(
			"targetType must be device or group",
		)
	}

	if value.Action != contract.ActionTurnOn && value.Action != contract.ActionTurnOff {
		return contract.Schedule{}, apierror.InvalidSchedule(
			"action must be turn_on or turn_off",
		)
	}

	timeZone := strings.TrimSpace(value.TimeZone)
	if timeZone == "" || strings.EqualFold(timeZone, "Local") {
		return contract.Schedule{}, apierror.InvalidSchedule("timeZone must be an IANA zone")
	}
	if _, err := time.LoadLocation(timeZone); err != nil {
		return contract.Schedule{}, apierror.InvalidSchedule("unknown timeZone: " + timeZone)
	}
	value.TimeZone = timeZone

	if value.StartsAtEpochMs <= 0 {
		return contract.Schedule{}, apierror.InvalidSchedule(
			"startsAtEpochMs must be a positive epoch timestamp",
		)
	}

	rule, normalizedRule, err := parseRRule(value.RRule, value.TimeZone, value.StartsAtEpochMs)
	if err != nil {
		return contract.Schedule{}, apierror.InvalidSchedule(err.Error())
	}
	value.RRule = normalizedRule

	if preserveNext {
		value.NextRunAtEpochMs = next
		return value, nil
	}
	computed, err := nextOccurrence(rule, now, true)
	if err != nil {
		return contract.Schedule{}, apierror.InvalidSchedule(err.Error())
	}
	if requireFuture && computed == nil {
		return contract.Schedule{}, apierror.InvalidSchedule(
			"recurrence has no future occurrence",
		)
	}
	value.NextRunAtEpochMs = computed
	return value, nil
}

func (s *ScheduleService) computeNext(
	value contract.Schedule,
	now int64,
	inclusive bool,
) (*int64, error) {
	rule, _, err := parseRRule(value.RRule, value.TimeZone, value.StartsAtEpochMs)
	if err != nil {
		return nil, err
	}
	return nextOccurrence(rule, now, inclusive)
}

func parseRRule(
	rfcValue, timeZone string,
	startsAtEpochMs int64,
) (*rrule.RRule, string, error) {
	normalized := strings.TrimSpace(rfcValue)
	if len(normalized) >= len("RRULE:") &&
		strings.EqualFold(normalized[:len("RRULE:")], "RRULE:") {
		normalized = strings.TrimSpace(normalized[len("RRULE:"):])
	}
	if normalized == "" {
		return nil, "", fmt.Errorf("rrule is required")
	}
	if strings.ContainsAny(normalized, "\r\n") {
		return nil, "", fmt.Errorf("rrule must be a single line")
	}
	if strings.Contains(strings.ToUpper(normalized), "DTSTART") {
		return nil, "", fmt.Errorf("DTSTART is carried by startsAtEpochMs, not rrule")
	}
	location, err := time.LoadLocation(timeZone)
	if err != nil {
		return nil, "", fmt.Errorf("unknown timeZone: %s", timeZone)
	}
	option, err := rrule.StrToROptionInLocation(normalized, location)
	if err != nil {
		return nil, "", fmt.Errorf("invalid rrule: %v", err)
	}
	option.Dtstart = time.UnixMilli(startsAtEpochMs).In(location)
	rule, err := rrule.NewRRule(*option)
	if err != nil {
		return nil, "", fmt.Errorf("invalid rrule: %v", err)
	}
	return rule, normalized, nil
}

func nextOccurrence(
	rule *rrule.RRule,
	afterEpochMs int64,
	inclusive bool,
) (*int64, error) {
	next := rule.After(time.UnixMilli(afterEpochMs), inclusive)
	if next.IsZero() {
		return nil, nil
	}
	value := next.UnixMilli()
	return &value, nil
}

func scheduledDispatchSeed(scheduleID string, nextRunAt *int64) string {
	if nextRunAt == nil {
		return scheduleID
	}
	return fmt.Sprintf("%s|%d", scheduleID, *nextRunAt)
}

func (s *ScheduleService) register(schedule contract.Schedule) {
	if schedule.NextRunAtEpochMs == nil {
		return
	}
	s.registerAt(schedule.ID, *schedule.NextRunAtEpochMs)
}

func (s *ScheduleService) registerAt(scheduleID string, nextRunAtEpochMs int64) {
	if err := s.registrar.Register(
		scheduleID, time.UnixMilli(nextRunAtEpochMs),
	); err != nil {
		logging.Errorf("[schedule] register id=%s: %v", scheduleID, err)
	}
}
