package com.esp32.control.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Flat SSE event envelope. The polymorphic discriminator is `type`; each
 * concrete event carries the contract's additional fields at the top level.
 */
@Serializable
sealed class ServerEvent {
    abstract val eventId: String
    abstract val atEpochMs: Long
}

@Serializable
@SerialName("gateway.updated")
data class GatewayUpdatedEvent(
    override val eventId: String,
    override val atEpochMs: Long,
    val gateway: GatewayStatus,
) : ServerEvent()

@Serializable
@SerialName("device.added")
data class DeviceAddedEvent(
    override val eventId: String,
    override val atEpochMs: Long,
    val device: Device,
) : ServerEvent()

@Serializable
@SerialName("device.updated")
data class DeviceUpdatedEvent(
    override val eventId: String,
    override val atEpochMs: Long,
    val device: Device,
) : ServerEvent()

@Serializable
@SerialName("device.removed")
data class DeviceRemovedEvent(
    override val eventId: String,
    override val atEpochMs: Long,
    val deviceId: String,
    val removedAtEpochMs: Long,
    val revision: Long,
) : ServerEvent()

@Serializable
@SerialName("command.updated")
data class CommandUpdatedEvent(
    override val eventId: String,
    override val atEpochMs: Long,
    val command: CommandRecord,
) : ServerEvent()

@Serializable
@SerialName("learning.updated")
data class LearningUpdatedEvent(
    override val eventId: String,
    override val atEpochMs: Long,
    val session: LearningSession,
) : ServerEvent()

@Serializable
@SerialName("stream.reset")
data class StreamResetEvent(
    override val eventId: String,
    override val atEpochMs: Long,
    val reason: String,
) : ServerEvent()

/** SSE `event:` field. Must match the JSON discriminator value. */
val ServerEvent.type: String
    get() = when (this) {
        is GatewayUpdatedEvent -> "gateway.updated"
        is DeviceAddedEvent -> "device.added"
        is DeviceUpdatedEvent -> "device.updated"
        is DeviceRemovedEvent -> "device.removed"
        is CommandUpdatedEvent -> "command.updated"
        is LearningUpdatedEvent -> "learning.updated"
        is StreamResetEvent -> "stream.reset"
    }
