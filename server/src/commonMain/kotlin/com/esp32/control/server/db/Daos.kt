package com.esp32.control.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY createdAtEpochMs ASC")
    suspend fun list(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun find(id: String): DeviceEntity?

    /**
     * Returns the device that already owns this RF code in either slot.
     * The gateway may report different protocol numbers for the same physical
     * code, so code identity uses length and value only.
     */
    @Query(
        """
        SELECT id FROM devices
        WHERE rfLength = :length
          AND (codeOn = :value OR codeOff = :value)
        LIMIT 1
        """,
    )
    suspend fun findOwner(length: Int, value: Long): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM device_tombstones WHERE deviceId = :deviceId")
    suspend fun find(deviceId: String): DeviceTombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: DeviceTombstoneEntity)

    @Query("DELETE FROM device_tombstones WHERE removedAtEpochMs < :cutoffEpochMs")
    suspend fun purge(cutoffEpochMs: Long)
}

@Dao
interface IdempotencyDao {
    @Query("SELECT * FROM idempotency WHERE idempotencyKey = :key")
    suspend fun find(key: String): IdempotencyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: IdempotencyEntity)

    @Query("UPDATE idempotency SET dispatchedAtEpochMs = :atEpochMs WHERE idempotencyKey = :key")
    suspend fun markDispatched(key: String, atEpochMs: Long)

    @Query("DELETE FROM idempotency WHERE idempotencyKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM idempotency WHERE createdAtEpochMs < :cutoffEpochMs")
    suspend fun purge(cutoffEpochMs: Long)
}

