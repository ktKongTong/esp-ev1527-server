package service

import (
	"context"
	"fmt"
	"net/http"
	"unicode/utf8"

	"gorm.io/gorm"

	"esp32controlserver/internal/apierror"
	"esp32controlserver/internal/contract"
	"esp32controlserver/internal/logging"
	"esp32controlserver/internal/repository"
)

const idempotencyTTLMs = 10 * 60 * 1000

// RFTransmitter publishes one command frame to the gateway.
type RFTransmitter interface {
	Send(ctx context.Context, payload contract.RFPayload) error
}

type CommandService struct {
	db          *gorm.DB
	devices     *repository.DeviceRepository
	idempotency *repository.IdempotencyRepository
	gateway     *GatewayStatusStore
	transmitter RFTransmitter
	now         func() int64
}

func NewCommandService(
	db *gorm.DB,
	devices *repository.DeviceRepository,
	idempotency *repository.IdempotencyRepository,
	gateway *GatewayStatusStore,
	transmitter RFTransmitter,
	opts ...Option,
) *CommandService {
	resolved := resolveOptions(opts)
	return &CommandService{
		db:          db,
		devices:     devices,
		idempotency: idempotency,
		gateway:     gateway,
		transmitter: transmitter,
		now:         resolved.now,
	}
}

// Issue publishes one RF code and stops there. The 315MHz link has no
// acknowledgement, so the server records nothing about the actuator state.
func (s *CommandService) Issue(
	ctx context.Context,
	deviceID, action, idempotencyKey string,
) (contract.CommandDispatched, bool, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return contract.CommandDispatched{}, false, err
	}
	if action != contract.ActionTurnOn && action != contract.ActionTurnOff {
		return contract.CommandDispatched{}, false, apierror.InvalidRequest(
			"action must be turn_on or turn_off",
		)
	}
	if count := utf8.RuneCountInString(idempotencyKey); count < 1 || count > 64 {
		return contract.CommandDispatched{}, false, apierror.InvalidRequest(
			"Idempotency-Key must be 1-64 characters",
		)
	}

	var payload *contract.RFPayload
	var replayed *contract.CommandDispatched

	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		now := s.now()
		devices := s.devices.WithDB(tx)
		idempotency := s.idempotency.WithDB(tx)

		if err := idempotency.Purge(ctx, now-idempotencyTTLMs); err != nil {
			return err
		}
		existing, err := idempotency.Find(ctx, idempotencyKey)
		if err != nil {
			return err
		}
		if existing != nil {
			if existing.DeviceID != deviceID || existing.Action != action {
				return apierror.New(
					http.StatusConflict,
					"idempotency_conflict",
					"Idempotency-Key was already used for a different request",
				)
			}
			if existing.DispatchedAtEpochMs == nil {
				return apierror.New(
					http.StatusConflict,
					"command_in_progress",
					"The original command is still being dispatched",
				)
			}
			found, err := devices.Find(ctx, deviceID)
			if err != nil {
				return err
			}
			if found == nil {
				return removedOrNotFound(ctx, devices, deviceID)
			}
			logging.Debugf(
				"[command] replay device=%s action=%s key=%s",
				deviceID, action, idempotencyKey,
			)
			replayed = &contract.CommandDispatched{
				DeviceID:            deviceID,
				Action:              action,
				DispatchedAtEpochMs: *existing.DispatchedAtEpochMs,
			}
			return nil
		}

		found, err := devices.Find(ctx, deviceID)
		if err != nil {
			return err
		}
		if found == nil {
			return removedOrNotFound(ctx, devices, deviceID)
		}
		if !s.gateway.IsOnline() {
			logging.Warnf("[command] rejected device=%s action=%s: gateway offline", deviceID, action)
			return apierror.Unavailable("gateway_unavailable", "Gateway is not online")
		}

		value := found.CodeOff
		if action == contract.ActionTurnOn {
			value = found.CodeOn
		}
		spec := contract.RFPayload{
			Value:    value,
			Protocol: found.RF.Protocol,
			Length:   found.RF.Length,
			Repeat:   found.RF.Repeat,
		}
		payload = &spec
		return idempotency.Reserve(ctx, repository.IdempotencyRecord{
			IdempotencyKey:   idempotencyKey,
			DeviceID:         deviceID,
			Action:           action,
			CreatedAtEpochMs: now,
		})
	})
	if err != nil {
		return contract.CommandDispatched{}, false, err
	}
	if replayed != nil {
		return *replayed, true, nil
	}
	if payload == nil {
		return contract.CommandDispatched{}, false, fmt.Errorf("missing publish payload")
	}

	// Do not publish for a device that was deleted after validation.
	mayPublish := false
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		found, err := s.devices.WithDB(tx).Find(ctx, deviceID)
		if err != nil {
			return err
		}
		mayPublish = found != nil
		return nil
	})
	if err != nil {
		_ = s.idempotency.Release(context.Background(), idempotencyKey)
		return contract.CommandDispatched{}, false, err
	}
	if !mayPublish {
		_ = s.idempotency.Release(context.Background(), idempotencyKey)
		return contract.CommandDispatched{}, false, apierror.New(
			http.StatusGone,
			"device_removed",
			"Device was removed",
		)
	}

	if err := s.transmitter.Send(ctx, *payload); err != nil {
		logging.Errorf(
			"[command] publish failed device=%s action=%s value=%d: %v",
			deviceID, action, payload.Value, err,
		)
		_ = s.idempotency.Release(context.Background(), idempotencyKey)
		return contract.CommandDispatched{}, false, apierror.Unavailable(
			"gateway_unavailable",
			"Failed to publish command to the gateway",
		)
	}

	dispatchedAt := s.now()
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		return s.idempotency.WithDB(tx).MarkDispatched(ctx, idempotencyKey, dispatchedAt)
	})
	if err != nil {
		return contract.CommandDispatched{}, false, err
	}
	logging.Infof(
		"[command] dispatched device=%s action=%s value=%d repeat=%d",
		deviceID, action, payload.Value, payload.Repeat,
	)
	return contract.CommandDispatched{
		DeviceID:            deviceID,
		Action:              action,
		DispatchedAtEpochMs: dispatchedAt,
	}, false, nil
}

func (s *CommandService) Purge(now int64) error {
	return s.idempotency.Purge(context.Background(), now-idempotencyTTLMs)
}
