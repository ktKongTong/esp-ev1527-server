package service

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"gorm.io/gorm"

	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/repository"
)

type fakeRegistrar struct {
	mu      sync.Mutex
	nextRun map[string]time.Time
}

func newFakeRegistrar() *fakeRegistrar {
	return &fakeRegistrar{nextRun: make(map[string]time.Time)}
}

func (f *fakeRegistrar) Register(scheduleID string, at time.Time) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.nextRun[scheduleID] = at
	return nil
}

func (f *fakeRegistrar) Unregister(scheduleID string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.nextRun, scheduleID)
}

func (f *fakeRegistrar) registered(scheduleID string) (time.Time, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	value, ok := f.nextRun[scheduleID]
	return value, ok
}

func (f *fakeRegistrar) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.nextRun)
}

type serviceFixture struct {
	db              *gorm.DB
	devices         *repository.DeviceRepository
	groups          *repository.GroupRepository
	schedules       *repository.ScheduleRepository
	commands        *CommandService
	deviceService   *DeviceService
	groupService    *GroupService
	scheduleService *ScheduleService
	registrar       *fakeRegistrar
	transmitter     *fakeTransmitter
	gateway         *GatewayStatusStore
	now             *int64
}

func newServiceFixture(t *testing.T, now int64) *serviceFixture {
	t.Helper()
	db := newTestDB(t)
	nowPtr := new(int64)
	*nowPtr = now
	counter := 0
	clock := WithNow(func() int64 { return *nowPtr })
	ids := WithIDGenerator(func(prefix string) string {
		counter++
		return fmt.Sprintf("%s_test_%d", prefix, counter)
	})

	devices := repository.NewDeviceRepository(db)
	groups := repository.NewGroupRepository(db)
	schedules := repository.NewScheduleRepository(db)
	idempotency := repository.NewIdempotencyRepository(db)
	gateway := newOnlineGateway()
	transmitter := &fakeTransmitter{}
	registrar := newFakeRegistrar()

	commands := NewCommandService(
		db, devices, idempotency, gateway, transmitter, clock,
	)
	deviceService := NewDeviceService(db, devices, clock)
	groupService := NewGroupService(
		db, groups, devices, commands, gateway, clock, ids,
	)
	scheduleService := NewScheduleService(
		db, schedules, devices, groups, commands, gateway, registrar,
		2*time.Minute, clock, ids,
	)

	return &serviceFixture{
		db:              db,
		devices:         devices,
		groups:          groups,
		schedules:       schedules,
		commands:        commands,
		deviceService:   deviceService,
		groupService:    groupService,
		scheduleService: scheduleService,
		registrar:       registrar,
		transmitter:     transmitter,
		gateway:         gateway,
		now:             nowPtr,
	}
}

func (f *serviceFixture) addDevice(t *testing.T, deviceID string) {
	t.Helper()
	device := sampleDevice()
	device.ID = deviceID
	device.Name = "name-" + deviceID
	if err := f.devices.Insert(context.Background(), device); err != nil {
		t.Fatalf("insert device %s: %v", deviceID, err)
	}
}

func (f *serviceFixture) addGroup(
	t *testing.T,
	name string,
	deviceIDs ...string,
) contract.Group {
	t.Helper()
	group, err := f.groupService.Create(context.Background(), contract.CreateGroupRequest{
		Name:      name,
		DeviceIDs: deviceIDs,
	})
	if err != nil {
		t.Fatalf("create group: %v", err)
	}
	return group
}
