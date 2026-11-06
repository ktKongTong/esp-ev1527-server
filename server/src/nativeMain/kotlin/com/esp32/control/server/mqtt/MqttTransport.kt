package com.esp32.control.server.mqtt

import com.esp32.control.server.Log
import com.esp32.control.server.MqttConfig
import com.esp32.control.server.MqttTransportType
import com.esp32.control.server.logPreview
import de.kempmobil.ktor.mqtt.Disconnected
import de.kempmobil.ktor.mqtt.MqttClient
import de.kempmobil.ktor.mqtt.MqttClientConfigBuilder
import de.kempmobil.ktor.mqtt.MqttEngine
import de.kempmobil.ktor.mqtt.MqttEngineConfig
import de.kempmobil.ktor.mqtt.MqttEngineFactory
import de.kempmobil.ktor.mqtt.PublishRequest
import de.kempmobil.ktor.mqtt.QoS
import de.kempmobil.ktor.mqtt.buildConfig
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
                Log.error(cause) { "[mqtt] background coroutine failed" }
            }
        },
    )

    private val endpoint: String = when (config.transport) {
        MqttTransportType.TCP -> "tcp://${config.host}:${config.port}"
        MqttTransportType.WEBSOCKET ->
            "${if (config.useTls) "wss" else "ws"}://${config.host}:${config.port}${config.websocketPath}"
    }

    private val client: MqttClient = when (config.transport) {
        MqttTransportType.TCP -> MqttClient(config.host, config.port) {
            applyClientConfig(config)
        }
        MqttTransportType.WEBSOCKET -> {
            val engine = WebSocketMqttEngine(
                host = config.host,
                port = config.port,
                path = config.websocketPath,
                secure = config.useTls,
            )
            MqttClient(buildConfig(PrebuiltEngineFactory(engine)) {
                applyClientConfig(config)
            })
        }
    }

    private fun MqttClientConfigBuilder<MqttEngineConfig>.applyClientConfig(config: MqttConfig) {
        clientId = config.clientId
        keepAliveSeconds = config.keepAliveSeconds.toUShort()
        username = config.username
        password = config.password
    }

    fun start(): Job = scope.launch {
        launch {
            client.publishedPackets.collect { publish ->
                val payload = publish.payload.toByteArray()
                Log.debug {
                    "[mqtt] <- ${publish.topic.name} " +
                        "qos=${publish.qoS} retain=${publish.isRetainMessage} " +
                        "payload=${payload.logPreview()}"
                }
                try {
                    onMessage(MqttMessage(publish.topic.name, payload))
                } catch (e: CancellationException) {
                    throw e
                } catch (cause: Throwable) {
                    Log.error(cause) {
                        "[mqtt] message handler failed for ${publish.topic.name}"
                    }
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
                Log.info { "[mqtt] connecting to $endpoint for $topicFilter" }
                val connack = client.connect(isCleanStart = true).getOrThrow()
                if (!connack.isSuccess) {
                    throw MqttUnavailableException("MQTT CONNACK rejected: ${connack.reason}")
                }
                Log.debug {
                    "[mqtt] CONNACK ${connack.reason} sessionPresent=${connack.isSessionPresent} " +
                        "serverKeepAlive=${connack.serverKeepAlive}"
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
                Log.info { "[mqtt] session established: $endpoint subscribed=$topicFilter" }

                // ktor-mqtt does not reconnect by itself. Wait for the engine
                // to report the socket loss, then rebuild the session above.
                client.connectionState.first { it is Disconnected }
            } catch (e: CancellationException) {
                throw e
            } catch (cause: Throwable) {
                Log.error(cause) { "[mqtt] session failed on $endpoint" }
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
        Log.debug { "[mqtt] -> $topic payload=${payload.logPreview()}" }
        val request = PublishRequest(topic) {
            desiredQoS = QoS.AT_LEAST_ONCE
            payload(ByteString(payload))
        }
        client.publish(request).getOrElse {
            throw MqttUnavailableException("MQTT publish failed for $topic", it)
        }
    }

    fun close() {
        runCatching { client.close() }
        scope.cancel()
    }
}

private class PrebuiltEngineFactory(
    private val engine: MqttEngine,
) : MqttEngineFactory<MqttEngineConfig> {
    override fun create(block: MqttEngineConfig.() -> Unit): MqttEngine = engine
}
