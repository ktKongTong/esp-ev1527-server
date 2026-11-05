package com.esp32.control.server.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val location: String,
    val codeOn: Long,
    val codeOff: Long,
    val rfProtocol: Int,
    val rfLength: Int,
    val rfRepeat: Int,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "device_tombstones")
data class DeviceTombstoneEntity(
    @PrimaryKey val deviceId: String,
    val removedAtEpochMs: Long,
)

/**
 * Short-lived dedupe state only. A null [dispatchedAtEpochMs] means the
 * original request reserved the key and has not finished publishing yet.
 */
@Entity(tableName = "idempotency")
data class IdempotencyEntity(
    @PrimaryKey val idempotencyKey: String,
    val deviceId: String,
    val action: String,
    val createdAtEpochMs: Long,
    val dispatchedAtEpochMs: Long?,
)

