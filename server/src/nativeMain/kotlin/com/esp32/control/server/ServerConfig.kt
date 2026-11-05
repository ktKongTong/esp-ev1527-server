package com.esp32.control.server

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

const val SERVER_VERSION: String = "0.2.0"

enum class MqttTransportType { TCP, WEBSOCKET }

data class MqttConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val clientId: String,
    val topicPrefix: String,
    val keepAliveSeconds: Int = 60,
    val transport: MqttTransportType = MqttTransportType.TCP,
    val websocketPath: String = "/mqtt",
    val useTls: Boolean = false,
) {
    // Credentials must never reach logs or error messages.
    override fun toString(): String =
        "MqttConfig(host=$host, port=$port, " +
            "username=${if (username.isNullOrEmpty()) "<none>" else "***"}, " +
            "password=${if (password.isNullOrEmpty()) "<none>" else "***"}, " +
            "clientId=$clientId, topicPrefix=$topicPrefix, keepAliveSeconds=$keepAliveSeconds, " +
            "transport=$transport, websocketPath=$websocketPath, useTls=$useTls)"
}

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
            val file = loadConfigFile()
            // Process environment wins, so container deployments are unchanged.
            fun setting(name: String): String? =
                env(name)?.takeIf { it.isNotBlank() }
                    ?: file[name]?.takeIf { it.isNotBlank() }

            val host = setting("SERVER_HOST") ?: "0.0.0.0"
            val port = setting("SERVER_PORT")?.toIntOrNull() ?: 8080
            val topicPrefix = setting("MQTT_TOPIC_PREFIX") ?: "home/OpenMQTTGateway"
            val mqttHost = setting("MQTT_HOST")
                ?: error("MQTT_HOST is required; there is no offline fallback")
            val mqttPort = setting("MQTT_PORT")?.toIntOrNull() ?: 1883
            val transport = when (val raw = setting("MQTT_TRANSPORT")?.lowercase() ?: "tcp") {
                "tcp" -> MqttTransportType.TCP
                "websocket", "ws", "wss" -> MqttTransportType.WEBSOCKET
                else -> error("MQTT_TRANSPORT must be tcp or websocket, got: $raw")
            }
            val useTls = setting("MQTT_TLS")?.let { raw ->
                raw.toBooleanStrictOrNull()
                    ?: error("MQTT_TLS must be true or false, got: $raw")
            } ?: false
            if (transport == MqttTransportType.TCP && useTls) {
                error(
                    "MQTT_TLS requires MQTT_TRANSPORT=websocket: " +
                        "Ktor Native does not implement raw TLS sockets",
                )
            }

            val mqtt = MqttConfig(
                host = mqttHost,
                port = mqttPort,
                username = setting("MQTT_USERNAME"),
                password = setting("MQTT_PASSWORD"),
                clientId = setting("MQTT_CLIENT_ID") ?: newId("esp32-control-server"),
                topicPrefix = topicPrefix,
                transport = transport,
                websocketPath = setting("MQTT_WS_PATH") ?: "/mqtt",
                useTls = useTls,
            )

            println(mqtt)

            return ServerConfig(
                host = host,
                port = port,
                mqtt = mqtt,
                databasePath = setting("DATABASE_PATH") ?: "esp32-control.db",
                gatewayClientId = setting("GATEWAY_CLIENT_ID") ?: "OpenMQTTGateway_ESP32_315M",
                commandTopic = "$topicPrefix/commands/MQTTto433",
                subscribedTopicFilter = "$topicPrefix/#",
            )
        }

        /**
         * Reads KEY=VALUE lines from CONFIG_FILE, or from .env in the working
         * directory, or from server/.env when the process runs at repo root.
         * Real environment variables take precedence over file values.
         */
        @OptIn(ExperimentalForeignApi::class)
        private fun loadConfigFile(): Map<String, String> {
            env("CONFIG_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
                return readConfigFile(path) ?: error("CONFIG_FILE not found: $path")
            }
            return readConfigFile(".env")
                ?: readConfigFile("server/.env")
                ?: emptyMap()
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun readConfigFile(path: String): Map<String, String>? {
            val file = fopen(path, "r") ?: return null
            return try {
                memScoped {
                    val values = mutableMapOf<String, String>()
                    val buffer = allocArray<ByteVar>(4096)
                    while (true) {
                        val line = fgets(buffer, 4096, file) ?: break
                        parseConfigLine(line.toKString(), values)
                    }
                    values
                }
            } finally {
                fclose(file)
            }
        }

        private fun parseConfigLine(raw: String, values: MutableMap<String, String>) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return
            val assignment = line.removePrefix("export ").trim()
            val separator = assignment.indexOf('=')
            if (separator <= 0) return
            val key = assignment.substring(0, separator).trim()
            if (key.isEmpty()) return

            var value = assignment.substring(separator + 1).trim()
            if (value.length >= 2) {
                val quote = value.first()
                if ((quote == '"' || quote == '\'') && value.last() == quote) {
                    value = value.substring(1, value.length - 1)
                }
            }
            values[key] = value
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun env(name: String): String? =
            memScoped {
                platform.posix.getenv(name)?.toKString()
            }
    }
}
