package com.esp32.control.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

const val SERVER_VERSION: String = "0.2.0"

data class MqttConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val clientId: String,
    val topicPrefix: String,
    val keepAliveSeconds: Int = 60,
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val mqtt: MqttConfig,
    val databasePath: String,
    val gatewayClientId: String,
    val commandTopic: String,
    val subscribedTopicFilter: String,
) {
    companion object {
        fun fromEnvironment(): ServerConfig {
            val host = env("SERVER_HOST") ?: "0.0.0.0"
            val port = env("SERVER_PORT")?.toIntOrNull() ?: 8080
            val topicPrefix = env("MQTT_TOPIC_PREFIX") ?: "home/OpenMQTTGateway"
            val mqttHost = env("MQTT_HOST")?.takeIf { it.isNotBlank() }
                ?: error("MQTT_HOST is required; there is no offline fallback")
            val mqttPort = env("MQTT_PORT")?.toIntOrNull() ?: 1883

            val mqtt = MqttConfig(
                host = mqttHost,
                port = mqttPort,
                username = env("MQTT_USERNAME")?.takeIf { it.isNotBlank() },
                password = env("MQTT_PASSWORD")?.takeIf { it.isNotBlank() },
                clientId = env("MQTT_CLIENT_ID") ?: newId("esp32-control-server"),
                topicPrefix = topicPrefix,
            )

            return ServerConfig(
                host = host,
                port = port,
                mqtt = mqtt,
                databasePath = env("DATABASE_PATH") ?: "esp32-control.db",
                gatewayClientId = env("GATEWAY_CLIENT_ID") ?: "OpenMQTTGateway_ESP32_315M",
                commandTopic = "$topicPrefix/commands/MQTTto315",
                subscribedTopicFilter = "$topicPrefix/#",
            )
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun env(name: String): String? =
            kotlinx.cinterop.memScoped {
                platform.posix.getenv(name)?.toKString()
            }
    }
}
