package com.esp32.control.gui.api

import com.esp32.control.gui.model.Device
import com.esp32.control.gui.model.HealthResponse
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Platform-specific engine:
 * - Android uses OkHttp and reaches the host machine through 10.0.2.2.
 * - Web uses the Ktor Js/fetch engine and derives the server host from the page.
 */
expect fun createPlatformHttpClient(): HttpClient

expect fun apiBaseUrl(): String

internal fun HttpClientConfig<*>.configureEsp32Client() {
    expectSuccess = true
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
    }
}

class Esp32Api(
    val baseUrl: String = apiBaseUrl(),
    private val client: HttpClient = createPlatformHttpClient(),
) {
    suspend fun health(): HealthResponse =
        client.get("$baseUrl/api/health").body()

    suspend fun devices(): List<Device> =
        client.get("$baseUrl/api/devices").body()

    suspend fun toggle(deviceId: String): Device =
        client.post("$baseUrl/api/devices/$deviceId/toggle").body()

    fun close() {
        client.close()
    }
}
