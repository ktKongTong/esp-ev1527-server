package com.esp32.control.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.time.TimeMark
import kotlin.time.TimeSource

fun main() {
    val config = ServerConfig.fromEnvironment()
    val startedAt = TimeSource.Monotonic.markNow()
    val store = DeviceStore()

    println("ESP32 control server ${SERVER_VERSION} (Ktor + Kotlin/Native)")
    println("Listening on http://${config.host}:${config.port}")
    println("  GET  /api/health")
    println("  GET  /api/devices")
    println("  POST /api/devices/{id}/toggle")
    println("Demo data is in memory; MQTT integration is not wired yet.")

    embeddedServer(CIO, host = config.host, port = config.port) {
        module(store, startedAt)
    }.start(wait = true)
}

fun Application.module(store: DeviceStore, startedAt: TimeMark) {
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }

    routing {
        get("/") {
            call.respondText(
                """
                ESP32 control server (Ktor + Kotlin/Native)

                GET  /api/health
                GET  /api/devices
                GET  /api/devices/{id}
                POST /api/devices/{id}/toggle
                """.trimIndent(),
                ContentType.Text.Plain,
            )
        }

        get("/api/health") {
            call.respond(
                HealthResponse(
                    uptimeSeconds = startedAt.elapsedNow().inWholeSeconds,
                ),
            )
        }

        get("/api/devices") {
            call.respond(store.list())
        }

        get("/api/devices/{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "设备 ID 不能为空"))
                return@get
            }
            val device = store.get(id)
            if (device == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("device_not_found", "未找到设备 $id"))
            } else {
                call.respond(device)
            }
        }

        post("/api/devices/{id}/toggle") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_id", "设备 ID 不能为空"))
                return@post
            }
            val device = store.toggle(id)
            if (device == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("device_not_found", "未找到设备 $id"))
                return@post
            }
            println("${call.request.path()} -> ${device.name}: ${if (device.on) "ON" else "OFF"}")
            call.respond(device)
        }
    }
}
