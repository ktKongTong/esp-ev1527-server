package com.esp32.control.server

import com.esp32.control.contract.ApiErrorBody
import com.esp32.control.contract.ApiErrorEnvelope
import com.esp32.control.contract.CommandRequest
import com.esp32.control.contract.ConfirmLearningRequest
import com.esp32.control.contract.ContractJson
import com.esp32.control.contract.ServerEvent
import com.esp32.control.contract.StartLearningRequest
import com.esp32.control.contract.TestLearningRequest
import com.esp32.control.contract.type
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.serialization.kotlinx.json.json
import io.ktor.sse.ServerSentEvent
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.uri
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlin.time.Duration.Companion.seconds

private val RequestIdKey = AttributeKey<String>("esp32.requestId")

private val RequestContextPlugin = createApplicationPlugin("RequestContext") {
    onCall { call ->
        val incoming = call.request.headers["X-Request-Id"]
        val requestId = if (incoming != null && incoming.length in 1..128) {
            incoming
        } else {
            newId("req")
        }
        call.attributes.put(RequestIdKey, requestId)
        call.response.header("X-Request-Id", requestId)
        if (!call.request.uri.startsWith("/api/v1/events")) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }
    }
}

fun main() {
    val config = ServerConfig.fromEnvironment()
    val hub = SseHub()
    val control = ControlPlane(config, hub)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val mqtt = config.mqtt?.let { mqttConfig ->
        MqttClient(
            config = mqttConfig,
            onMessage = control::onMqttMessage,
            onConnected = control::onMqttConnected,
            onDisconnected = control::onMqttDisconnected,
        )
    }
    mqtt?.let {
        control.attachTransport { topic, payload -> it.publish(topic, payload) }
        it.start()
    }

    scope.launch {
        while (isActive) {
            delay(500)
            runCatching { control.tick() }
        }
    }

    embeddedServer(CIO, host = config.host, port = config.port) {
        module(control, hub, config)
    }.start(wait = true)
}

fun Application.module(control: ControlPlane, hub: SseHub, config: ServerConfig) {
    install(RequestContextPlugin)
    install(ContentNegotiation) {
        json(ContractJson)
    }
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Accept)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("Idempotency-Key")
        allowHeader("X-Request-Id")
        allowHeader("Last-Event-ID")
        exposeHeader("X-Request-Id")
        exposeHeader("Idempotent-Replayed")
    }
    install(SSE)
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondApiError(cause.status, cause.code, cause.message, cause.details)
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondApiError(
                HttpStatusCode.BadRequest,
                "malformed_json",
                "Request body is not valid JSON",
            )
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondApiError(
                HttpStatusCode.UnsupportedMediaType,
                "unsupported_media_type",
                "Content-Type must be application/json",
            )
        }
        exception<BadRequestException> { call, _ ->
            // Application-level 400s are thrown as ApiException. A raw
            // BadRequestException here comes from body deserialization.
            call.respondApiError(
                HttpStatusCode.BadRequest,
                "malformed_json",
                "Request body is not valid JSON",
            )
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val message = cause.message.orEmpty()
            if (message.contains("Cannot transform this request's content", ignoreCase = true)) {
                val contentType = call.request.contentType()
                if (contentType.contentType != "application" || contentType.contentSubtype != "json") {
                    call.respondApiError(
                        HttpStatusCode.UnsupportedMediaType,
                        "unsupported_media_type",
                        "Content-Type must be application/json",
                    )
                } else {
                    call.respondApiError(
                        HttpStatusCode.BadRequest,
                        "malformed_json",
                        "Request body is not valid JSON",
                    )
                }
                return@exception
            }
            call.respondApiError(
                HttpStatusCode.InternalServerError,
                "internal_error",
                cause.message ?: "Unexpected server error",
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondApiError(HttpStatusCode.NotFound, "route_not_found", "Route not found")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondApiError(
                HttpStatusCode.MethodNotAllowed,
                "method_not_allowed",
                "Method not allowed",
            )
        }
    }

    routing {
        get("/") {
            call.respondText(
                "ESP32 control server ${SERVER_VERSION} (contract 1.1.0)\n" +
                    "API base: /api/v1\n" +
                    "dataSource: ${config.dataSource}\n",
            )
        }

        route("/api/v1") {
            get("/health") {
                call.respond(control.health())
            }

            get("/devices") {
                call.respond(control.listDevices())
            }

            get("/devices/{deviceId}") {
                val deviceId = call.parameters["deviceId"] ?: throw invalidDeviceId()
                call.respond(control.getDevice(deviceId))
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
                val result = control.issueCommand(deviceId, request.action, idempotencyKey)
                if (result.replayed) {
                    call.response.header("Idempotent-Replayed", "true")
                }
                call.respond(HttpStatusCode.Accepted, result.accepted)
            }

            get("/commands/{commandId}") {
                val commandId = call.parameters["commandId"]
                    ?: throw ApiException(
                        HttpStatusCode.NotFound,
                        "command_not_found",
                        "Command not found",
                    )
                call.respond(control.getCommand(commandId))
            }

            delete("/devices/{deviceId}") {
                val deviceId = call.parameters["deviceId"] ?: throw invalidDeviceId()
                control.deleteDevice(deviceId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/learning/sessions") {
                val length = call.request.contentLength()
                val request = if (length == null || length == 0L) {
                    StartLearningRequest()
                } else {
                    call.receive<StartLearningRequest>()
                }
                call.respond(HttpStatusCode.Created, control.startLearning(request))
            }

            get("/learning/sessions/{sessionId}") {
                val sessionId = call.parameters["sessionId"]
                    ?: throw learningNotFound()
                call.respond(control.getLearning(sessionId))
            }

            post("/learning/sessions/{sessionId}/candidates/{candidateId}/accept") {
                val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
                val candidateId = call.parameters["candidateId"]
                    ?: throw ApiException(
                        HttpStatusCode.NotFound,
                        "candidate_not_found",
                        "Candidate not found",
                    )
                call.respond(control.acceptCandidate(sessionId, candidateId))
            }

            post("/learning/sessions/{sessionId}/rescan") {
                val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
                call.respond(control.rescan(sessionId))
            }

            post("/learning/sessions/{sessionId}/test") {
                val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
                val request = call.receive<TestLearningRequest>()
                call.respond(
                    HttpStatusCode.Accepted,
                    control.testLearning(sessionId, request.slot),
                )
            }

            post("/learning/sessions/{sessionId}/confirm") {
                val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
                val request = call.receive<ConfirmLearningRequest>()
                val confirmation = control.confirmLearning(sessionId, request)
                call.response.header(HttpHeaders.Location, "/api/v1/devices/${confirmation.device.id}")
                call.respond(HttpStatusCode.Created, confirmation)
            }

            delete("/learning/sessions/{sessionId}") {
                val sessionId = call.parameters["sessionId"] ?: throw learningNotFound()
                control.cancelLearning(sessionId)
                call.respond(HttpStatusCode.NoContent)
            }

            sse("/events") {
                heartbeat { period = 15.seconds }
                send(ServerSentEvent(retry = 3_000))
                val lastEventId = call.request.headers["Last-Event-ID"]
                hub.stream(lastEventId) { event ->
                    send(
                        ServerSentEvent(
                            data = encodeEvent(event),
                            event = event.type,
                            id = event.eventId,
                        ),
                    )
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: Map<String, String>? = null,
) {
    val requestId = if (attributes.contains(RequestIdKey)) attributes[RequestIdKey] else newId("req")
    respond(
        status,
        ApiErrorEnvelope(
            ApiErrorBody(
                code = code,
                message = message,
                requestId = requestId,
                details = details,
            ),
        ),
    )
}

private fun encodeEvent(event: ServerEvent): String =
    ContractJson.encodeToString(ServerEvent.serializer(), event)

private fun invalidDeviceId(): ApiException = ApiException(
    HttpStatusCode.BadRequest,
    "invalid_device_id",
    "Invalid device id",
)

private fun learningNotFound(): ApiException = ApiException(
    HttpStatusCode.NotFound,
    "learning_session_not_found",
    "Learning session not found",
)
