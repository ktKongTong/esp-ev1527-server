package com.esp32.control.gui.data

import com.esp32.control.contract.Device
import com.esp32.control.contract.Group
import com.esp32.control.contract.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Non-persistent store for the web build and tests. */
internal class InMemoryLocalStore : LocalStore {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    private var cachedUrl: String? = null

    override val devices: Flow<List<Device>> = _devices.asStateFlow()
    override val groups: Flow<List<Group>> = _groups.asStateFlow()
    override val schedules: Flow<List<Schedule>> = _schedules.asStateFlow()
    override val lastSyncedAtEpochMs: Flow<Long?> = _lastSyncedAt.asStateFlow()

    override suspend fun cachedServerUrl(): String? = cachedUrl

    override suspend fun schedule(scheduleId: String): Schedule? =
        _schedules.value.firstOrNull { it.id == scheduleId }

    override suspend fun replaceAll(
        serverUrl: String,
        devices: List<Device>,
        groups: List<Group>,
        schedules: List<Schedule>,
        syncedAtEpochMs: Long,
    ) {
        _devices.value = devices
        _groups.value = groups
        _schedules.value = schedules
        _lastSyncedAt.value = syncedAtEpochMs
        cachedUrl = serverUrl
    }

    override suspend fun upsertDevice(device: Device) {
        _devices.update { replaceById(it, device.id, Device::id, device) }
    }

    override suspend fun upsertGroup(group: Group) {
        _groups.update { replaceById(it, group.id, Group::id, group) }
    }

    override suspend fun upsertSchedule(schedule: Schedule) {
        _schedules.update { replaceById(it, schedule.id, Schedule::id, schedule) }
    }

    override suspend fun deleteDevice(deviceId: String) {
        _devices.update { items -> items.filterNot { it.id == deviceId } }
    }

    override suspend fun deleteGroup(groupId: String) {
        _groups.update { items -> items.filterNot { it.id == groupId } }
    }

    override suspend fun deleteSchedule(scheduleId: String) {
        _schedules.update { items -> items.filterNot { it.id == scheduleId } }
    }

    override suspend fun clear() {
        _devices.value = emptyList()
        _groups.value = emptyList()
        _schedules.value = emptyList()
        _lastSyncedAt.value = null
        cachedUrl = null
    }

    private fun <T> replaceById(
        items: List<T>,
        id: String,
        idOf: (T) -> String,
        value: T,
    ): List<T> {
        val index = items.indexOfFirst { idOf(it) == id }
        if (index < 0) return items + value
        return items.toMutableList().also { it[index] = value }
    }
}
