package com.esp32.control.server.domain

import androidx.room3.withWriteTransaction
import com.esp32.control.contract.Device
import com.esp32.control.contract.DeviceListResponse
import com.esp32.control.contract.UpdateDeviceRequest
import com.esp32.control.server.data.DeviceRepository
import com.esp32.control.server.db.AppDatabase
import com.esp32.control.server.deviceNotFound
import com.esp32.control.server.deviceRemoved
import com.esp32.control.server.invalidRequest
import com.esp32.control.server.nowEpochMs
import com.esp32.control.server.requireDeviceId

class DeviceService(
    private val db: AppDatabase,
    private val devices: DeviceRepository,
) {
    suspend fun list(): DeviceListResponse = DeviceListResponse(
        devices = devices.list(),
        serverTimeEpochMs = nowEpochMs(),
    )

    suspend fun get(deviceId: String): Device {
        requireDeviceId(deviceId)
        devices.find(deviceId)?.let { return it }
        devices.findTombstone(deviceId)?.let { throw deviceRemoved(it) }
        throw deviceNotFound()
    }

    /**
     * Updates the editable labels only. RF codes and parameters stay owned by
     * the learned pair, so editing never touches the code registry.
     */
    suspend fun update(deviceId: String, request: UpdateDeviceRequest): Device {
        requireDeviceId(deviceId)
        if (request.name == null && request.location == null) {
            throw invalidRequest("name or location is required")
        }
        val requestedName = request.name?.let { normalizeDeviceLabel(it) }
        val requestedLocation = request.location?.let { normalizeDeviceLabel(it) }
        if ((request.name != null && requestedName == null) ||
            (request.location != null && requestedLocation == null)
        ) {
            throw invalidRequest("name and location must be 1-64 characters")
        }

        return db.withWriteTransaction {
            val current = devices.find(deviceId)
            if (current == null) {
                devices.findTombstone(deviceId)?.let { throw deviceRemoved(it) }
                throw deviceNotFound()
            }
            val updated = current.copy(
                name = requestedName ?: current.name,
                location = requestedLocation ?: current.location,
            )
            devices.updateLabels(updated.id, updated.name, updated.location)
            updated
        }
    }

    /**
     * Removes the device and leaves a tombstone so later reads can return 410.
     * A command that has not reached the publish step yet sees the missing
     * device and is recorded as cancelled.
     */
    suspend fun delete(deviceId: String): Boolean {
        requireDeviceId(deviceId)
        return db.withWriteTransaction {
            val device = devices.find(deviceId)
            if (device == null) {
                if (devices.findTombstone(deviceId) != null) {
                    return@withWriteTransaction false
                }
                throw deviceNotFound()
            }

            devices.delete(deviceId)
            devices.addTombstone(
                deviceId = deviceId,
                removedAtEpochMs = nowEpochMs(),
            )
            true
        }
    }

    suspend fun purge(now: Long) {
        devices.purgeTombstones(now - TOMBSTONE_TTL_MS)
    }

    companion object {
        private const val TOMBSTONE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
