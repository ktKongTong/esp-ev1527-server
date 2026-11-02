package com.esp32.control.server

import kotlinx.serialization.Serializable

const val SERVER_VERSION = "0.1.0"

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val service: String = "esp32-control-server",
    val runtime: String = "Kotlin/Native",
    val version: String = SERVER_VERSION,
    val uptimeSeconds: Long,
    val dataSource: String = "in-memory-demo",
    val gatewayConnected: Boolean = false,
)

@Serializable
data class Device(
    val id: String,
    val name: String,
    val location: String,
    val on: Boolean,
    val online: Boolean,
    val codeOn: Long,
    val codeOff: Long,
    val lastAction: String,
)

@Serializable
data class ApiError(
    val error: String,
    val message: String,
)
