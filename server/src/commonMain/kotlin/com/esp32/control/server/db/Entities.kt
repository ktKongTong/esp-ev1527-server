package com.esp32.control.server.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val location: String,
    val state: String,
    val stateEvidence: String,
    val online: Boolean,
    val codeOn: Long,
    val codeOff: Long,
    val rfProtocol: Int,
    val rfLength: Int,
    val rfRepeat: Int,
    val lastActionType: String?,
    val lastActionAtEpochMs: Long?,
    val lastActionCommandId: String?,
    val revision: Long,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "device_tombstones")
data class DeviceTombstoneEntity(
    @PrimaryKey val deviceId: String,
    val removedAtEpochMs: Long,
    val revision: Long,
)

@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey val commandId: String,
    val deviceId: String,
    val action: String,
    val status: String,
    val requestedAtEpochMs: Long,
    val dispatchedAtEpochMs: Long?,
    val failureReason: String?,
)

@Entity(tableName = "idempotency")
data class IdempotencyEntity(
    @PrimaryKey val idempotencyKey: String,
    val commandId: String,
    val deviceId: String,
    val action: String,
    val createdAtEpochMs: Long,
)

