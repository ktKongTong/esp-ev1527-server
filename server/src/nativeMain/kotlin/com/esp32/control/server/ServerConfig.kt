package com.esp32.control.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

data class ServerConfig(
    val host: String,
    val port: Int,
) {
    companion object {
        fun fromEnvironment(): ServerConfig {
            val host = readEnv("SERVER_HOST")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "0.0.0.0"
            val port = readEnv("SERVER_PORT")
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: 8080
            return ServerConfig(host = host, port = port)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readEnv(name: String): String? = getenv(name)?.toKString()
