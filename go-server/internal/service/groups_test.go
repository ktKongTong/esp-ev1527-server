package service

import (
	"context"
	"net/http"
	"slices"
	"testing"

	"esp32controlserver/internal/contract"
)

func TestGroupMembershipIsManyToMany(t *testing.T) {
	f := newServiceFixture(t, 1_000_000)
	f.addDevice(t, "dev_a")
	f.addDevice(t, "dev_b")

	first, err := f.groupService.Create(context.Background(), contract.CreateGroupRequest{
		Name:      "  floor one  ",
		DeviceIDs: []string{"dev_a", "dev_b", "dev_a"},
	})
	if err != nil {
		t.Fatalf("create first group: %v", err)
	}
	if first.Name != "floor one" {
		t.Fatalf("name = %q", first.Name)
	}
	if !slices.Equal(first.DeviceIDs, []string{"dev_a", "dev_b"}) {
		t.Fatalf("members = %v", first.DeviceIDs)
	}

	second, err := f.groupService.Create(context.Background(), contract.CreateGroupRequest{
		Name:      "floor two",
		DeviceIDs: []string{"dev_a"},
	})
	if err != nil {
		t.Fatalf("create second group: %v", err)
	}
	if !slices.Equal(second.DeviceIDs, []string{"dev_a"}) {
		t.Fatalf("second members = %v", second.DeviceIDs)
	}

	list, err := f.groupService.List(context.Background())
	if err != nil {
		t.Fatalf("list groups: %v", err)
	}
	if len(list.Groups) != 2 {
		t.Fatalf("groups = %d, want 2", len(list.Groups))
	}
	if list.ServerTimeEpochMs != 1_000_000 {
		t.Fatalf("server time = %d", list.ServerTimeEpochMs)
	}
}

func TestGroupUpdateReplacesMembers(t *testing.T) {
	f := newServiceFixture(t, 1_000_000)
	f.addDevice(t, "dev_a")
	f.addDevice(t, "dev_b")
	group := f.addGroup(t, "floor", "dev_a", "dev_b")

	name := "renamed"
	updated, err := f.groupService.Update(
		context.Background(),
		group.ID,
		contract.UpdateGroupRequest{Name: &name},
	)
	if err != nil {
		t.Fatalf("rename group: %v", err)
	}
	if updated.Name != "renamed" || !slices.Equal(updated.DeviceIDs, []string{"dev_a", "dev_b"}) {
		t.Fatalf("rename changed membership: %+v", updated)
	}

	members := []string{"dev_b"}
	updated, err = f.groupService.Update(
		context.Background(),
		group.ID,
		contract.UpdateGroupRequest{DeviceIDs: &members},
	)
	if err != nil {
		t.Fatalf("replace members: %v", err)
	}
	if !slices.Equal(updated.DeviceIDs, []string{"dev_b"}) {
		t.Fatalf("members = %v", updated.DeviceIDs)
	}

	members = []string{"dev_missing"}
	_, err = f.groupService.Update(
		context.Background(),
		group.ID,
		contract.UpdateGroupRequest{DeviceIDs: &members},
	)
	requireAPIError(t, err, http.StatusNotFound, "device_not_found")
}

func TestGroupCreateRejectsInvalidMembers(t *testing.T) {
	f := newServiceFixture(t, 1_000_000)
	f.addDevice(t, "dev_a")

	_, err := f.groupService.Create(context.Background(), contract.CreateGroupRequest{
		Name:      " ",
		DeviceIDs: []string{"dev_a"},
	})
	requireAPIError(t, err, http.StatusBadRequest, "invalid_request")

	_, err = f.groupService.Create(context.Background(), contract.CreateGroupRequest{
		Name:      "floor",
		DeviceIDs: []string{"bad id"},
	})
	requireAPIError(t, err, http.StatusBadRequest, "invalid_device_id")

	_, err = f.groupService.Create(context.Background(), contract.CreateGroupRequest{
		Name:      "floor",
		DeviceIDs: []string{"dev_missing"},
	})
	requireAPIError(t, err, http.StatusNotFound, "device_not_found")
}

func TestGroupCommandFansOutAndReplays(t *testing.T) {
	f := newServiceFixture(t, 1_000_000)
	f.addDevice(t, "dev_a")
	f.addDevice(t, "dev_b")
	group := f.addGroup(t, "floor", "dev_a", "dev_b")

	result, err := f.groupService.IssueCommand(
		context.Background(), group.ID, contract.ActionTurnOn, "group-key-1",
	)
	if err != nil {
		t.Fatalf("issue group command: %v", err)
	}
	if result.DispatchedCount != 2 || result.FailedCount != 0 {
		t.Fatalf("result = %+v", result)
	}
	if len(result.Failures) != 0 {
		t.Fatalf("failures = %+v", result.Failures)
	}
	if len(f.transmitter.calls()) != 2 {
		t.Fatalf("publishes = %d, want 2", len(f.transmitter.calls()))
	}

	replayed, err := f.groupService.IssueCommand(
		context.Background(), group.ID, contract.ActionTurnOn, "group-key-1",
	)
	if err != nil {
		t.Fatalf("replay group command: %v", err)
	}
	if replayed.DispatchedCount != 2 {
		t.Fatalf("replay result = %+v", replayed)
	}
	if len(f.transmitter.calls()) != 2 {
		t.Fatalf("replay published again: %d", len(f.transmitter.calls()))
	}
}

func TestGroupCommandRejectsEmptyAndOffline(t *testing.T) {
	f := newServiceFixture(t, 1_000_000)
	empty := f.addGroup(t, "empty")
	_, err := f.groupService.IssueCommand(
		context.Background(), empty.ID, contract.ActionTurnOn, "group-key-1",
	)
	requireAPIError(t, err, http.StatusConflict, "group_empty")

	f.addDevice(t, "dev_a")
	group := f.addGroup(t, "floor", "dev_a")
	f.gateway.SetGateway(contract.GatewayOffline, nil)
	_, err = f.groupService.IssueCommand(
		context.Background(), group.ID, contract.ActionTurnOn, "group-key-2",
	)
	requireAPIError(t, err, http.StatusServiceUnavailable, "gateway_unavailable")
}

func TestGroupDeleteRemovesMembershipsAndSchedules(t *testing.T) {
	f := newServiceFixture(t, 1_000_000)
	f.addDevice(t, "dev_a")
	group := f.addGroup(t, "floor", "dev_a")

	schedule, err := f.scheduleService.Create(
		context.Background(),
		contract.CreateScheduleRequest{
			TargetType:      contract.ScheduleTargetGroup,
			TargetID:        group.ID,
			Action:          contract.ActionTurnOn,
			RRule:           "FREQ=DAILY",
			TimeZone:        "UTC",
			StartsAtEpochMs: 2_000_000,
		},
	)
	if err != nil {
		t.Fatalf("create group schedule: %v", err)
	}

	if err := f.groupService.Delete(context.Background(), group.ID); err != nil {
		t.Fatalf("delete group: %v", err)
	}
	if found, err := f.groups.Find(context.Background(), group.ID); err != nil || found != nil {
		t.Fatalf("group still exists: %+v, %v", found, err)
	}
	if found, err := f.schedules.Find(context.Background(), schedule.ID); err != nil || found != nil {
		t.Fatalf("schedule still exists: %+v, %v", found, err)
	}
	if found, err := f.devices.Find(context.Background(), "dev_a"); err != nil || found == nil {
		t.Fatalf("device was deleted with the group: %+v, %v", found, err)
	}
}
