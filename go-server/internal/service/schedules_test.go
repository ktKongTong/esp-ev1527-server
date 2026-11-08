package service

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"

	"esp32controlserver/internal/contract"
)

func utcMillis(year int, month time.Month, day, hour, minute int) int64 {
	return time.Date(year, month, day, hour, minute, 0, 0, time.UTC).UnixMilli()
}

func TestScheduleCreateComputesNextOccurrence(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")

	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			Name:            "morning pump",
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "RRULE:FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 8, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	if schedule.RRule != "FREQ=DAILY" {
		t.Fatalf("rrule = %q", schedule.RRule)
	}
	wantNext := utcMillis(2026, time.November, 10, 8, 0)
	if schedule.NextRunAtEpochMs == nil || *schedule.NextRunAtEpochMs != wantNext {
		t.Fatalf("next = %v, want %d", schedule.NextRunAtEpochMs, wantNext)
	}
	registered, ok := f.registrar.registered(schedule.ID)
	if !ok || registered.UnixMilli() != wantNext {
		t.Fatalf("registered = %v, %t", registered, ok)
	}
}

func TestScheduleUsesTimeZoneWallClock(t *testing.T) {
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		t.Fatalf("load location: %v", err)
	}
	now := time.Date(2026, time.November, 9, 1, 0, 0, 0, time.UTC).UnixMilli()
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")

	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "Asia/Shanghai",
			StartsAtEpochMs: time.Date(2026, time.November, 9, 8, 0, 0, 0, loc).UnixMilli(),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	if schedule.NextRunAtEpochMs == nil {
		t.Fatal("next is nil")
	}
	next := time.UnixMilli(*schedule.NextRunAtEpochMs).In(loc)
	want := time.Date(2026, time.November, 10, 8, 0, 0, 0, loc)
	if !next.Equal(want) {
		t.Fatalf("next = %s, want %s", next, want)
	}
}

func TestScheduleOnceFiresAndExhausts(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")

	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY;COUNT=1",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	if schedule.NextRunAtEpochMs == nil {
		t.Fatal("next is nil")
	}
	f.scheduleService.Fire(context.Background(), schedule.ID, *schedule.NextRunAtEpochMs)

	if len(f.transmitter.calls()) != 1 {
		t.Fatalf("publishes = %d, want 1", len(f.transmitter.calls()))
	}
	stored, err := f.schedules.Find(context.Background(), schedule.ID)
	if err != nil {
		t.Fatalf("reload schedule: %v", err)
	}
	if stored.NextRunAtEpochMs != nil {
		t.Fatalf("next = %v, want nil", stored.NextRunAtEpochMs)
	}
	if stored.LastRunAtEpochMs == nil || *stored.LastRunAtEpochMs != now {
		t.Fatalf("last run = %v", stored.LastRunAtEpochMs)
	}
	if stored.LastResult == nil || *stored.LastResult != contract.ScheduleResultDispatched {
		t.Fatalf("last result = %v", stored.LastResult)
	}
	if f.registrar.count() != 0 {
		t.Fatalf("registrar still has %d jobs", f.registrar.count())
	}
}

func TestScheduleGroupFanOut(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	f.addDevice(t, "dev_b")
	group := f.addGroup(t, "floor", "dev_a", "dev_b")

	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetGroup,
			TargetID:        group.ID,
			Action:          contract.ActionTurnOff,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	f.scheduleService.Fire(context.Background(), schedule.ID, *schedule.NextRunAtEpochMs)

	calls := f.transmitter.calls()
	if len(calls) != 2 {
		t.Fatalf("publishes = %d, want 2", len(calls))
	}
	for _, call := range calls {
		if call.Value == 0 {
			t.Fatalf("unexpected payload: %+v", call)
		}
	}
	stored, err := f.schedules.Find(context.Background(), schedule.ID)
	if err != nil {
		t.Fatalf("reload schedule: %v", err)
	}
	if stored.LastResult == nil || *stored.LastResult != contract.ScheduleResultDispatched {
		t.Fatalf("last result = %v", stored.LastResult)
	}
	if stored.NextRunAtEpochMs == nil {
		t.Fatal("recurring schedule lost its next run")
	}
}

func TestScheduleSkipsWhenGatewayOffline(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	f.gateway.SetGateway(contract.GatewayOffline, nil)
	f.scheduleService.Fire(context.Background(), schedule.ID, *schedule.NextRunAtEpochMs)

	if len(f.transmitter.calls()) != 0 {
		t.Fatalf("published while offline: %d", len(f.transmitter.calls()))
	}
	stored, err := f.schedules.Find(context.Background(), schedule.ID)
	if err != nil {
		t.Fatalf("reload schedule: %v", err)
	}
	if stored.LastResult == nil || *stored.LastResult != contract.ScheduleResultSkipped {
		t.Fatalf("last result = %v", stored.LastResult)
	}
	if stored.LastError == nil || *stored.LastError != "gateway_unavailable" {
		t.Fatalf("last error = %v", stored.LastError)
	}
}

func TestScheduleIgnoresStaleJob(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	before := *schedule.NextRunAtEpochMs
	f.scheduleService.Fire(context.Background(), schedule.ID, before+1)
	if len(f.transmitter.calls()) != 0 {
		t.Fatalf("stale job dispatched: %d", len(f.transmitter.calls()))
	}
	stored, err := f.schedules.Find(context.Background(), schedule.ID)
	if err != nil {
		t.Fatalf("reload schedule: %v", err)
	}
	if stored.NextRunAtEpochMs == nil || *stored.NextRunAtEpochMs != before {
		t.Fatalf("stale job changed next: %v", stored.NextRunAtEpochMs)
	}
}

func TestScheduleValidation(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	group := f.addGroup(t, "floor", "dev_a")
	validStart := utcMillis(2026, time.November, 9, 10, 0)

	base := contract.CreateScheduleRequest{
		TargetType:      contract.ScheduleTargetDevice,
		TargetID:        "dev_a",
		Action:          contract.ActionTurnOn,
		RRule:           "FREQ=DAILY",
		TimeZone:        "UTC",
		StartsAtEpochMs: validStart,
	}
	cases := []struct {
		name   string
		mutate func(*contract.CreateScheduleRequest)
		status int
		code   string
	}{
		{"bad target type", func(r *contract.CreateScheduleRequest) { r.TargetType = "area" }, http.StatusBadRequest, "invalid_schedule"},
		{"bad action", func(r *contract.CreateScheduleRequest) { r.Action = "toggle" }, http.StatusBadRequest, "invalid_schedule"},
		{"bad timezone", func(r *contract.CreateScheduleRequest) { r.TimeZone = "Mars/Base" }, http.StatusBadRequest, "invalid_schedule"},
		{"empty rrule", func(r *contract.CreateScheduleRequest) { r.RRule = " " }, http.StatusBadRequest, "invalid_schedule"},
		{"dtstart in rrule", func(r *contract.CreateScheduleRequest) { r.RRule = "DTSTART:20261109T100000Z\nRRULE:FREQ=DAILY" }, http.StatusBadRequest, "invalid_schedule"},
		{"zero start", func(r *contract.CreateScheduleRequest) { r.StartsAtEpochMs = 0 }, http.StatusBadRequest, "invalid_schedule"},
		{"exhausted recurrence", func(r *contract.CreateScheduleRequest) {
			r.StartsAtEpochMs = utcMillis(2026, time.November, 9, 8, 0)
			r.RRule = "FREQ=DAILY;COUNT=1"
		}, http.StatusBadRequest, "invalid_schedule"},
		{"disabled exhausted recurrence", func(r *contract.CreateScheduleRequest) {
			disabled := false
			r.Enabled = &disabled
			r.StartsAtEpochMs = utcMillis(2026, time.November, 9, 8, 0)
			r.RRule = "FREQ=DAILY;COUNT=1"
		}, 0, ""},
		{"unknown device", func(r *contract.CreateScheduleRequest) { r.TargetID = "dev_missing" }, http.StatusNotFound, "device_not_found"},
		{"unknown group", func(r *contract.CreateScheduleRequest) {
			r.TargetType = contract.ScheduleTargetGroup
			r.TargetID = "grp_missing"
		}, http.StatusNotFound, "group_not_found"},
		{"group schedule", func(r *contract.CreateScheduleRequest) {
			r.TargetType = contract.ScheduleTargetGroup
			r.TargetID = group.ID
		}, 0, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			request := base
			tc.mutate(&request)
			_, err := f.scheduleService.Create(context.Background(), request)
			if tc.status == 0 {
				if err != nil {
					t.Fatalf("create: %v", err)
				}
				return
			}
			requireAPIError(t, err, tc.status, tc.code)
		})
	}
}

func TestScheduleUpdatePreservesExhaustedOneTime(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY;COUNT=1",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}
	// The timer fires at the occurrence time.
	*f.now = *schedule.NextRunAtEpochMs
	f.scheduleService.Fire(context.Background(), schedule.ID, *schedule.NextRunAtEpochMs)

	name := "finished"
	updated, err := f.scheduleService.Update(
		context.Background(),
		schedule.ID,
		contract.UpdateScheduleRequest{Name: &name},
	)
	if err != nil {
		t.Fatalf("rename exhausted schedule: %v", err)
	}
	if updated.Name != "finished" || updated.NextRunAtEpochMs != nil {
		t.Fatalf("updated = %+v", updated)
	}

	disabled := false
	updated, err = f.scheduleService.Update(
		context.Background(),
		schedule.ID,
		contract.UpdateScheduleRequest{Enabled: &disabled},
	)
	if err != nil {
		t.Fatalf("disable exhausted schedule: %v", err)
	}
	if updated.Enabled || updated.NextRunAtEpochMs != nil {
		t.Fatalf("disabled schedule = %+v", updated)
	}

	*f.now++
	enabled := true
	_, err = f.scheduleService.Update(
		context.Background(),
		schedule.ID,
		contract.UpdateScheduleRequest{Enabled: &enabled},
	)
	requireAPIError(t, err, http.StatusBadRequest, "invalid_schedule")
}

func TestScheduleUpdateRecomputesNext(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0) // Monday
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 8, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}

	rule := "FREQ=WEEKLY;BYDAY=MO"
	start := utcMillis(2026, time.November, 9, 8, 0)
	updated, err := f.scheduleService.Update(
		context.Background(),
		schedule.ID,
		contract.UpdateScheduleRequest{RRule: &rule, StartsAtEpochMs: &start},
	)
	if err != nil {
		t.Fatalf("update recurrence: %v", err)
	}
	want := utcMillis(2026, time.November, 16, 8, 0)
	if updated.NextRunAtEpochMs == nil || *updated.NextRunAtEpochMs != want {
		t.Fatalf("next = %v, want %d", updated.NextRunAtEpochMs, want)
	}
	registered, ok := f.registrar.registered(schedule.ID)
	if !ok || registered.UnixMilli() != want {
		t.Fatalf("registered = %v, %t", registered, ok)
	}

	disabled := false
	updated, err = f.scheduleService.Update(
		context.Background(),
		schedule.ID,
		contract.UpdateScheduleRequest{Enabled: &disabled},
	)
	if err != nil {
		t.Fatalf("disable schedule: %v", err)
	}
	if updated.Enabled {
		t.Fatal("schedule still enabled")
	}
	if _, ok := f.registrar.registered(schedule.ID); ok {
		t.Fatal("disabled schedule still registered")
	}
}

func TestDeviceDeleteRemovesMembershipAndSchedules(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	group := f.addGroup(t, "floor", "dev_a")
	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	if err != nil {
		t.Fatalf("create schedule: %v", err)
	}

	if _, err := f.deviceService.Delete(context.Background(), "dev_a"); err != nil {
		t.Fatalf("delete device: %v", err)
	}
	if found, err := f.schedules.Find(context.Background(), schedule.ID); err != nil || found != nil {
		t.Fatalf("schedule still exists: %+v, %v", found, err)
	}
	reloaded, err := f.groups.Find(context.Background(), group.ID)
	if err != nil {
		t.Fatalf("reload group: %v", err)
	}
	if reloaded == nil || len(reloaded.DeviceIDs) != 0 {
		t.Fatalf("membership remains: %+v", reloaded)
	}
}

func TestScheduleStartRestoresAndSkipsMissed(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")

	future, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	if err != nil {
		t.Fatalf("create future schedule: %v", err)
	}

	missedNext := utcMillis(2026, time.November, 9, 8, 0)
	missed, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: missedNext,
		},
	)
	if err != nil {
		t.Fatalf("create missed schedule: %v", err)
	}
	// Simulate a restart where the persisted next occurrence is older than
	// the grace window.
	if err := f.schedules.SetNextRun(context.Background(), missed.ID, &missedNext, now); err != nil {
		t.Fatalf("set missed next: %v", err)
	}

	// Recreate the service with an empty registrar, as a restart would.
	restarted := NewScheduleService(
		f.db, f.schedules, f.devices, f.groups, f.commands, f.gateway,
		f.registrar, time.Minute,
		WithNow(func() int64 { return *f.now }),
	)
	if err := restarted.Start(context.Background()); err != nil {
		t.Fatalf("start schedules: %v", err)
	}
	if registered, ok := f.registrar.registered(future.ID); !ok || registered.UnixMilli() != *future.NextRunAtEpochMs {
		t.Fatalf("future schedule not restored: %v, %t", registered, ok)
	}
	reloaded, err := f.schedules.Find(context.Background(), missed.ID)
	if err != nil {
		t.Fatalf("reload missed schedule: %v", err)
	}
	wantNext := utcMillis(2026, time.November, 10, 8, 0)
	if reloaded.NextRunAtEpochMs == nil || *reloaded.NextRunAtEpochMs != wantNext {
		t.Fatalf("missed next = %v, want %d", reloaded.NextRunAtEpochMs, wantNext)
	}
	if len(f.transmitter.calls()) != 0 {
		t.Fatalf("missed schedule dispatched on restart: %d", len(f.transmitter.calls()))
	}
}

func TestScheduleRejectsOversizedName(t *testing.T) {
	now := utcMillis(2026, time.November, 9, 9, 0)
	f := newServiceFixture(t, now)
	f.addDevice(t, "dev_a")
	_, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			Name:            strings.Repeat("a", 65),
			TargetType:      contract.ScheduleTargetDevice,
			TargetID:        "dev_a",
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: utcMillis(2026, time.November, 9, 10, 0),
		},
	)
	requireAPIError(t, err, http.StatusBadRequest, "invalid_schedule")
}
