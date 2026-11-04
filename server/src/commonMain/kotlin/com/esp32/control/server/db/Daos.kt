package com.esp32.control.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY createdAtEpochMs ASC")
    suspend fun list(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun find(id: String): DeviceEntity?

    /**
     * Returns the device that already owns this RF code in either slot.
     */
    @Query(
        """
        SELECT id FROM devices
        WHERE rfProtocol = :protocol AND rfLength = :length
          AND (codeOn = :value OR codeOff = :value)
        LIMIT 1
        """,
    )
    suspend fun findOwner(protocol: Int, length: Int, value: Long): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(device: DeviceEntity)

    @Update
    suspend fun update(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE devices SET online = :online, revision = revision + 1 WHERE online != :online")
    suspend fun setOnline(online: Boolean)
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
interface CommandDao {
    @Query("SELECT * FROM commands WHERE commandId = :commandId")
    suspend fun find(commandId: String): CommandEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(command: CommandEntity)
}

@Dao
interface IdempotencyDao {
    @Query("SELECT * FROM idempotency WHERE idempotencyKey = :key")
    suspend fun find(key: String): IdempotencyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: IdempotencyEntity)

    @Query("DELETE FROM idempotency WHERE createdAtEpochMs < :cutoffEpochMs")
    suspend fun purge(cutoffEpochMs: Long)
}

