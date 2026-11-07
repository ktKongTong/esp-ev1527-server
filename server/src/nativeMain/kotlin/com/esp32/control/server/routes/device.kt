package com.esp32.control.server.routes

import com.esp32.control.contract.CommandRequest
import com.esp32.control.contract.UpdateDeviceRequest
import com.esp32.control.server.ApiException
import com.esp32.control.server.domain.CommandService
import com.esp32.control.server.domain.DeviceService
import com.esp32.control.server.invalidDeviceId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

fun Route.deviceRoute(devices: DeviceService, commands: CommandService) {
    get("/devices") {
        call.respond(devices.list())
    }

    get("/devices/{deviceId}") {
        val deviceId = call.parameters["deviceId"] ?: throw invalidDeviceId()
        call.respond(devices.get(deviceId))
    }

    patch("/devices/{deviceId}") {
        val deviceId = call.parameters["deviceId"] ?: throw invalidDeviceId()
        val request = call.receive<UpdateDeviceRequest>()
        call.respond(devices.update(deviceId, request))
    }

    post("/devices/{deviceId}/commands") {
        val deviceId = call.parameters["deviceId"] ?: throw invalidDeviceId()
        val idempotencyKey = call.request.headers["Idempotency-Key"]
            ?: throw ApiException(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Idempotency-Key is required",
            )
        val request = call.receive<CommandRequest>()
        val result = commands.issue(deviceId, request.action, idempotencyKey)
        if (result.replayed) {
            call.response.header("Idempotent-Replayed", "true")
        }
        call.respond(HttpStatusCode.Accepted, result.dispatched)
    }

    delete("/devices/{deviceId}") {
        val deviceId = call.parameters["deviceId"] ?: throw invalidDeviceId()
        devices.delete(deviceId)
        call.respond(HttpStatusCode.NoContent)
    }
}
