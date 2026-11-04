package com.esp32.control.server.data

import com.esp32.control.contract.CommandAction

data class DeviceTombstone(
    val deviceId: String,
    val removedAtEpochMs: Long,
    val revision: Long,
)

data class IdempotencyRecord(
    val idempotencyKey: String,
    val commandId: String,
    val deviceId: String,
    val action: CommandAction,
    val createdAtEpochMs: Long,
)
