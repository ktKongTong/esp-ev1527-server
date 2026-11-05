package com.esp32.control.server.data

import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.Device
import com.esp32.control.contract.RfParameters
import com.esp32.control.server.db.DeviceEntity
import com.esp32.control.server.db.IdempotencyEntity

internal fun DeviceEntity.toModel(): Device = Device(
    id = id,
    name = name,
    location = location,
    codeOn = codeOn,
    codeOff = codeOff,
    rf = RfParameters(protocol = rfProtocol, length = rfLength, repeat = rfRepeat),
    createdAtEpochMs = createdAtEpochMs,
)

internal fun Device.toEntity(): DeviceEntity = DeviceEntity(
    id = id,
    name = name,
    location = location,
    codeOn = codeOn,
    codeOff = codeOff,
    rfProtocol = rf.protocol,
    rfLength = rf.length,
    rfRepeat = rf.repeat,
    createdAtEpochMs = createdAtEpochMs,
)

internal fun IdempotencyEntity.toRecord(): IdempotencyRecord = IdempotencyRecord(
    idempotencyKey = idempotencyKey,
    deviceId = deviceId,
    action = CommandAction.valueOf(action),
    createdAtEpochMs = createdAtEpochMs,
    dispatchedAtEpochMs = dispatchedAtEpochMs,
)

internal fun IdempotencyRecord.toEntity(): IdempotencyEntity = IdempotencyEntity(
    idempotencyKey = idempotencyKey,
    deviceId = deviceId,
    action = action.name,
    createdAtEpochMs = createdAtEpochMs,
    dispatchedAtEpochMs = dispatchedAtEpochMs,
)
