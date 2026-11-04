package com.esp32.control.server.mqtt

import com.esp32.control.contract.ContractJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class RfPayload(
    val value: Long,
    val protocol: Int,
    val length: Int,
    val repeat: Int,
)

data class RfCapture(
    val value: Long,
    val protocol: Int,
    val length: Int,
    val delayMs: Int,
)

fun interface RfTransmitter {
    suspend fun send(payload: RfPayload)
}

class MqttRfTransmitter(
    private val transport: MqttTransport,
    private val commandTopic: String,
) : RfTransmitter {
    override suspend fun send(payload: RfPayload) {
        transport.publish(
            commandTopic,
            ContractJson.encodeToString(payload).encodeToByteArray(),
        )
    }
}
