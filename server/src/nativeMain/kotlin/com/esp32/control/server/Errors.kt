package com.esp32.control.server

import com.esp32.control.server.data.DeviceTombstone
import io.ktor.http.HttpStatusCode

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: Map<String, String>? = null,
) : RuntimeException(message)

private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")

fun requireDeviceId(deviceId: String) {
    if (!DEVICE_ID.matches(deviceId)) {
        throw invalidDeviceId()
    }
}

fun invalidDeviceId(): ApiException =
    ApiException(HttpStatusCode.BadRequest, "invalid_device_id", "Invalid device id")

fun deviceRemoved(tombstone: DeviceTombstone): ApiException = ApiException(
    HttpStatusCode.Gone,
    "device_removed",
    "Device has been removed",
    mapOf("removedAtEpochMs" to tombstone.removedAtEpochMs.toString()),
)

fun deviceNotFound(): ApiException =
    ApiException(HttpStatusCode.NotFound, "device_not_found", "Device not found")

fun learningNotFound(): ApiException =
    ApiException(HttpStatusCode.NotFound, "learning_session_not_found", "Learning session not found")

fun invalidRequest(message: String): ApiException =
    ApiException(HttpStatusCode.BadRequest, "invalid_request", message)
