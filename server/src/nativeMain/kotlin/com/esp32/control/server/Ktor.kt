package com.esp32.control.server

import com.esp32.control.contract.ApiErrorBody
import com.esp32.control.contract.ApiErrorEnvelope
import com.esp32.control.contract.ContractJson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException

private val RequestIdKey = AttributeKey<String>("esp32.requestId")

private fun ApplicationCall.requestId(): String =
    if (attributes.contains(RequestIdKey)) attributes[RequestIdKey] else "-"

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
        call.response.header(HttpHeaders.CacheControl, "no-store")
        Log.debug {
            "[http] -> ${call.request.httpMethod.value} ${call.request.uri} requestId=$requestId"
        }
    }
    onCallRespond { call, _ ->
        // An unset status is the implicit 200 OK, so report it that way.
        Log.debug {
            "[http] <- ${call.response.status()?.value ?: 200} " +
                "${call.request.httpMethod.value} ${call.request.uri} requestId=${call.requestId()}"
        }
    }
}
private suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: Map<String, String>? = null,
) {
    val requestId = requestId().takeUnless { it == "-" } ?: newId("req")
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

fun Application.installModules() {
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
        exposeHeader("X-Request-Id")
        exposeHeader("Idempotent-Replayed")
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            Log.warn {
                "[http] ${cause.status.value} ${cause.code} ${call.request.uri} " +
                    "requestId=${call.requestId()}"
            }
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
            Log.error(cause) {
                "[http] 500 ${call.request.uri} requestId=${call.requestId()}"
            }
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
}

