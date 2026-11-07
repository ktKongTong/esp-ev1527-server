package service

import (
	"context"

	"gorm.io/gorm"

	"esp32controlserver/internal/apierror"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/repository"
)

const tombstoneTTLMs = 24 * 60 * 60 * 1000

type DeviceService struct {
	db      *gorm.DB
	devices *repository.DeviceRepository
	now     func() int64
}

func NewDeviceService(
	db *gorm.DB,
	devices *repository.DeviceRepository,
	opts ...Option,
) *DeviceService {
	resolved := resolveOptions(opts)
	return &DeviceService{db: db, devices: devices, now: resolved.now}
}

func (s *DeviceService) List(ctx context.Context) (contract.DeviceListResponse, error) {
	devices, err := s.devices.List(ctx)
	if err != nil {
		return contract.DeviceListResponse{}, err
	}
	return contract.DeviceListResponse{
		Devices:           devices,
		ServerTimeEpochMs: s.now(),
	}, nil
}

func (s *DeviceService) Get(ctx context.Context, deviceID string) (contract.Device, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return contract.Device{}, err
	}
	found, err := s.devices.Find(ctx, deviceID)
	if err != nil {
		return contract.Device{}, err
	}
	if found != nil {
		return *found, nil
	}
	return contract.Device{}, removedOrNotFound(ctx, s.devices, deviceID)
}

// Update edits only the labels. RF codes and parameters stay owned by the
// learned pair, so editing never touches the code registry.
func (s *DeviceService) Update(
	ctx context.Context,
	deviceID string,
	request contract.UpdateDeviceRequest,
) (contract.Device, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return contract.Device{}, err
	}
	if request.Name == nil && request.Location == nil {
		return contract.Device{}, apierror.InvalidRequest("name or location is required")
	}
	var name, location string
	if request.Name != nil {
		value, ok := normalizeDeviceLabel(*request.Name)
		if !ok {
			return contract.Device{}, apierror.InvalidRequest(
				"name and location must be 1-64 characters",
			)
		}
		name = value
	}
	if request.Location != nil {
		value, ok := normalizeDeviceLabel(*request.Location)
		if !ok {
			return contract.Device{}, apierror.InvalidRequest(
				"name and location must be 1-64 characters",
			)
		}
		location = value
	}

	var updated contract.Device
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		devices := s.devices.WithDB(tx)
		found, err := devices.Find(ctx, deviceID)
		if err != nil {
			return err
		}
		if found == nil {
			return removedOrNotFound(ctx, devices, deviceID)
		}
		updated = *found
		if request.Name != nil {
			updated.Name = name
		}
		if request.Location != nil {
			updated.Location = location
		}
		return devices.UpdateLabels(ctx, deviceID, updated.Name, updated.Location)
	})
	return updated, err
}

// Delete removes the device and leaves a tombstone so later reads can return
// 410. It returns false when the tombstone already exists, which the route
// still reports as 204.
func (s *DeviceService) Delete(ctx context.Context, deviceID string) (bool, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return false, err
	}
	deleted := false
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		devices := s.devices.WithDB(tx)
		found, err := devices.Find(ctx, deviceID)
		if err != nil {
			return err
		}
		if found == nil {
			tombstone, err := devices.FindTombstone(ctx, deviceID)
			if err != nil {
				return err
			}
			if tombstone != nil {
				return nil
			}
			return apierror.DeviceNotFound()
		}
		if err := devices.Delete(ctx, deviceID); err != nil {
			return err
		}
		if err := devices.AddTombstone(ctx, deviceID, s.now()); err != nil {
			return err
		}
		deleted = true
		return nil
	})
	return deleted, err
}

func (s *DeviceService) Purge(now int64) error {
	return s.devices.PurgeTombstones(context.Background(), now-tombstoneTTLMs)
}

// removedOrNotFound returns device_removed when a tombstone exists and
// device_not_found otherwise. Callers use it after a successful nil lookup.
func removedOrNotFound(
	ctx context.Context,
	devices *repository.DeviceRepository,
	deviceID string,
) error {
	tombstone, err := devices.FindTombstone(ctx, deviceID)
	if err != nil {
		return err
	}
	if tombstone != nil {
		return apierror.DeviceRemoved(tombstone.RemovedAtEpochMs)
	}
	return apierror.DeviceNotFound()
}
