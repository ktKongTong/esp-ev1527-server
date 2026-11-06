package main

import (
	"context"
	"errors"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type deviceTombstone struct {
	DeviceID         string
	RemovedAtEpochMs int64
}

type idempotencyRecord struct {
	IdempotencyKey      string
	DeviceID            string
	Action              string
	CreatedAtEpochMs    int64
	DispatchedAtEpochMs *int64
}

type deviceRepository struct {
	db *gorm.DB
}

func (r *deviceRepository) withDB(db *gorm.DB) *deviceRepository {
	return &deviceRepository{db: db}
}

func (r *deviceRepository) list(ctx context.Context) ([]device, error) {
	var rows []deviceRow
	if err := r.db.WithContext(ctx).Order("createdAtEpochMs ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	devices := make([]device, 0, len(rows))
	for _, row := range rows {
		devices = append(devices, row.toDevice())
	}
	return devices, nil
}

func (r *deviceRepository) find(ctx context.Context, id string) (*device, error) {
	var row deviceRow
	err := r.db.WithContext(ctx).Where("id = ?", id).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	value := row.toDevice()
	return &value, nil
}

func (r *deviceRepository) findTombstone(ctx context.Context, deviceID string) (*deviceTombstone, error) {
	var row deviceTombstoneRow
	err := r.db.WithContext(ctx).Where("deviceId = ?", deviceID).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &deviceTombstone{DeviceID: row.DeviceID, RemovedAtEpochMs: row.RemovedAtEpochMs}, nil
}

func (r *deviceRepository) findOwner(
	ctx context.Context,
	protocol, length int,
	value int64,
) (string, error) {
	var row deviceRow
	err := r.db.WithContext(ctx).
		Select("id").
		Where(
			"rfProtocol = ? AND rfLength = ? AND (codeOn = ? OR codeOff = ?)",
			protocol, length, value, value,
		).
		Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return row.ID, nil
}

func (r *deviceRepository) insert(ctx context.Context, value device) error {
	return r.db.WithContext(ctx).Create(&deviceRow{
		ID:               value.ID,
		Name:             value.Name,
		Location:         value.Location,
		CodeOn:           value.CodeOn,
		CodeOff:          value.CodeOff,
		RFProtocol:       value.RF.Protocol,
		RFLength:         value.RF.Length,
		RFRepeat:         value.RF.Repeat,
		CreatedAtEpochMs: value.CreatedAtEpochMs,
	}).Error
}

func (r *deviceRepository) delete(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Where("id = ?", id).Delete(&deviceRow{}).Error
}

func (r *deviceRepository) addTombstone(ctx context.Context, deviceID string, removedAtEpochMs int64) error {
	return r.db.WithContext(ctx).
		Clauses(clause.OnConflict{UpdateAll: true}).
		Create(&deviceTombstoneRow{DeviceID: deviceID, RemovedAtEpochMs: removedAtEpochMs}).Error
}

func (r *deviceRepository) purgeTombstones(ctx context.Context, cutoffEpochMs int64) error {
	return r.db.WithContext(ctx).
		Where("removedAtEpochMs < ?", cutoffEpochMs).
		Delete(&deviceTombstoneRow{}).Error
}

type idempotencyRepository struct {
	db *gorm.DB
}

func (r *idempotencyRepository) withDB(db *gorm.DB) *idempotencyRepository {
	return &idempotencyRepository{db: db}
}

func (r *idempotencyRepository) find(ctx context.Context, key string) (*idempotencyRecord, error) {
	var row idempotencyRow
	err := r.db.WithContext(ctx).Where("idempotencyKey = ?", key).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &idempotencyRecord{
		IdempotencyKey:      row.IdempotencyKey,
		DeviceID:            row.DeviceID,
		Action:              row.Action,
		CreatedAtEpochMs:    row.CreatedAtEpochMs,
		DispatchedAtEpochMs: row.DispatchedAtEpochMs,
	}, nil
}

func (r *idempotencyRepository) reserve(ctx context.Context, record idempotencyRecord) error {
	return r.db.WithContext(ctx).Create(&idempotencyRow{
		IdempotencyKey:      record.IdempotencyKey,
		DeviceID:            record.DeviceID,
		Action:              record.Action,
		CreatedAtEpochMs:    record.CreatedAtEpochMs,
		DispatchedAtEpochMs: record.DispatchedAtEpochMs,
	}).Error
}

func (r *idempotencyRepository) markDispatched(ctx context.Context, key string, atEpochMs int64) error {
	return r.db.WithContext(ctx).
		Model(&idempotencyRow{}).
		Where("idempotencyKey = ?", key).
		Update("dispatchedAtEpochMs", atEpochMs).Error
}

func (r *idempotencyRepository) release(ctx context.Context, key string) error {
	return r.db.WithContext(ctx).Where("idempotencyKey = ?", key).Delete(&idempotencyRow{}).Error
}

func (r *idempotencyRepository) purge(ctx context.Context, cutoffEpochMs int64) error {
	return r.db.WithContext(ctx).
		Where("createdAtEpochMs < ?", cutoffEpochMs).
		Delete(&idempotencyRow{}).Error
}

func (row deviceRow) toDevice() device {
	return device{
		ID:       row.ID,
		Name:     row.Name,
		Location: row.Location,
		CodeOn:   row.CodeOn,
		CodeOff:  row.CodeOff,
		RF: rfParameters{
			Protocol: row.RFProtocol,
			Length:   row.RFLength,
			Repeat:   row.RFRepeat,
		},
		CreatedAtEpochMs: row.CreatedAtEpochMs,
	}
}
