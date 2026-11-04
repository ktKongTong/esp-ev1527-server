package com.esp32.control.server.data

import com.esp32.control.contract.CommandRecord
import com.esp32.control.server.db.AppDatabase

/**
 * Command and idempotency persistence. Methods do not open transactions.
 */
class CommandRepository(db: AppDatabase) {
    private val commands = db.commandDao()
    private val idempotency = db.idempotencyDao()

    suspend fun find(commandId: String): CommandRecord? =
        commands.find(commandId)?.toModel()

    suspend fun save(command: CommandRecord) {
        commands.upsert(command.toEntity())
    }

    suspend fun findIdempotency(key: String): IdempotencyRecord? =
        idempotency.find(key)?.toRecord()

    suspend fun saveIdempotency(entry: IdempotencyRecord) {
        idempotency.insert(entry.toEntity())
    }

    suspend fun purgeIdempotency(cutoffEpochMs: Long) {
        idempotency.purge(cutoffEpochMs)
    }
}
