package com.esp32.control.server.data

import com.esp32.control.contract.ActionType
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.CommandRecord
import com.esp32.control.contract.CommandStatus
import com.esp32.control.contract.Device
import com.esp32.control.contract.DeviceAction
import com.esp32.control.contract.DeviceState
import com.esp32.control.contract.RfParameters
import com.esp32.control.contract.StateEvidence
import com.esp32.control.server.db.CommandEntity
import com.esp32.control.server.db.DeviceEntity
import com.esp32.control.server.db.IdempotencyEntity

internal fun DeviceEntity.toModel(): Device = Device(
    id = id,
    name = name,
    location = location,
    state = DeviceState.valueOf(state),
    stateEvidence = StateEvidence.valueOf(stateEvidence),
    online = online,
    codeOn = codeOn,
    codeOff = codeOff,
    rf = RfParameters(protocol = rfProtocol, length = rfLength, repeat = rfRepeat),
    lastAction = lastActionType?.let { type ->
        DeviceAction(
            type = ActionType.valueOf(type),
            atEpochMs = requireNotNull(lastActionAtEpochMs) { "lastActionAtEpochMs is null" },
            commandId = lastActionCommandId,
        )
    },
    revision = revision,
    createdAtEpochMs = createdAtEpochMs,
)

internal fun Device.toEntity(): DeviceEntity = DeviceEntity(
    id = id,
    name = name,
    location = location,
    state = state.name,
    stateEvidence = stateEvidence.name,
    online = online,
    codeOn = codeOn,
    codeOff = codeOff,
    rfProtocol = rf.protocol,
    rfLength = rf.length,
    rfRepeat = rf.repeat,
    lastActionType = lastAction?.type?.name,
    lastActionAtEpochMs = lastAction?.atEpochMs,
    lastActionCommandId = lastAction?.commandId,
    revision = revision,
    createdAtEpochMs = createdAtEpochMs,
)

internal fun CommandEntity.toModel(): CommandRecord = CommandRecord(
    commandId = commandId,
    deviceId = deviceId,
    action = CommandAction.valueOf(action),
    status = CommandStatus.valueOf(status),
    requestedAtEpochMs = requestedAtEpochMs,
    dispatchedAtEpochMs = dispatchedAtEpochMs,
    failureReason = failureReason,
)

internal fun CommandRecord.toEntity(): CommandEntity = CommandEntity(
    commandId = commandId,
    deviceId = deviceId,
    action = action.name,
    status = status.name,
    requestedAtEpochMs = requestedAtEpochMs,
    dispatchedAtEpochMs = dispatchedAtEpochMs,
    failureReason = failureReason,
)

internal fun IdempotencyEntity.toRecord(): IdempotencyRecord = IdempotencyRecord(
    idempotencyKey = idempotencyKey,
    commandId = commandId,
    deviceId = deviceId,
    action = CommandAction.valueOf(action),
    createdAtEpochMs = createdAtEpochMs,
)

internal fun IdempotencyRecord.toEntity(): IdempotencyEntity = IdempotencyEntity(
    idempotencyKey = idempotencyKey,
    commandId = commandId,
    deviceId = deviceId,
    action = action.name,
    createdAtEpochMs = createdAtEpochMs,
)

