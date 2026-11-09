package com.esp32.control.gui.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.esp32.control.contract.Device
import com.esp32.control.contract.Group
import com.esp32.control.contract.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomLocalStore(
    private val dao: CacheDao,
) : LocalStore {
    override val devices: Flow<List<Device>> =
        dao.observeDevices().map { items -> items.map(DeviceEntity::toModel) }

    override val groups: Flow<List<Group>> =
        dao.observeGroups().map { items -> items.map(GroupEntity::toModel) }

    override val schedules: Flow<List<Schedule>> =
        dao.observeSchedules().map { items -> items.map(ScheduleEntity::toModel) }

    override val lastSyncedAtEpochMs: Flow<Long?> =
        dao.observeMeta().map { meta -> meta?.lastSyncedAtEpochMs }

    override suspend fun cachedServerUrl(): String? = dao.getMeta()?.serverUrl

    override suspend fun schedule(scheduleId: String): Schedule? =
        dao.getSchedule(scheduleId)?.toModel()

    override suspend fun replaceAll(
        serverUrl: String,
        devices: List<Device>,
        groups: List<Group>,
        schedules: List<Schedule>,
        syncedAtEpochMs: Long,
    ) {
        dao.replaceAll(
            serverUrl = serverUrl,
            devices = devices.map(Device::toEntity),
            groups = groups.map(Group::toEntity),
            schedules = schedules.map(Schedule::toEntity),
            syncedAtEpochMs = syncedAtEpochMs,
        )
    }

    override suspend fun upsertDevice(device: Device) = dao.upsertDevice(device.toEntity())

    override suspend fun upsertGroup(group: Group) = dao.upsertGroup(group.toEntity())

    override suspend fun upsertSchedule(schedule: Schedule) =
        dao.upsertSchedule(schedule.toEntity())

    override suspend fun deleteDevice(deviceId: String) = dao.deleteDevice(deviceId)

    override suspend fun deleteGroup(groupId: String) = dao.deleteGroup(groupId)

    override suspend fun deleteSchedule(scheduleId: String) = dao.deleteSchedule(scheduleId)

    override suspend fun clear() = dao.clear()
}

internal fun createRoomLocalStore(context: Context): LocalStore = RoomLocalStore(
    Room.databaseBuilder<AppDatabase>(context, "esp32-control-cache.db")
        .setDriver(BundledSQLiteDriver())
        // The file is a disposable cache; a schema change can drop it.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
        .cacheDao(),
)
