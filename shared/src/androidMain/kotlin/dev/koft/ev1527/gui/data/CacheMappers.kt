package dev.koft.ev1527.gui.data

import dev.koft.ev1527.contract.CommandAction
import dev.koft.ev1527.contract.ContractJson
import dev.koft.ev1527.contract.Device
import dev.koft.ev1527.contract.Group
import dev.koft.ev1527.contract.RfParameters
import dev.koft.ev1527.contract.Schedule
import dev.koft.ev1527.contract.ScheduleResult
import dev.koft.ev1527.contract.ScheduleTarget
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

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

internal fun GroupEntity.toModel(): Group = Group(
    id = id,
    name = name,
    deviceIds = decodeDeviceIds(deviceIdsJson),
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

internal fun Group.toEntity(): GroupEntity = GroupEntity(
    id = id,
    name = name,
    deviceIdsJson = encodeDeviceIds(deviceIds),
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

internal fun ScheduleEntity.toModel(): Schedule = Schedule(
    id = id,
    name = name,
    targetType = targetType.toScheduleTarget(),
    targetId = targetId,
    action = action.toCommandAction(),
    rrule = rrule,
    timeZone = timeZone,
    startsAtEpochMs = startsAtEpochMs,
    enabled = enabled,
    nextRunAtEpochMs = nextRunAtEpochMs,
    lastRunAtEpochMs = lastRunAtEpochMs,
    lastResult = lastResult?.toScheduleResult(),
    lastError = lastError,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

internal fun Schedule.toEntity(): ScheduleEntity = ScheduleEntity(
    id = id,
    name = name,
    targetType = targetType.name,
    targetId = targetId,
    action = action.name,
    rrule = rrule,
    timeZone = timeZone,
    startsAtEpochMs = startsAtEpochMs,
    enabled = enabled,
    nextRunAtEpochMs = nextRunAtEpochMs,
    lastRunAtEpochMs = lastRunAtEpochMs,
    lastResult = lastResult?.name,
    lastError = lastError,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun encodeDeviceIds(ids: List<String>): String =
    ContractJson.encodeToString(ListSerializer(String.serializer()), ids)

private fun decodeDeviceIds(raw: String): List<String> =
    runCatching {
        ContractJson.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrDefault(emptyList())

private fun String.toScheduleTarget(): ScheduleTarget =
    ScheduleTarget.entries.firstOrNull { it.name == this } ?: ScheduleTarget.DEVICE

private fun String.toCommandAction(): CommandAction =
    CommandAction.entries.firstOrNull { it.name == this } ?: CommandAction.TURN_ON

private fun String.toScheduleResult(): ScheduleResult? =
    ScheduleResult.entries.firstOrNull { it.name == this }
