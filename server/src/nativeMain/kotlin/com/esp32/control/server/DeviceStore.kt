package com.esp32.control.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory demo store. It intentionally contains no MQTT dependency yet: this
 * module verifies the Ktor + Kotlin/Native toolchain and gives both GUIs a real
 * HTTP API to talk to. The next step is to replace the demo mutations with an
 * MQTT command publisher connected to mock/.
 */
class DeviceStore {
    private val mutex = Mutex()
    private val devices = mutableListOf(
        Device(
            id = "aerator-1",
            name = "1 号增氧机",
            location = "东侧鱼塘",
            on = false,
            online = true,
            codeOn = 1_714_739,
            codeOff = 1_714_740,
            lastAction = "待机",
        ),
        Device(
            id = "aerator-2",
            name = "2 号增氧机",
            location = "西侧鱼塘",
            on = true,
            online = true,
            codeOn = 1_714_741,
            codeOff = 1_714_742,
            lastAction = "已开启",
        ),
    )

    suspend fun list(): List<Device> = mutex.withLock { devices.toList() }

    suspend fun get(id: String): Device? = mutex.withLock {
        devices.firstOrNull { it.id == id }
    }

    suspend fun toggle(id: String): Device? = mutex.withLock {
        val index = devices.indexOfFirst { it.id == id }
        if (index < 0) {
            return@withLock null
        }
        val current = devices[index]
        val updated = current.copy(
            on = !current.on,
            lastAction = if (current.on) "已关闭" else "已开启",
        )
        devices[index] = updated
        updated
    }
}
