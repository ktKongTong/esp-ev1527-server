package dev.koft.ev1527.gui.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class CacheDao {
    @Query("SELECT * FROM devices ORDER BY createdAtEpochMs, id")
    abstract fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM device_groups ORDER BY createdAtEpochMs, id")
    abstract fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM schedules ORDER BY createdAtEpochMs, id")
    abstract fun observeSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM cache_meta WHERE id = 1")
    abstract fun observeMeta(): Flow<CacheMetaEntity?>

    @Query("SELECT * FROM cache_meta WHERE id = 1")
    abstract suspend fun getMeta(): CacheMetaEntity?

    @Query("SELECT * FROM schedules WHERE id = :id")
    abstract suspend fun getSchedule(id: String): ScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDevices(items: List<DeviceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertGroups(items: List<GroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSchedules(items: List<ScheduleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDevice(item: DeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertGroup(item: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSchedule(item: ScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMeta(item: CacheMetaEntity)

    @Query("DELETE FROM devices")
    abstract suspend fun deleteAllDevices()

    @Query("DELETE FROM device_groups")
    abstract suspend fun deleteAllGroups()

    @Query("DELETE FROM schedules")
    abstract suspend fun deleteAllSchedules()

    @Query("DELETE FROM cache_meta")
    abstract suspend fun deleteMeta()

    @Query("DELETE FROM devices WHERE id = :id")
    abstract suspend fun deleteDevice(id: String)

    @Query("DELETE FROM device_groups WHERE id = :id")
    abstract suspend fun deleteGroup(id: String)

    @Query("DELETE FROM schedules WHERE id = :id")
    abstract suspend fun deleteSchedule(id: String)

    @Transaction
    open suspend fun replaceAll(
        devices: List<DeviceEntity>,
        groups: List<GroupEntity>,
        schedules: List<ScheduleEntity>,
        serverUrl: String,
        syncedAtEpochMs: Long,
    ) {
        deleteAllDevices()
        deleteAllGroups()
        deleteAllSchedules()
        insertDevices(devices)
        insertGroups(groups)
        insertSchedules(schedules)
        upsertMeta(
            CacheMetaEntity(
                serverUrl = serverUrl,
                lastSyncedAtEpochMs = syncedAtEpochMs,
            ),
        )
    }

    @Transaction
    open suspend fun clear() {
        deleteAllDevices()
        deleteAllGroups()
        deleteAllSchedules()
        deleteMeta()
    }
}
