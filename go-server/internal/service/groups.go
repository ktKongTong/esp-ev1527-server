package service

import (
	"context"

	"gorm.io/gorm"

	"esp32controlserver/internal/apierror"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/repository"
)

// GroupService owns named device collections. Membership is many-to-many:
// one device can belong to any number of groups, and groups never own the
// device or its learned codes.
type GroupService struct {
	db       *gorm.DB
	groups   *repository.GroupRepository
	devices  *repository.DeviceRepository
	commands *CommandService
	gateway  *GatewayStatusStore
	now      func() int64
	newID    func(string) string
}

func NewGroupService(
	db *gorm.DB,
	groups *repository.GroupRepository,
	devices *repository.DeviceRepository,
	commands *CommandService,
	gateway *GatewayStatusStore,
	opts ...Option,
) *GroupService {
	resolved := resolveOptions(opts)
	return &GroupService{
		db:       db,
		groups:   groups,
		devices:  devices,
		commands: commands,
		gateway:  gateway,
		now:      resolved.now,
		newID:    resolved.newID,
	}
}

func (s *GroupService) List(ctx context.Context) (contract.GroupListResponse, error) {
	groups, err := s.groups.List(ctx)
	if err != nil {
		return contract.GroupListResponse{}, err
	}
	return contract.GroupListResponse{
		Groups:            groups,
		ServerTimeEpochMs: s.now(),
	}, nil
}

func (s *GroupService) Get(ctx context.Context, groupID string) (contract.Group, error) {
	if err := requireGroupID(groupID); err != nil {
		return contract.Group{}, err
	}
	found, err := s.groups.Find(ctx, groupID)
	if err != nil {
		return contract.Group{}, err
	}
	if found == nil {
		return contract.Group{}, apierror.GroupNotFound()
	}
	return *found, nil
}

func (s *GroupService) Create(
	ctx context.Context,
	request contract.CreateGroupRequest,
) (contract.Group, error) {
	name, ok := normalizeLabel(request.Name)
	if !ok {
		return contract.Group{}, apierror.InvalidRequest("name must be 1-64 characters")
	}
	deviceIDs, err := s.validateMembers(ctx, request.DeviceIDs)
	if err != nil {
		return contract.Group{}, err
	}

	now := s.now()
	group := contract.Group{
		ID:               s.newID("grp"),
		Name:             name,
		DeviceIDs:        deviceIDs,
		CreatedAtEpochMs: now,
		UpdatedAtEpochMs: now,
	}
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		groups := s.groups.WithDB(tx)
		if err := groups.Insert(ctx, group); err != nil {
			return err
		}
		return groups.ReplaceMembers(ctx, group.ID, deviceIDs, now)
	})
	if err != nil {
		return contract.Group{}, err
	}
	return group, nil
}

func (s *GroupService) Update(
	ctx context.Context,
	groupID string,
	request contract.UpdateGroupRequest,
) (contract.Group, error) {
	if err := requireGroupID(groupID); err != nil {
		return contract.Group{}, err
	}
	if request.Name == nil && request.DeviceIDs == nil {
		return contract.Group{}, apierror.InvalidRequest("name or deviceIds is required")
	}

	var name string
	if request.Name != nil {
		value, ok := normalizeLabel(*request.Name)
		if !ok {
			return contract.Group{}, apierror.InvalidRequest("name must be 1-64 characters")
		}
		name = value
	}
	var deviceIDs []string
	if request.DeviceIDs != nil {
		value, err := s.validateMembers(ctx, *request.DeviceIDs)
		if err != nil {
			return contract.Group{}, err
		}
		deviceIDs = value
	}

	var updated contract.Group
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		groups := s.groups.WithDB(tx)
		found, err := groups.Find(ctx, groupID)
		if err != nil {
			return err
		}
		if found == nil {
			return apierror.GroupNotFound()
		}
		updated = *found
		if request.Name != nil {
			updated.Name = name
		}
		if request.DeviceIDs != nil {
			updated.DeviceIDs = deviceIDs
		}
		updated.UpdatedAtEpochMs = s.now()
		if err := groups.Update(ctx, groupID, updated.Name, updated.UpdatedAtEpochMs); err != nil {
			return err
		}
		if request.DeviceIDs != nil {
			return groups.ReplaceMembers(
				ctx, groupID, updated.DeviceIDs, updated.UpdatedAtEpochMs,
			)
		}
		return nil
	})
	if err != nil {
		return contract.Group{}, err
	}
	return updated, nil
}

// Delete removes the group, its memberships and every schedule that targets
// the group. The devices themselves are untouched.
func (s *GroupService) Delete(ctx context.Context, groupID string) error {
	if err := requireGroupID(groupID); err != nil {
		return err
	}
	return s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		groups := s.groups.WithDB(tx)
		found, err := groups.Find(ctx, groupID)
		if err != nil {
			return err
		}
		if found == nil {
			return apierror.GroupNotFound()
		}
		if err := repository.DeleteGroupReferences(ctx, tx, groupID); err != nil {
			return err
		}
		return groups.Delete(ctx, groupID)
	})
}

// IssueCommand fans one action out to the current members. The response
// reports per-device results because the RF link has no acknowledgement and a
// partial failure is a normal outcome.
func (s *GroupService) IssueCommand(
	ctx context.Context,
	groupID, action, idempotencyKey string,
) (contract.GroupCommandDispatched, error) {
	if err := requireGroupID(groupID); err != nil {
		return contract.GroupCommandDispatched{}, err
	}
	found, err := s.groups.Find(ctx, groupID)
	if err != nil {
		return contract.GroupCommandDispatched{}, err
	}
	if found == nil {
		return contract.GroupCommandDispatched{}, apierror.GroupNotFound()
	}
	if len(found.DeviceIDs) == 0 {
		return contract.GroupCommandDispatched{}, apierror.GroupEmpty()
	}
	if !s.gateway.IsOnline() {
		return contract.GroupCommandDispatched{}, apierror.Unavailable(
			"gateway_unavailable",
			"Gateway is not online",
		)
	}

	result, err := s.commands.IssueBatch(
		ctx, found.DeviceIDs, action, idempotencyKey,
	)
	if err != nil {
		return contract.GroupCommandDispatched{}, err
	}
	failures := result.Failures
	if failures == nil {
		failures = []contract.CommandFailure{}
	}
	return contract.GroupCommandDispatched{
		GroupID:             groupID,
		Action:              action,
		DispatchedCount:     len(result.Dispatched),
		FailedCount:         len(failures),
		Failures:            failures,
		DispatchedAtEpochMs: s.now(),
	}, nil
}

func (s *GroupService) validateMembers(
	ctx context.Context,
	requested []string,
) ([]string, error) {
	deviceIDs := make([]string, 0, len(requested))
	seen := make(map[string]struct{}, len(requested))
	for _, deviceID := range requested {
		if err := requireDeviceID(deviceID); err != nil {
			return nil, err
		}
		if _, exists := seen[deviceID]; exists {
			continue
		}
		found, err := s.devices.Find(ctx, deviceID)
		if err != nil {
			return nil, err
		}
		if found == nil {
			return nil, removedOrNotFound(ctx, s.devices, deviceID)
		}
		seen[deviceID] = struct{}{}
		deviceIDs = append(deviceIDs, deviceID)
	}
	return deviceIDs, nil
}
