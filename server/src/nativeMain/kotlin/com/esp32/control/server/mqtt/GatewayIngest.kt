package com.esp32.control.server.mqtt

import com.esp32.control.contract.ContractJson
import com.esp32.control.contract.GatewayState
import com.esp32.control.server.Log
import com.esp32.control.server.ServerConfig
import com.esp32.control.server.domain.Ev1527
import com.esp32.control.server.domain.LearningService
import com.esp32.control.server.nowEpochMs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

class GatewayIngest(
    private val config: ServerConfig,
    private val gateway: GatewayStatusStore,
    private val learning: LearningService,
) {
    private val prefix = config.subscribedTopicFilter.removeSuffix("/#")

    suspend fun onDisconnected() {
        setGateway(GatewayState.OFFLINE, null)
    }

    suspend fun onMessage(topic: String, payload: ByteArray) {
        if (!topic.startsWith("$prefix/")) {
            Log.debug { "[ingest] ignored topic outside prefix $prefix: $topic" }
            return
        }
        val text = payload.decodeToString().trim()

        when {
            topic == "$prefix/LWT" -> {
                val state = text.trim('"')
                Log.info { "[ingest] gateway LWT=$state" }
                when (state) {
                    "online" -> setGateway(GatewayState.ONLINE, null)
                    "offline" -> setGateway(GatewayState.OFFLINE, null)
                }
            }

            topic == "$prefix/version" -> {
                val version = text.trim('"').takeIf { it.isNotEmpty() }
                Log.info { "[ingest] gateway version=${version ?: "<empty>"}" }
                setGateway(GatewayState.ONLINE, version)
            }

            topic.startsWith("$prefix/commands/") -> {
                Log.debug { "[ingest] ignore command echo on $topic" }
            }

            else -> parseCapture(topic, text)?.let { ingestCapture(it) }
        }
    }

    private suspend fun setGateway(state: GatewayState, version: String?) {
        if (!gateway.setGateway(state, version)) return

        if (state != GatewayState.ONLINE) {
            learning.onGatewayOffline(nowEpochMs())
        }
    }

    private suspend fun ingestCapture(capture: RfCapture) {
        val now = nowEpochMs()
        Log.debug {
            "[ingest] capture value=${capture.value} protocol=${capture.protocol} " +
                "length=${capture.length} delay=${capture.delayMs}ms"
        }
        gateway.onCapture(now)
        learning.onCapture(capture, now)
    }

    private fun parseCapture(topic: String, text: String): RfCapture? {
        val payload = runCatching {
            ContractJson.decodeFromString<GatewayCapturePayload>(text)
        }.getOrElse { cause ->
            Log.debug {
                "[ingest] drop $topic: not a capture payload (${cause.message}): ${text.take(240)}"
            }
            return null
        }
        if (!Ev1527.acceptsLength(payload.length)) {
            Log.debug {
                "[ingest] drop $topic: length=${payload.length}, " +
                    "EV1527 expects ${Ev1527.LENGTH} bits"
            }
            return null
        }
        if (!Ev1527.isValid(payload.value)) {
            Log.debug {
                "[ingest] drop $topic: value=${payload.value} outside " +
                    "0..${Ev1527.MAX_VALUE}"
            }
            return null
        }
        return RfCapture(
            value = payload.value,
            protocol = payload.protocol,
            length = payload.length,
            delayMs = payload.delay.coerceAtLeast(0),
        )
    }

    @Serializable
    private data class GatewayCapturePayload(
        val value: Long,
        val protocol: Int,
        val length: Int,
        val delay: Int = 330,
    )
}
