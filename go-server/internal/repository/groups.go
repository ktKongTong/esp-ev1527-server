package repository

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"esp32controlserver/internal/contract"
)

type GroupRepository struct {
	db *gorm.DB
}

func NewGroupRepository(db *gorm.DB) *GroupRepository {
	return &GroupRepository{db: db}
}

func (r *GroupRepository) WithDB(db *gorm.DB) *GroupRepository {
	return &GroupRepository{db: db}
}

func (r *GroupRepository) List(ctx context.Context) ([]contract.Group, error) {
	var rows []groupRow
	if err := r.db.WithContext(ctx).
		Order("createdAtEpochMs ASC").
		Order("id ASC").
		Find(&rows).Error; err != nil {
		return nil, err
	}
	groups := make([]contract.Group, 0, len(rows))
	if len(rows) == 0 {
		return groups, nil
	}

	ids := make([]string, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.ID)
	}
	members, err := r.membersByGroup(ctx, ids)
	if err != nil {
		return nil, err
	}
	for _, row := range rows {
		deviceIDs := members[row.ID]
		if deviceIDs == nil {
			deviceIDs = []string{}
		}
		groups = append(groups, contract.Group{
			ID:               row.ID,
			Name:             row.Name,
			DeviceIDs:        deviceIDs,
			CreatedAtEpochMs: row.CreatedAtEpochMs,
			UpdatedAtEpochMs: row.UpdatedAtEpochMs,
		})
	}
	return groups, nil
}

func (r *GroupRepository) Find(ctx context.Context, id string) (*contract.Group, error) {
	var row groupRow
	err := r.db.WithContext(ctx).Where("id = ?", id).Take(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	deviceIDs, err := r.Members(ctx, id)
	if err != nil {
		return nil, err
	}
	return &contract.Group{
		ID:               row.ID,
		Name:             row.Name,
		DeviceIDs:        deviceIDs,
		CreatedAtEpochMs: row.CreatedAtEpochMs,
		UpdatedAtEpochMs: row.UpdatedAtEpochMs,
	}, nil
}

func (r *GroupRepository) Insert(ctx context.Context, value contract.Group) error {
	return r.db.WithContext(ctx).Create(&groupRow{
		ID:               value.ID,
		Name:             value.Name,
		CreatedAtEpochMs: value.CreatedAtEpochMs,
		UpdatedAtEpochMs: value.UpdatedAtEpochMs,
	}).Error
}

func (r *GroupRepository) Update(
	ctx context.Context,
	id, name string,
	updatedAtEpochMs int64,
) error {
	return r.db.WithContext(ctx).
		Model(&groupRow{}).
		Where("id = ?", id).
		Updates(map[string]any{
			"name":             name,
			"updatedAtEpochMs": updatedAtEpochMs,
		}).Error
}

func (r *GroupRepository) Delete(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Where("id = ?", id).Delete(&groupRow{}).Error
}

// ReplaceMembers replaces the full membership set. The service validates and
// de-duplicates the ids before calling it.
func (r *GroupRepository) ReplaceMembers(
	ctx context.Context,
	groupID string,
	deviceIDs []string,
	addedAtEpochMs int64,
) error {
	if err := r.db.WithContext(ctx).
		Where("groupId = ?", groupID).
		Delete(&deviceGroupRow{}).Error; err != nil {
		return err
	}
	if len(deviceIDs) == 0 {
		return nil
	}
	rows := make([]deviceGroupRow, 0, len(deviceIDs))
	for _, deviceID := range deviceIDs {
		rows = append(rows, deviceGroupRow{
			DeviceID:       deviceID,
			GroupID:        groupID,
			AddedAtEpochMs: addedAtEpochMs,
		})
	}
	return r.db.WithContext(ctx).Create(&rows).Error
}

func (r *GroupRepository) Members(ctx context.Context, groupID string) ([]string, error) {
	var rows []deviceGroupRow
	if err := r.db.WithContext(ctx).
		Where("groupId = ?", groupID).
		Order("addedAtEpochMs ASC").
		Order("deviceId ASC").
		Find(&rows).Error; err != nil {
		return nil, err
	}
	deviceIDs := make([]string, 0, len(rows))
	for _, row := range rows {
		deviceIDs = append(deviceIDs, row.DeviceID)
	}
	return deviceIDs, nil
}

func (r *GroupRepository) membersByGroup(
	ctx context.Context,
	groupIDs []string,
) (map[string][]string, error) {
	var rows []deviceGroupRow
	if err := r.db.WithContext(ctx).
		Where("groupId IN ?", groupIDs).
		Order("addedAtEpochMs ASC").
		Order("deviceId ASC").
		Find(&rows).Error; err != nil {
		return nil, err
	}
	members := make(map[string][]string, len(groupIDs))
	for _, row := range rows {
		members[row.GroupID] = append(members[row.GroupID], row.DeviceID)
	}
	return members, nil
}

func (r *GroupRepository) DeleteMembersByDevice(
	ctx context.Context,
	deviceID string,
) error {
	return r.db.WithContext(ctx).
		Where("deviceId = ?", deviceID).
		Delete(&deviceGroupRow{}).Error
}
