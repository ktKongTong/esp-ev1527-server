package com.esp32.control.server.data

import com.esp32.control.contract.Device
import com.esp32.control.server.db.AppDatabase
import com.esp32.control.server.db.DeviceTombstoneEntity

/**
 * Device persistence. Methods do not open transactions, so callers can group
 * related reads and writes with RoomDatabase.withWriteTransaction.
 */
class DeviceRepository(db: AppDatabase) {
    private val devices = db.deviceDao()
    private val tombstones = db.tombstoneDao()

    suspend fun list(): List<Device> = devices.list().map { it.toModel() }

    suspend fun find(id: String): Device? = devices.find(id)?.toModel()

    suspend fun findTombstone(id: String): DeviceTombstone? =
        tombstones.find(id)?.let {
            DeviceTombstone(
                deviceId = it.deviceId,
                removedAtEpochMs = it.removedAtEpochMs,
            )
        }

    suspend fun findOwner(length: Int, value: Long): String? =
        devices.findOwner(length, value)

    suspend fun insert(device: Device) {
        devices.insert(device.toEntity())
    }

    suspend fun delete(id: String) {
        devices.delete(id)
    }

    suspend fun addTombstone(deviceId: String, removedAtEpochMs: Long) {
        tombstones.insert(DeviceTombstoneEntity(deviceId, removedAtEpochMs))
    }

    suspend fun purgeTombstones(cutoffEpochMs: Long) {
        tombstones.purge(cutoffEpochMs)
    }
}
