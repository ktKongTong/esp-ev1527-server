package com.esp32.control.server.data

import com.esp32.control.server.db.AppDatabase

/**
 * Idempotency dedupe persistence. These rows are not command history; they
 * exist only long enough to stop a client retry from publishing twice.
 */
class IdempotencyRepository(db: AppDatabase) {
    private val idempotency = db.idempotencyDao()

    suspend fun find(key: String): IdempotencyRecord? =
        idempotency.find(key)?.toRecord()

    suspend fun reserve(entry: IdempotencyRecord) {
        idempotency.insert(entry.toEntity())
    }

    suspend fun markDispatched(key: String, atEpochMs: Long) {
        idempotency.markDispatched(key, atEpochMs)
    }

    suspend fun release(key: String) {
        idempotency.delete(key)
    }

    suspend fun purge(cutoffEpochMs: Long) {
        idempotency.purge(cutoffEpochMs)
    }
}
