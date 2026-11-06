package com.esp32.control.server.mqtt

import com.esp32.control.server.Log
import de.kempmobil.ktor.mqtt.ConnectionException
import de.kempmobil.ktor.mqtt.MalformedPacketException
import de.kempmobil.ktor.mqtt.MqttEngine
import de.kempmobil.ktor.mqtt.packet.Packet
import de.kempmobil.ktor.mqtt.packet.readPacket
import de.kempmobil.ktor.mqtt.packet.write
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.Buffer

/**
 * MQTT over WebSocket transport.
 *
 * Adapted from ktor-mqtt's WebSocketEngine in `mqtt-client-ws`
 * (Apache-2.0, https://github.com/ktKongTong/ktor-mqtt). That module is not
 * published for linuxArm64, linuxX64 or macosArm64, so the transport loop
 * lives here. MQTT framing still comes from ktor-mqtt's public
 * `readPacket`/`write`; this class only moves bytes over WebSocket.
 *
 * Ktor Native has no raw TLS sockets, but its Curl engine ships a static
 * libcurl + OpenSSL and supports WebSockets. `wss` therefore provides TLS for
 * the MQTT connection without host libraries or an external terminator.
 */
internal class WebSocketMqttEngine(
    host: String,
    port: Int,
    path: String,
    secure: Boolean,
) : MqttEngine {
    private val endpoint = Url(
        buildString {
            append(if (secure) "wss" else "ws")
            append("://")
            append(host)
            append(':')
            append(port)
            append(if (path.startsWith('/')) path else "/$path")
        },
    )

    private val client = HttpClient(Curl) {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
        }
    }

    private val _packetResults = MutableSharedFlow<Result<Packet>>()
    override val packetResults = _packetResults.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected = _connected.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var receiverJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    override suspend fun start(): Result<Unit> {
        return try {
            session = client.webSocketSession(endpoint.toString()) {
                headers[HttpHeaders.SecWebSocketProtocol] = "mqtt"
            }.also { opened ->
                Log.info { "[mqtt-ws] websocket open: $endpoint" }
                _connected.value = true
                receiverJob = scope.launch { opened.incomingMessagesLoop() }
            }
            Result.success(Unit)
        } catch (cause: Exception) {
            Log.error(cause) { "[mqtt-ws] websocket connect failed: $endpoint" }
            Result.failure(ConnectionException("Cannot connect to $endpoint", cause))
        }
    }

    override suspend fun send(packet: Packet): Result<Unit> {
        return session?.doSend(packet)
            ?: Result.failure(ConnectionException("Not connected to $endpoint"))
    }

    override suspend fun disconnect() {
        _connected.value = false
        session?.close()
        receiverJob?.cancel()
        session = null
        receiverJob = null
    }

    override fun close() {
        client.close()
        scope.cancel()
    }

    override fun toString(): String = "WebSocketMqttEngine[$endpoint]"

    private suspend fun DefaultClientWebSocketSession.incomingMessagesLoop() {
        // MQTT packets are not aligned to WebSocket frame boundaries, so join
        // binary frames through a channel and read complete packets from it.
        val channel = ByteChannel(autoFlush = true)
        val reader = scope.launch {
            while (!channel.isClosedForRead) {
                val result = try {
                    Result.success(channel.readPacket())
                } catch (ex: MalformedPacketException) {
                    Result.failure(ex)
                } catch (_: CancellationException) {
                    break
                }
                _packetResults.emit(result)
            }
        }

        try {
            for (frame in incoming) {
                if (frame is Frame.Binary) {
                    Log.debug { "[mqtt-ws] frame ${frame.data.size}B" }
                    channel.writeFully(frame.readBytes())
                }
            }
        } catch (_: CancellationException) {
            // Session was closed by disconnect() or by the caller.
        } catch (cause: Exception) {
            Log.error(cause) { "[mqtt-ws] receive failed: $endpoint" }
        } finally {
            Log.debug { "[mqtt-ws] receive loop closed: $endpoint" }
            disconnect()
            reader.cancel()
            channel.close()
        }
    }

    private suspend fun DefaultClientWebSocketSession.doSend(packet: Packet): Result<Unit> {
        return try {
            with(Buffer()) {
                write(packet)
                if (size <= maxFrameSize) {
                    outgoing.send(Frame.Binary(fin = true, packet = this))
                } else {
                    val frame = Buffer()
                    while (size > 0) {
                        readAtMostTo(frame, size.coerceAtMost(maxFrameSize))
                        outgoing.send(Frame.Binary(fin = true, packet = frame))
                    }
                }
            }
            Result.success(Unit)
        } catch (cause: Exception) {
            Result.failure(cause)
        }
    }
}
