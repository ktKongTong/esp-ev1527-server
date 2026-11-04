package com.esp32.control.server.domain

import androidx.room3.withWriteTransaction
import com.esp32.control.contract.ActionType
import com.esp32.control.contract.CommandAccepted
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.CommandRecord
import com.esp32.control.contract.CommandStatus
import com.esp32.control.contract.DeviceAction
import com.esp32.control.contract.DeviceState
import com.esp32.control.contract.StateEvidence
import com.esp32.control.server.ApiException
import com.esp32.control.server.data.CommandRepository
import com.esp32.control.server.data.DeviceRepository
import com.esp32.control.server.data.IdempotencyRecord
import com.esp32.control.server.db.AppDatabase
import com.esp32.control.server.deviceNotFound
import com.esp32.control.server.deviceRemoved
import com.esp32.control.server.mqtt.GatewayStatusStore
import com.esp32.control.server.mqtt.RfPayload
import com.esp32.control.server.mqtt.RfTransmitter
import com.esp32.control.server.newId
import com.esp32.control.server.nowEpochMs
import com.esp32.control.server.requireDeviceId
import io.ktor.http.HttpStatusCode

data class CommandResult(
    val accepted: CommandAccepted,
    val replayed: Boolean,
)

class CommandService(
    private val db: AppDatabase,
    private val devices: DeviceRepository,
    private val commands: CommandRepository,
    private val gateway: GatewayStatusStore,
    private val transmitter: RfTransmitter,
) {
    suspend fun get(commandId: String): CommandRecord =
        commands.find(commandId)
            ?: throw ApiException(
                HttpStatusCode.NotFound,
                "command_not_found",
                "Command not found",
            )

    suspend fun issue(
        deviceId: String,
        action: CommandAction,
        idempotencyKey: String,
    ): CommandResult {
        requireDeviceId(deviceId)
        if (idempotencyKey.isBlank() || idempotencyKey.length > 64) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Idempotency-Key must be 1-64 characters",
            )
        }

        val commandId = newId("cmd")
        var payload: RfPayload? = null
        var requestedAtEpochMs = 0L
        var replay: CommandResult? = null

        db.withWriteTransaction {
            val now = nowEpochMs()
            purge(now)

            val existing = commands.findIdempotency(idempotencyKey)
            if (existing != null) {
                if (existing.deviceId != deviceId || existing.action != action) {
                    throw ApiException(
                        HttpStatusCode.Conflict,
                        "idempotency_conflict",
                        "Idempotency-Key was already used for a different request",
                    )
                }
                val command = commands.find(existing.commandId)
                if (command != null) {
                    val device = devices.find(deviceId) ?: throw removed(deviceId)
                    replay = CommandResult(CommandAccepted(command, device), replayed = true)
                    return@withWriteTransaction
                }
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "command_in_progress",
                    "The original command is still being dispatched",
                )
            }

            val device = devices.find(deviceId) ?: run {
                devices.findTombstone(deviceId)?.let { throw deviceRemoved(it) }
                throw deviceNotFound()
            }
            if (!gateway.isOnline()) {
                throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "gateway_unavailable",
                    "Gateway is not online",
                )
            }
            if (!device.online) {
                throw ApiException(HttpStatusCode.Conflict, "device_offline", "Device is offline")
            }

            val spec = RfPayload(
                value = if (action == CommandAction.TURN_ON) device.codeOn else device.codeOff,
                protocol = device.rf.protocol,
                length = device.rf.length,
                repeat = device.rf.repeat,
            )
            commands.saveIdempotency(
                IdempotencyRecord(
                    idempotencyKey = idempotencyKey,
                    commandId = commandId,
                    deviceId = deviceId,
                    action = action,
                    createdAtEpochMs = now,
                ),
            )
            requestedAtEpochMs = now
            payload = spec
        }

        replay?.let { return it }
        val spec = payload ?: error("missing publish payload")

        // Do not publish for a device that was deleted after validation.
        val mayPublish = db.withWriteTransaction {
            devices.find(deviceId) != null
        }

        if (!mayPublish) {
            db.withWriteTransaction {
                commands.save(
                    CommandRecord(
                        commandId = commandId,
                        deviceId = deviceId,
                        action = action,
                        status = CommandStatus.CANCELLED,
                        requestedAtEpochMs = requestedAtEpochMs,
                    ),
                )
            }
            throw ApiException(HttpStatusCode.Gone, "device_removed", "Device was removed")
        }

        // QoS 1 only means the broker accepted the frame. The 315MHz link has
        // no acknowledgement, so the command is terminal once published.
        val publishError = runCatching { transmitter.send(spec) }.exceptionOrNull()

        return db.withWriteTransaction {
            val now = nowEpochMs()
            val device = devices.find(deviceId)

            if (publishError != null) {
                val removed = device == null
                commands.save(
                    CommandRecord(
                        commandId = commandId,
                        deviceId = deviceId,
                        action = action,
                        status = if (removed) CommandStatus.CANCELLED else CommandStatus.FAILED,
                        requestedAtEpochMs = requestedAtEpochMs,
                        failureReason = if (removed) "device_removed" else "mqtt_publish_failed",
                    ),
                )
                throw if (removed) {
                    ApiException(HttpStatusCode.Gone, "device_removed", "Device was removed")
                } else {
                    ApiException(
                        HttpStatusCode.ServiceUnavailable,
                        "gateway_unavailable",
                        "Failed to publish command to the gateway",
                    )
                }
            }

            if (device == null) {
                commands.save(
                    CommandRecord(
                        commandId = commandId,
                        deviceId = deviceId,
                        action = action,
                        status = CommandStatus.DISPATCHED,
                        requestedAtEpochMs = requestedAtEpochMs,
                        dispatchedAtEpochMs = now,
                    ),
                )
                throw ApiException(HttpStatusCode.Gone, "device_removed", "Device was removed")
            }

            val updated = device.copy(
                state = if (action == CommandAction.TURN_ON) DeviceState.ON else DeviceState.OFF,
                stateEvidence = StateEvidence.COMMANDED,
                lastAction = DeviceAction(
                    type = if (action == CommandAction.TURN_ON) {
                        ActionType.TURNED_ON
                    } else {
                        ActionType.TURNED_OFF
                    },
                    atEpochMs = now,
                    commandId = commandId,
                ),
                revision = device.revision + 1,
            )
            devices.update(updated)

            val record = CommandRecord(
                commandId = commandId,
                deviceId = deviceId,
                action = action,
                status = CommandStatus.DISPATCHED,
                requestedAtEpochMs = requestedAtEpochMs,
                dispatchedAtEpochMs = now,
            )
            commands.save(record)
            CommandResult(CommandAccepted(record, updated), replayed = false)
        }
    }

    suspend fun purge(now: Long) {
        commands.purgeIdempotency(now - IDEMPOTENCY_TTL_MS)
    }

    private suspend fun removed(deviceId: String): ApiException {
        val tombstone = devices.findTombstone(deviceId)
        return if (tombstone != null) deviceRemoved(tombstone) else deviceNotFound()
    }

    companion object {
        private const val IDEMPOTENCY_TTL_MS = 10 * 60 * 1000L
    }
}
