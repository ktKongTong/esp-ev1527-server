package repository

import (
	"context"
	"errors"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"esp32controlserver/internal/contract"
)

type DeviceTombstone struct {
	DeviceID         string
	RemovedAtEpochMs int64
}

type IdempotencyRecord struct {
	IdempotencyKey      string
	DeviceID            string
	Action              string
	CreatedAtEpochMs    int64
	DispatchedAtEpochMs *int64
}

type DeviceRepository struct {
	db *gorm.DB
}

func NewDeviceRepository(db *gorm.DB) *DeviceRepository {
	return &DeviceRepository{db: db}
}

func (r *DeviceRepository) WithDB(db *gorm.DB) *DeviceRepository {
	return &DeviceRepository{db: db}
}

func (r *DeviceRepository) List(ctx context.Context) ([]contract.Device, error) {
	var rows []deviceRow
	if err := r.db.WithContext(ctx).Order("createdAtEpochMs ASC").Find(&rows).Error; err != nil {
		return nil, err
	}
	devices := make([]contract.Device, 0, len(rows))
	for _, row := range rows {
		devices = append(devices, row.toDevice())
	}
	return devices, nil
}

func (r *DeviceRepository) Find(ctx context.Context, id string) (*contract.Device, error) {
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

func (r *DeviceRepository) FindTombstone(
	ctx context.Context,
	deviceID string,
) (*DeviceTombstone, error) {
	var row deviceTombstoneRow
	err := r.db.WithContext(ctx).Where("deviceId = ?", deviceID).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &DeviceTombstone{
		DeviceID:         row.DeviceID,
		RemovedAtEpochMs: row.RemovedAtEpochMs,
	}, nil
}

// FindOwner matches on length and code value only. The gateway may report a
// different protocol number for the same physical code, so protocol must not
// split code identity.
func (r *DeviceRepository) FindOwner(
	ctx context.Context,
	length int,
	value int64,
) (string, error) {
	var row deviceRow
	err := r.db.WithContext(ctx).
		Select("id").
		Where(
			"rfLength = ? AND (codeOn = ? OR codeOff = ?)",
			length, value, value,
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

func (r *DeviceRepository) Insert(ctx context.Context, value contract.Device) error {
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

func (r *DeviceRepository) UpdateLabels(
	ctx context.Context,
	id, name, location string,
) error {
	return r.db.WithContext(ctx).
		Model(&deviceRow{}).
		Where("id = ?", id).
		Updates(map[string]any{"name": name, "location": location}).Error
}

func (r *DeviceRepository) Delete(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Where("id = ?", id).Delete(&deviceRow{}).Error
}

func (r *DeviceRepository) AddTombstone(
	ctx context.Context,
	deviceID string,
	removedAtEpochMs int64,
) error {
	return r.db.WithContext(ctx).
		Clauses(clause.OnConflict{UpdateAll: true}).
		Create(&deviceTombstoneRow{
			DeviceID:         deviceID,
			RemovedAtEpochMs: removedAtEpochMs,
		}).Error
}

func (r *DeviceRepository) PurgeTombstones(ctx context.Context, cutoffEpochMs int64) error {
	return r.db.WithContext(ctx).
		Where("removedAtEpochMs < ?", cutoffEpochMs).
		Delete(&deviceTombstoneRow{}).Error
}

type IdempotencyRepository struct {
	db *gorm.DB
}

func NewIdempotencyRepository(db *gorm.DB) *IdempotencyRepository {
	return &IdempotencyRepository{db: db}
}

func (r *IdempotencyRepository) WithDB(db *gorm.DB) *IdempotencyRepository {
	return &IdempotencyRepository{db: db}
}

func (r *IdempotencyRepository) Find(
	ctx context.Context,
	key string,
) (*IdempotencyRecord, error) {
	var row idempotencyRow
	err := r.db.WithContext(ctx).Where("idempotencyKey = ?", key).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &IdempotencyRecord{
		IdempotencyKey:      row.IdempotencyKey,
		DeviceID:            row.DeviceID,
		Action:              row.Action,
		CreatedAtEpochMs:    row.CreatedAtEpochMs,
		DispatchedAtEpochMs: row.DispatchedAtEpochMs,
	}, nil
}

func (r *IdempotencyRepository) Reserve(
	ctx context.Context,
	record IdempotencyRecord,
) error {
	return r.db.WithContext(ctx).Create(&idempotencyRow{
		IdempotencyKey:      record.IdempotencyKey,
		DeviceID:            record.DeviceID,
		Action:              record.Action,
		CreatedAtEpochMs:    record.CreatedAtEpochMs,
		DispatchedAtEpochMs: record.DispatchedAtEpochMs,
	}).Error
}

func (r *IdempotencyRepository) MarkDispatched(
	ctx context.Context,
	key string,
	atEpochMs int64,
) error {
	return r.db.WithContext(ctx).
		Model(&idempotencyRow{}).
		Where("idempotencyKey = ?", key).
		Update("dispatchedAtEpochMs", atEpochMs).Error
}

func (r *IdempotencyRepository) Release(ctx context.Context, key string) error {
	return r.db.WithContext(ctx).Where("idempotencyKey = ?", key).Delete(&idempotencyRow{}).Error
}

func (r *IdempotencyRepository) Purge(ctx context.Context, cutoffEpochMs int64) error {
	return r.db.WithContext(ctx).
		Where("createdAtEpochMs < ?", cutoffEpochMs).
		Delete(&idempotencyRow{}).Error
}
