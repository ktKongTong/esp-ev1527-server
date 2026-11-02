package com.esp32.control.gui.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String = "unknown",
    val service: String = "",
    val runtime: String = "",
    val version: String = "",
    val uptimeSeconds: Long = 0,
    val dataSource: String = "",
    val gatewayConnected: Boolean = false,
)

@Serializable
data class Device(
    val id: String = "",
    val name: String = "",
    val location: String = "",
    val on: Boolean = false,
    val online: Boolean = false,
    val codeOn: Long = 0,
    val codeOff: Long = 0,
    val lastAction: String = "",
)

@Serializable
data class ApiError(
    val error: String = "",
    val message: String = "",
)
