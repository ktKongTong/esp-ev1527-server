package main

import (
	"context"

	"gorm.io/gorm"
)

const tombstoneTTLMs = 24 * 60 * 60 * 1000

type deviceService struct {
	db      *gorm.DB
	devices *deviceRepository
	now     func() int64
}

func newDeviceService(db *gorm.DB, devices *deviceRepository) *deviceService {
	return &deviceService{db: db, devices: devices, now: nowEpochMs}
}

func (s *deviceService) list(ctx context.Context) (deviceListResponse, error) {
	devices, err := s.devices.list(ctx)
	if err != nil {
		return deviceListResponse{}, err
	}
	return deviceListResponse{
		Devices:           devices,
		ServerTimeEpochMs: s.now(),
	}, nil
}

func (s *deviceService) get(ctx context.Context, deviceID string) (device, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return device{}, err
	}
	found, err := s.devices.find(ctx, deviceID)
	if err != nil {
		return device{}, err
	}
	if found != nil {
		return *found, nil
	}
	return device{}, removedOrNotFound(ctx, s.devices, deviceID)
}

// update edits only the labels. RF codes and parameters stay owned by the
// learned pair, so editing never touches the code registry.
func (s *deviceService) update(
	ctx context.Context,
	deviceID string,
	request updateDeviceRequest,
) (device, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return device{}, err
	}
	if request.Name == nil && request.Location == nil {
		return device{}, invalidRequest("name or location is required")
	}
	var name, location string
	if request.Name != nil {
		value, ok := normalizeDeviceLabel(*request.Name)
		if !ok {
			return device{}, invalidRequest("name and location must be 1-64 characters")
		}
		name = value
	}
	if request.Location != nil {
		value, ok := normalizeDeviceLabel(*request.Location)
		if !ok {
			return device{}, invalidRequest("name and location must be 1-64 characters")
		}
		location = value
	}

	var updated device
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		devices := s.devices.withDB(tx)
		found, err := devices.find(ctx, deviceID)
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
		return devices.updateLabels(ctx, deviceID, updated.Name, updated.Location)
	})
	return updated, err
}

// delete removes the device and leaves a tombstone so later reads can return
// 410. It returns false when the tombstone already exists, which the route
// still reports as 204.
func (s *deviceService) delete(ctx context.Context, deviceID string) (bool, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return false, err
	}
	deleted := false
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		devices := s.devices.withDB(tx)
		found, err := devices.find(ctx, deviceID)
		if err != nil {
			return err
		}
		if found == nil {
			tombstone, err := devices.findTombstone(ctx, deviceID)
			if err != nil {
				return err
			}
			if tombstone != nil {
				return nil
			}
			return deviceNotFound()
		}
		if err := devices.delete(ctx, deviceID); err != nil {
			return err
		}
		if err := devices.addTombstone(ctx, deviceID, s.now()); err != nil {
			return err
		}
		deleted = true
		return nil
	})
	return deleted, err
}

func (s *deviceService) purge(now int64) error {
	return s.devices.purgeTombstones(context.Background(), now-tombstoneTTLMs)
}

// removedOrNotFound returns device_removed when a tombstone exists and
// device_not_found otherwise. Callers use it after a successful nil lookup.
func removedOrNotFound(
	ctx context.Context,
	devices *deviceRepository,
	deviceID string,
) error {
	tombstone, err := devices.findTombstone(ctx, deviceID)
	if err != nil {
		return err
	}
	if tombstone != nil {
		return deviceRemoved(tombstone.RemovedAtEpochMs)
	}
	return deviceNotFound()
}
