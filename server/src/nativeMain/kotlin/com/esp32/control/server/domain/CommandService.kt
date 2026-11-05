package com.esp32.control.server.domain

import androidx.room3.withWriteTransaction
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.CommandDispatched
import com.esp32.control.server.ApiException
import com.esp32.control.server.data.DeviceRepository
import com.esp32.control.server.data.IdempotencyRecord
import com.esp32.control.server.data.IdempotencyRepository
import com.esp32.control.server.db.AppDatabase
import com.esp32.control.server.deviceNotFound
import com.esp32.control.server.deviceRemoved
import com.esp32.control.server.mqtt.GatewayStatusStore
import com.esp32.control.server.mqtt.RfPayload
import com.esp32.control.server.mqtt.RfTransmitter
import com.esp32.control.server.nowEpochMs
import com.esp32.control.server.requireDeviceId
import io.ktor.http.HttpStatusCode

data class CommandDispatchResult(
    val dispatched: CommandDispatched,
    val replayed: Boolean,
)

/**
 * A command publishes one RF code and stops there. The 315MHz link has no
 * acknowledgement, so the server records nothing about the actuator state.
 */
class CommandService(
    private val db: AppDatabase,
    private val devices: DeviceRepository,
    private val idempotency: IdempotencyRepository,
    private val gateway: GatewayStatusStore,
    private val transmitter: RfTransmitter,
) {
    suspend fun issue(
        deviceId: String,
        action: CommandAction,
        idempotencyKey: String,
    ): CommandDispatchResult {
        requireDeviceId(deviceId)
        if (idempotencyKey.isBlank() || idempotencyKey.length > 64) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Idempotency-Key must be 1-64 characters",
            )
        }

        var payload: RfPayload? = null
        var replay: CommandDispatchResult? = null

        db.withWriteTransaction {
            val now = nowEpochMs()
            purge(now)

            val existing = idempotency.find(idempotencyKey)
            if (existing != null) {
                if (existing.deviceId != deviceId || existing.action != action) {
                    throw ApiException(
                        HttpStatusCode.Conflict,
                        "idempotency_conflict",
                        "Idempotency-Key was already used for a different request",
                    )
                }
                val dispatchedAt = existing.dispatchedAtEpochMs ?: throw ApiException(
                    HttpStatusCode.Conflict,
                    "command_in_progress",
                    "The original command is still being dispatched",
                )
                devices.find(deviceId) ?: throw removed(deviceId)
                replay = CommandDispatchResult(
                    dispatched = CommandDispatched(
                        deviceId = deviceId,
                        action = action,
                        dispatchedAtEpochMs = dispatchedAt,
                    ),
                    replayed = true,
                )
                return@withWriteTransaction
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

            payload = RfPayload(
                value = if (action == CommandAction.TURN_ON) device.codeOn else device.codeOff,
                protocol = device.rf.protocol,
                length = device.rf.length,
                repeat = device.rf.repeat,
            )
            idempotency.reserve(
                IdempotencyRecord(
                    idempotencyKey = idempotencyKey,
                    deviceId = deviceId,
                    action = action,
                    createdAtEpochMs = now,
                    dispatchedAtEpochMs = null,
                ),
            )
        }

        replay?.let { return it }
        val spec = payload ?: error("missing publish payload")

        // Do not publish for a device that was deleted after validation.
        val mayPublish = db.withWriteTransaction {
            devices.find(deviceId) != null
        }
        if (!mayPublish) {
            db.withWriteTransaction { idempotency.release(idempotencyKey) }
            throw ApiException(HttpStatusCode.Gone, "device_removed", "Device was removed")
        }

        val publishError = runCatching { transmitter.send(spec) }.exceptionOrNull()
        if (publishError != null) {
            // Release the key so the client can retry after the gateway returns.
            db.withWriteTransaction { idempotency.release(idempotencyKey) }
            throw ApiException(
                HttpStatusCode.ServiceUnavailable,
                "gateway_unavailable",
                "Failed to publish command to the gateway",
            )
        }

        val dispatchedAt = db.withWriteTransaction {
            val now = nowEpochMs()
            idempotency.markDispatched(idempotencyKey, now)
            now
        }

        return CommandDispatchResult(
            dispatched = CommandDispatched(
                deviceId = deviceId,
                action = action,
                dispatchedAtEpochMs = dispatchedAt,
            ),
            replayed = false,
        )
    }

    suspend fun purge(now: Long) {
        idempotency.purge(now - IDEMPOTENCY_TTL_MS)
    }

    private suspend fun removed(deviceId: String): ApiException {
        val tombstone = devices.findTombstone(deviceId)
        return if (tombstone != null) deviceRemoved(tombstone) else deviceNotFound()
    }

    companion object {
        private const val IDEMPOTENCY_TTL_MS = 10 * 60 * 1000L
    }
}
