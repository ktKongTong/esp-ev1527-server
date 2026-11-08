package repository

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"esp32controlserver/internal/contract"
)

type ScheduleRepository struct {
	db *gorm.DB
}

func NewScheduleRepository(db *gorm.DB) *ScheduleRepository {
	return &ScheduleRepository{db: db}
}

func (r *ScheduleRepository) WithDB(db *gorm.DB) *ScheduleRepository {
	return &ScheduleRepository{db: db}
}

func (r *ScheduleRepository) List(ctx context.Context) ([]contract.Schedule, error) {
	var rows []scheduleRow
	if err := r.db.WithContext(ctx).
		Order("createdAtEpochMs ASC").
		Order("id ASC").
		Find(&rows).Error; err != nil {
		return nil, err
	}
	schedules := make([]contract.Schedule, 0, len(rows))
	for _, row := range rows {
		schedules = append(schedules, row.toSchedule())
	}
	return schedules, nil
}

func (r *ScheduleRepository) ListEnabled(ctx context.Context) ([]contract.Schedule, error) {
	var rows []scheduleRow
	if err := r.db.WithContext(ctx).
		Where("enabled = ?", true).
		Order("nextRunAtEpochMs ASC").
		Order("id ASC").
		Find(&rows).Error; err != nil {
		return nil, err
	}
	schedules := make([]contract.Schedule, 0, len(rows))
	for _, row := range rows {
		schedules = append(schedules, row.toSchedule())
	}
	return schedules, nil
}

func (r *ScheduleRepository) Find(
	ctx context.Context,
	id string,
) (*contract.Schedule, error) {
	var row scheduleRow
	err := r.db.WithContext(ctx).Where("id = ?", id).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	value := row.toSchedule()
	return &value, nil
}

func (r *ScheduleRepository) Insert(ctx context.Context, value contract.Schedule) error {
	return r.db.WithContext(ctx).Create(&scheduleRow{
		ID:               value.ID,
		Name:             value.Name,
		TargetType:       value.TargetType,
		TargetID:         value.TargetID,
		Action:           value.Action,
		RRule:            value.RRule,
		TimeZone:         value.TimeZone,
		StartsAtEpochMs:  value.StartsAtEpochMs,
		Enabled:          value.Enabled,
		NextRunAtEpochMs: value.NextRunAtEpochMs,
		LastRunAtEpochMs: value.LastRunAtEpochMs,
		LastResult:       value.LastResult,
		LastError:        value.LastError,
		CreatedAtEpochMs: value.CreatedAtEpochMs,
		UpdatedAtEpochMs: value.UpdatedAtEpochMs,
	}).Error
}

// UpdateDefinition writes the user-editable definition and its materialized
// next occurrence. Run history stays untouched.
func (r *ScheduleRepository) UpdateDefinition(
	ctx context.Context,
	value contract.Schedule,
) error {
	return r.db.WithContext(ctx).
		Model(&scheduleRow{}).
		Where("id = ?", value.ID).
		Updates(map[string]any{
			"name":             value.Name,
			"targetType":       value.TargetType,
			"targetId":         value.TargetID,
			"action":           value.Action,
			"rrule":            value.RRule,
			"timeZone":         value.TimeZone,
			"startsAtEpochMs":  value.StartsAtEpochMs,
			"enabled":          value.Enabled,
			"nextRunAtEpochMs": value.NextRunAtEpochMs,
			"updatedAtEpochMs": value.UpdatedAtEpochMs,
		}).Error
}

func (r *ScheduleRepository) UpdateExecution(
	ctx context.Context,
	id string,
	nextRunAtEpochMs *int64,
	lastRunAtEpochMs int64,
	result string,
	lastError *string,
	updatedAtEpochMs int64,
) error {
	return r.db.WithContext(ctx).
		Model(&scheduleRow{}).
		Where("id = ?", id).
		Updates(map[string]any{
			"nextRunAtEpochMs": nextRunAtEpochMs,
			"lastRunAtEpochMs": lastRunAtEpochMs,
			"lastResult":       result,
			"lastError":        lastError,
			"updatedAtEpochMs": updatedAtEpochMs,
		}).Error
}

func (r *ScheduleRepository) SetNextRun(
	ctx context.Context,
	id string,
	nextRunAtEpochMs *int64,
	updatedAtEpochMs int64,
) error {
	return r.db.WithContext(ctx).
		Model(&scheduleRow{}).
		Where("id = ?", id).
		Updates(map[string]any{
			"nextRunAtEpochMs": nextRunAtEpochMs,
			"updatedAtEpochMs": updatedAtEpochMs,
		}).Error
}

func (r *ScheduleRepository) Delete(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Where("id = ?", id).Delete(&scheduleRow{}).Error
}

func (r *ScheduleRepository) DeleteByTarget(
	ctx context.Context,
	targetType, targetID string,
) error {
	return r.db.WithContext(ctx).
		Where("targetType = ? AND targetId = ?", targetType, targetID).
		Delete(&scheduleRow{}).Error
}
