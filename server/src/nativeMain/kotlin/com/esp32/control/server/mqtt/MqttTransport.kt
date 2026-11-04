package com.esp32.control.server.mqtt

import com.esp32.control.server.MqttConfig
import de.kempmobil.ktor.mqtt.Disconnected
import de.kempmobil.ktor.mqtt.MqttClient
import de.kempmobil.ktor.mqtt.PublishRequest
import de.kempmobil.ktor.mqtt.QoS
import de.kempmobil.ktor.mqtt.buildFilterList
import de.kempmobil.ktor.mqtt.packet.hasFailure
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString

class MqttUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class MqttMessage(val topic: String, val payload: ByteArray)

/**
 * Thin adapter around ktor-mqtt.
 *
 * The library owns MQTT 5 framing, QoS handshakes, keep-alive and subscription
 * state. This class only maps topics and byte arrays, and adds the reconnect
 * supervisor that ktor-mqtt does not provide.
 */
class MqttTransport(
    private val config: MqttConfig,
    private val topicFilter: String,
    private val onMessage: suspend (MqttMessage) -> Unit,
    private val onDisconnected: suspend () -> Unit,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, cause ->
            if (cause !is CancellationException) {
                println("MQTT background coroutine failed: ${cause.message}")
            }
        },
    )

    private val client = MqttClient(config.host, config.port) {
        clientId = config.clientId
        keepAliveSeconds = config.keepAliveSeconds.toUShort()
        username = config.username
        password = config.password
    }

    fun start(): Job = scope.launch {
        launch {
            client.publishedPackets.collect { publish ->
                try {
                    onMessage(MqttMessage(publish.topic.name, publish.payload.toByteArray()))
                } catch (e: CancellationException) {
                    throw e
                } catch (cause: Throwable) {
                    println("MQTT message handler failed: ${cause.message}")
                }
            }
        }
        connectionLoop()
    }

    private suspend fun connectionLoop() {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            val sessionStartedAt = TimeSource.Monotonic.markNow()
            var sessionEstablished = false
            try {
                val connack = client.connect(isCleanStart = true).getOrThrow()
                if (!connack.isSuccess) {
                    throw MqttUnavailableException("MQTT CONNACK rejected: ${connack.reason}")
                }
                val suback = client.subscribe(
                    buildFilterList {
                        add(topicFilter, QoS.AT_LEAST_ONCE)
                    },
                ).getOrThrow()
                if (suback.hasFailure) {
                    throw MqttUnavailableException("MQTT subscription rejected: ${suback.reasons}")
                }

                sessionEstablished = true

                // ktor-mqtt does not reconnect by itself. Wait for the engine
                // to report the socket loss, then rebuild the session above.
                client.connectionState.first { it is Disconnected }
            } catch (e: CancellationException) {
                throw e
            } catch (cause: Throwable) {
                println("MQTT session failed: ${cause.message}")
            } finally {
                if (sessionEstablished) {
                    runCatching { onDisconnected() }
                }
                runCatching { client.disconnect() }
            }

            // A session that stayed up long enough is treated as healthy, so a
            // later disconnect starts again from the short backoff.
            backoffMs = if (sessionStartedAt.elapsedNow() >= 30.seconds) {
                1_000L
            } else {
                (backoffMs * 2).coerceAtMost(30_000L)
            }
            delay((backoffMs * Random.nextDouble(0.8, 1.2)).toLong())
        }
    }

    suspend fun publish(topic: String, payload: ByteArray) {
        val request = PublishRequest(topic) {
            desiredQoS = QoS.AT_LEAST_ONCE
            payload(ByteString(payload))
        }
        client.publish(request).getOrElse {
            throw MqttUnavailableException("MQTT publish failed", it)
        }
    }

    fun close() {
        runCatching { client.close() }
        scope.cancel()
    }
}
