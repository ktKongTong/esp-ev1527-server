package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"unicode/utf8"

	"gorm.io/gorm"
)

const idempotencyTTLMs = 10 * 60 * 1000

type rfPayload struct {
	Value    int64 `json:"value"`
	Protocol int   `json:"protocol"`
	Length   int   `json:"length"`
	Repeat   int   `json:"repeat"`
}

type rfTransmitter interface {
	Send(ctx context.Context, payload rfPayload) error
}

type commandService struct {
	db          *gorm.DB
	devices     *deviceRepository
	idempotency *idempotencyRepository
	gateway     *gatewayStatusStore
	transmitter rfTransmitter
	now         func() int64
}

func newCommandService(
	db *gorm.DB,
	devices *deviceRepository,
	idempotency *idempotencyRepository,
	gateway *gatewayStatusStore,
	transmitter rfTransmitter,
) *commandService {
	return &commandService{
		db:          db,
		devices:     devices,
		idempotency: idempotency,
		gateway:     gateway,
		transmitter: transmitter,
		now:         nowEpochMs,
	}
}

// issue publishes one RF code and stops there. The 315MHz link has no
// acknowledgement, so the server records nothing about the actuator state.
func (s *commandService) issue(
	ctx context.Context,
	deviceID, action, idempotencyKey string,
) (commandDispatched, bool, error) {
	if err := requireDeviceID(deviceID); err != nil {
		return commandDispatched{}, false, err
	}
	if action != actionTurnOn && action != actionTurnOff {
		return commandDispatched{}, false, invalidRequest("action must be turn_on or turn_off")
	}
	if count := utf8.RuneCountInString(idempotencyKey); count < 1 || count > 64 {
		return commandDispatched{}, false, invalidRequest("Idempotency-Key must be 1-64 characters")
	}

	var payload *rfPayload
	var replayed *commandDispatched

	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		now := s.now()
		devices := s.devices.withDB(tx)
		idempotency := s.idempotency.withDB(tx)

		if err := idempotency.purge(ctx, now-idempotencyTTLMs); err != nil {
			return err
		}
		existing, err := idempotency.find(ctx, idempotencyKey)
		if err != nil {
			return err
		}
		if existing != nil {
			if existing.DeviceID != deviceID || existing.Action != action {
				return newAPIError(
					http.StatusConflict,
					"idempotency_conflict",
					"Idempotency-Key was already used for a different request",
				)
			}
			if existing.DispatchedAtEpochMs == nil {
				return newAPIError(
					http.StatusConflict,
					"command_in_progress",
					"The original command is still being dispatched",
				)
			}
			found, err := devices.find(ctx, deviceID)
			if err != nil {
				return err
			}
			if found == nil {
				return removedOrNotFound(ctx, devices, deviceID)
			}
			debugf("[command] replay device=%s action=%s key=%s", deviceID, action, idempotencyKey)
			replayed = &commandDispatched{
				DeviceID:            deviceID,
				Action:              action,
				DispatchedAtEpochMs: *existing.DispatchedAtEpochMs,
			}
			return nil
		}

		found, err := devices.find(ctx, deviceID)
		if err != nil {
			return err
		}
		if found == nil {
			return removedOrNotFound(ctx, devices, deviceID)
		}
		if !s.gateway.isOnline() {
			warnf("[command] rejected device=%s action=%s: gateway offline", deviceID, action)
			return unavailable("gateway_unavailable", "Gateway is not online")
		}

		value := found.CodeOff
		if action == actionTurnOn {
			value = found.CodeOn
		}
		spec := rfPayload{
			Value:    value,
			Protocol: found.RF.Protocol,
			Length:   found.RF.Length,
			Repeat:   found.RF.Repeat,
		}
		payload = &spec
		return idempotency.reserve(ctx, idempotencyRecord{
			IdempotencyKey:   idempotencyKey,
			DeviceID:         deviceID,
			Action:           action,
			CreatedAtEpochMs: now,
		})
	})
	if err != nil {
		return commandDispatched{}, false, err
	}
	if replayed != nil {
		return *replayed, true, nil
	}
	if payload == nil {
		return commandDispatched{}, false, fmt.Errorf("missing publish payload")
	}

	// Do not publish for a device that was deleted after validation.
	mayPublish := false
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		found, err := s.devices.withDB(tx).find(ctx, deviceID)
		if err != nil {
			return err
		}
		mayPublish = found != nil
		return nil
	})
	if err != nil {
		_ = s.idempotency.release(context.Background(), idempotencyKey)
		return commandDispatched{}, false, err
	}
	if !mayPublish {
		_ = s.idempotency.release(context.Background(), idempotencyKey)
		return commandDispatched{}, false, newAPIError(
			http.StatusGone,
			"device_removed",
			"Device was removed",
		)
	}

	if err := s.transmitter.Send(ctx, *payload); err != nil {
		errorf(
			"[command] publish failed device=%s action=%s value=%d: %v",
			deviceID, action, payload.Value, err,
		)
		_ = s.idempotency.release(context.Background(), idempotencyKey)
		return commandDispatched{}, false, unavailable(
			"gateway_unavailable",
			"Failed to publish command to the gateway",
		)
	}

	dispatchedAt := s.now()
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		return s.idempotency.withDB(tx).markDispatched(ctx, idempotencyKey, dispatchedAt)
	})
	if err != nil {
		return commandDispatched{}, false, err
	}
	infof(
		"[command] dispatched device=%s action=%s value=%d repeat=%d",
		deviceID, action, payload.Value, payload.Repeat,
	)
	return commandDispatched{
		DeviceID:            deviceID,
		Action:              action,
		DispatchedAtEpochMs: dispatchedAt,
	}, false, nil
}

func (s *commandService) purge(now int64) error {
	return s.idempotency.purge(context.Background(), now-idempotencyTTLMs)
}

func encodeRFPayload(payload rfPayload) ([]byte, error) {
	return json.Marshal(payload)
}
