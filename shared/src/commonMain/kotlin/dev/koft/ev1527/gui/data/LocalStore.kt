package dev.koft.ev1527.gui.data

import dev.koft.ev1527.contract.Device
import dev.koft.ev1527.contract.Group
import dev.koft.ev1527.contract.Schedule
import kotlinx.coroutines.flow.Flow

/**
 * Read model for the UI. The store is a disposable cache of server state:
 * refresh replaces it, and writes update it only after the server confirms.
 */
internal interface LocalStore {
    val devices: Flow<List<Device>>
    val groups: Flow<List<Group>>
    val schedules: Flow<List<Schedule>>
    val lastSyncedAtEpochMs: Flow<Long?>

    suspend fun cachedServerUrl(): String?

    suspend fun schedule(scheduleId: String): Schedule?

    suspend fun replaceAll(
        serverUrl: String,
        devices: List<Device>,
        groups: List<Group>,
        schedules: List<Schedule>,
        syncedAtEpochMs: Long,
    )

    suspend fun upsertDevice(device: Device)
    suspend fun upsertGroup(group: Group)
    suspend fun upsertSchedule(schedule: Schedule)

    suspend fun deleteDevice(deviceId: String)
    suspend fun deleteGroup(groupId: String)
    suspend fun deleteSchedule(scheduleId: String)

    suspend fun clear()
}
