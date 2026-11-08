package repository

import (
	"context"

	"gorm.io/gorm"

	"esp32controlserver/internal/contract"
)

// DeleteDeviceReferences removes the rows that point at a device before the
// device itself is deleted. Schedules that target the device cannot run
// without it, so they are removed with the device instead of lingering as
// permanently failing jobs.
func DeleteDeviceReferences(
	ctx context.Context,
	db *gorm.DB,
	deviceID string,
) error {
	if err := db.WithContext(ctx).
		Where("deviceId = ?", deviceID).
		Delete(&deviceGroupRow{}).Error; err != nil {
		return err
	}
	return db.WithContext(ctx).
		Where("targetType = ? AND targetId = ?", contract.ScheduleTargetDevice, deviceID).
		Delete(&scheduleRow{}).Error
}

// DeleteGroupReferences removes memberships and group-targeted schedules. The
// group row itself is deleted by the caller.
func DeleteGroupReferences(
	ctx context.Context,
	db *gorm.DB,
	groupID string,
) error {
	if err := db.WithContext(ctx).
		Where("groupId = ?", groupID).
		Delete(&deviceGroupRow{}).Error; err != nil {
		return err
	}
	return db.WithContext(ctx).
		Where("targetType = ? AND targetId = ?", contract.ScheduleTargetGroup, groupID).
		Delete(&scheduleRow{}).Error
}
