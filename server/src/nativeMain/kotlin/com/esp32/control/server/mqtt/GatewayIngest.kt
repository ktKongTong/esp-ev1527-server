package com.esp32.control.server.mqtt

import com.esp32.control.contract.ContractJson
import com.esp32.control.contract.GatewayState
import com.esp32.control.server.ServerConfig
import com.esp32.control.server.data.DeviceRepository
import com.esp32.control.server.domain.LearningService
import com.esp32.control.server.nowEpochMs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

class GatewayIngest(
    private val config: ServerConfig,
    private val gateway: GatewayStatusStore,
    private val devices: DeviceRepository,
    private val learning: LearningService,
) {
    private val prefix = config.subscribedTopicFilter.removeSuffix("/#")

    suspend fun onDisconnected() {
        setGateway(GatewayState.OFFLINE, null)
    }

    suspend fun onMessage(topic: String, payload: ByteArray) {
        if (!topic.startsWith("$prefix/")) return
        val text = payload.decodeToString().trim()

        when {
            topic == "$prefix/LWT" -> when (text.trim('"')) {
                "online" -> setGateway(GatewayState.ONLINE, null)
                "offline" -> setGateway(GatewayState.OFFLINE, null)
            }

            topic == "$prefix/version" -> {
                val version = text.trim('"').takeIf { it.isNotEmpty() }
                setGateway(GatewayState.ONLINE, version)
            }

            topic.startsWith("$prefix/commands/") -> Unit

            else -> parseCapture(text)?.let { ingestCapture(it) }
        }
    }

    private suspend fun setGateway(state: GatewayState, version: String?) {
        if (!gateway.setGateway(state, version)) return

        devices.setOnline(state == GatewayState.ONLINE)
        if (state != GatewayState.ONLINE) {
            learning.onGatewayOffline(nowEpochMs())
        }
    }

    private suspend fun ingestCapture(capture: RfCapture) {
        val now = nowEpochMs()
        gateway.onCapture(now)
        learning.onCapture(capture, now)
    }

    private fun parseCapture(text: String): RfCapture? {
        val payload = runCatching {
            ContractJson.decodeFromString<GatewayCapturePayload>(text)
        }.getOrNull() ?: return null
        if (payload.protocol <= 0) return null
        if (payload.length !in 1..32) return null
        if (!validCode(payload.value, payload.length)) return null
        return RfCapture(
            value = payload.value,
            protocol = payload.protocol,
            length = payload.length,
            delayMs = payload.delay.coerceAtLeast(0),
        )
    }

    private fun validCode(value: Long, length: Int): Boolean {
        if (length !in 1..32) return false
        if (value < 0) return false
        return value < (1L shl length)
    }

    @Serializable
    private data class GatewayCapturePayload(
        val value: Long,
        val protocol: Int,
        val length: Int,
        val delay: Int = 330,
    )
}
