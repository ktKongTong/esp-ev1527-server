package com.esp32.control.server

import io.github.davidepianca98.MQTTClient
import io.github.davidepianca98.mqtt.MQTTVersion
import io.github.davidepianca98.mqtt.Subscription
import io.github.davidepianca98.mqtt.packets.Qos
import io.github.davidepianca98.mqtt.packets.mqttv5.ReasonCode
import io.github.davidepianca98.mqtt.packets.mqttv5.SubscriptionOptions
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class MqttUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin adapter around the KMQTT Kotlin Multiplatform MQTT client.
 *
 * KMQTT owns framing, keep-alive, QoS 1 PUBACK handling and session
 * behaviour. This class only translates the server's topic tree and
 * ByteArray payloads, and adds the server's reconnect policy because
 * KMQTT 1.0.0 does not reconnect on its own.
 */
class MqttClient(
    private val config: MqttConfig,
    private val onMessage: suspend (topic: String, payload: ByteArray) -> Unit,
    private val onConnected: suspend () -> Unit,
    private val onDisconnected: suspend () -> Unit,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, cause ->
            if (cause !is CancellationException) {
                println("MQTT background coroutine failed: ${cause.message}")
            }
        },
    )
    private val incoming = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

    @Volatile
    private var client: MQTTClient? = null

    @Volatile
    private var connected: Boolean = false

    fun start(): Job = scope.launch {
        launch {
            for ((topic, payload) in incoming) {
                try {
                    onMessage(topic, payload)
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
            var mqtt: MQTTClient? = null
            var runner: Job? = null
            try {
                val connectedSignal = CompletableDeferred<Unit>()
                val subscribedSignal = CompletableDeferred<Unit>()
                val current = MQTTClient(
                    MQTTVersion.MQTT3_1_1,
                    config.host,
                    config.port,
                    null,
                    config.keepAliveSeconds,
                    null,
                    true,
                    newId("esp32-control-server"),
                    config.username,
                    config.password?.encodeToByteArray()?.toUByteArray(),
                    connackTimeout = 10,
                    connectTimeout = 10,
                    onConnected = { connectedSignal.complete(Unit) },
                    onDisconnected = { },
                    onSubscribed = { subscribedSignal.complete(Unit) },
                    publishReceived = { publish ->
                        incoming.trySend(
                            publish.topicName to (
                                publish.payload?.toByteArray() ?: ByteArray(0)
                                ),
                        )
                    },
                )
                mqtt = current
                // The constructor connects synchronously and may throw; keeping
                // it inside this try lets the loop back off and retry.
                client = current

                // KMQTT 1.0.0's run() is blocking and throws when the socket
                // closes. Contain it so a Broker restart becomes a reconnect.
                runner = scope.launch(Dispatchers.Default) {
                    try {
                        current.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // The connection loop below owns reconnect policy.
                    }
                }

                val didConnect = withTimeoutOrNull(15.seconds) {
                    connectedSignal.await()
                    true
                } ?: false
                if (!didConnect) {
                    throw MqttUnavailableException("MQTT connection timed out")
                }

                try {
                    current.subscribe(
                        listOf(
                            Subscription(
                                config.topicPrefix + "/#",
                                SubscriptionOptions(Qos.AT_LEAST_ONCE),
                            ),
                        ),
                    )
                    withTimeout(5.seconds) { subscribedSignal.await() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw MqttUnavailableException("MQTT subscribe failed", e)
                }

                connected = true
                backoffMs = 1_000L
                onConnected()
                runner.join()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Reconnect with bounded exponential backoff.
            } finally {
                connected = false
                client = null
                runner?.cancel()
                mqtt?.let { runCatching { it.disconnect(ReasonCode.SUCCESS) } }
                if (mqtt != null) {
                    runCatching { onDisconnected() }
                }
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    suspend fun publish(topic: String, payload: ByteArray) {
        val mqtt = client ?: throw MqttUnavailableException("MQTT client is not started")
        if (!connected) throw MqttUnavailableException("MQTT is not connected")
        try {
            mqtt.publish(
                retain = false,
                qos = Qos.AT_LEAST_ONCE,
                topic = topic,
                payload = payload.toUByteArray(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MqttUnavailableException("MQTT publish failed", e)
        }
    }

    fun close() {
        connected = false
        runCatching { client?.disconnect(ReasonCode.SUCCESS) }
        client = null
        scope.cancel()
    }
}
