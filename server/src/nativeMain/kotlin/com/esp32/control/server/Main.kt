package com.esp32.control.server

import com.esp32.control.contract.ApiErrorBody
import com.esp32.control.contract.ApiErrorEnvelope
import com.esp32.control.contract.ContractJson
import com.esp32.control.contract.ServerEvent
import com.esp32.control.contract.type
import com.esp32.control.server.routes.deviceRoute
import com.esp32.control.server.routes.learningRoute
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
import io.ktor.server.request.contentType
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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
            deviceRoute(control)
            learningRoute(control)

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

internal fun invalidDeviceId(): ApiException = ApiException(
    HttpStatusCode.BadRequest,
    "invalid_device_id",
    "Invalid device id",
)

internal fun learningNotFound(): ApiException = ApiException(
    HttpStatusCode.NotFound,
    "learning_session_not_found",
    "Learning session not found",
)
