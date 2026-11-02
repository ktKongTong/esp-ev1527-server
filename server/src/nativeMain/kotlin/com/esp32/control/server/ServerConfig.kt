package com.esp32.control.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

const val SERVER_VERSION: String = "0.1.0"

data class MqttConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val topicPrefix: String,
    val keepAliveSeconds: Int = 60,
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val mqtt: MqttConfig?,
    val gatewayClientId: String,
    val commandTopic: String,
    val subscribedTopicFilter: String,
    val dataSource: com.esp32.control.contract.DataSource,
) {
    companion object {
        fun fromEnvironment(): ServerConfig {
            val host = env("SERVER_HOST") ?: "0.0.0.0"
            val port = env("SERVER_PORT")?.toIntOrNull() ?: 8080
            val topicPrefix = env("MQTT_TOPIC_PREFIX") ?: "home/OpenMQTTGateway"
            val mqttHost = env("MQTT_HOST")
            val mqttPort = env("MQTT_PORT")?.toIntOrNull() ?: 1883

            val mqtt = if (mqttHost.isNullOrBlank()) {
                null
            } else {
                MqttConfig(
                    host = mqttHost,
                    port = mqttPort,
                    username = env("MQTT_USERNAME")?.takeIf { it.isNotBlank() },
                    password = env("MQTT_PASSWORD")?.takeIf { it.isNotBlank() },
                    topicPrefix = topicPrefix,
                )
            }

            return ServerConfig(
                host = host,
                port = port,
                mqtt = mqtt,
                gatewayClientId = env("GATEWAY_CLIENT_ID") ?: "OpenMQTTGateway_ESP32_315M",
                commandTopic = "$topicPrefix/commands/MQTTto315",
                subscribedTopicFilter = "$topicPrefix/#",
                dataSource = if (mqtt == null) {
                    com.esp32.control.contract.DataSource.IN_MEMORY_DEMO
                } else {
                    com.esp32.control.contract.DataSource.MQTT
                },
            )
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun env(name: String): String? =
            kotlinx.cinterop.memScoped {
                platform.posix.getenv(name)?.toKString()
            }
    }
}
