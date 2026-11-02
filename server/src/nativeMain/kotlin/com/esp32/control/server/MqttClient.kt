package com.esp32.control.server

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_SNDTIMEO
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.socket
import platform.posix.timeval
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MqttUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal MQTT 3.1.1 client for the single gateway topic tree.
 *
 * It deliberately supports only what the ESP32 contract needs: CONNECT,
 * SUBSCRIBE, QoS 0/1 PUBLISH, PUBACK, PING and reconnect. Keeping the
 * transport dependency-free makes the Kotlin/Native linuxArm64 build portable.
 */
@OptIn(ExperimentalForeignApi::class)
class MqttClient(
    private val config: MqttConfig,
    private val onMessage: suspend (topic: String, payload: ByteArray) -> Unit,
    private val onConnected: suspend () -> Unit,
    private val onDisconnected: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outgoing = Channel<Outgoing>(capacity = 128)
    private val pendingAcks = mutableMapOf<Int, CompletableDeferred<Unit>>()
    private val pendingMutex = Mutex()
    private val writeMutex = Mutex()
    private var socketFd: Int = -1
    private var nextPacketId: Int = 1

    @Volatile
    private var connected: Boolean = false

    fun start(): Job = scope.launch {
        connectionLoop()
    }

    fun close() {
        connected = false
        closeSocket()
        scope.cancel()
    }

    suspend fun publish(topic: String, payload: ByteArray, timeout: Duration = 3.seconds) {
        if (!connected) throw MqttUnavailableException("MQTT is not connected")
        val packetId = nextPacketId().toInt()
        val ack = CompletableDeferred<Unit>()
        pendingMutex.withLock { pendingAcks[packetId] = ack }
        try {
            outgoing.send(Outgoing.Publish(topic, payload, packetId, ack))
            withTimeout(timeout) { ack.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            pendingMutex.withLock { pendingAcks.remove(packetId) }
            throw MqttUnavailableException("MQTT publish failed", e)
        }
    }

    private suspend fun connectionLoop() {
        var backoffMs = 2_000L
        while (currentCoroutineContext().isActive) {
            try {
                connectAndServe()
                backoffMs = 2_000L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Reconnect with a bounded backoff. The control plane observes
                // the disconnected callback and marks the gateway offline.
            } finally {
                connected = false
                closeSocket()
                failPending()
                runCatching { onDisconnected() }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun connectAndServe() = coroutineScope {
        val fd = openSocket(config.host, config.port)
        socketFd = fd
        sendConnect(fd)
        readConnack(fd)
        sendSubscribe(fd)
        readSubAck(fd)
        connected = true
        onConnected()

        val reader = launch(Dispatchers.Default) { readLoop(fd) }
        val writer = launch(Dispatchers.Default) { writeLoop(fd) }
        val pinger = launch(Dispatchers.Default) {
            while (isActive) {
                delay(config.keepAliveSeconds.seconds / 2)
                sendPacket(fd, byteArrayOf(0xC0.toByte(), 0x00))
            }
        }

        try {
            reader.join()
        } finally {
            writer.cancel()
            pinger.cancel()
        }
    }

    private suspend fun writeLoop(fd: Int) {
        for (message in outgoing) {
            when (message) {
                is Outgoing.Publish -> {
                    try {
                        sendPublish(fd, message.topic, message.payload, message.packetId)
                    } catch (e: Throwable) {
                        message.ack.completeExceptionally(MqttUnavailableException("MQTT write failed", e))
                        throw e
                    }
                }
            }
        }
    }

    private suspend fun readLoop(fd: Int) {
        try {
            while (currentCoroutineContext().isActive) {
                val first = readByte(fd)
                val type = (first.toInt() ushr 4) and 0x0F
                val remaining = readRemainingLength(fd)
                val body = readExact(fd, remaining)

                when (type) {
                    2 -> {
                        if (body.size >= 2 && body[1].toInt() != 0) {
                            throw MqttUnavailableException("MQTT CONNACK rejected: ${body[1]}")
                        }
                    }
                    3 -> handlePublish(fd, first, body)
                    4 -> {
                        if (body.size >= 2) {
                            val packetId = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                            pendingMutex.withLock {
                                pendingAcks.remove(packetId)?.complete(Unit)
                            }
                        }
                    }
                    9 -> Unit // SUBACK
                    13 -> Unit // PINGRESP
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MqttUnavailableException("MQTT read failed", e)
        }
    }

    private suspend fun handlePublish(fd: Int, first: Byte, body: ByteArray) {
        var offset = 0
        if (body.size < 2) return
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        offset = 2
        if (offset + topicLength > body.size) return
        val topic = body.copyOfRange(offset, offset + topicLength).decodeToString()
        offset += topicLength
        val qos = (first.toInt() ushr 1) and 0x03
        var packetId = 0
        if (qos > 0) {
            if (offset + 2 > body.size) return
            packetId = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
            offset += 2
        }
        val payload = body.copyOfRange(offset, body.size)
        if (qos == 1 && packetId > 0) {
            sendPacket(fd, byteArrayOf(0x40, 0x02, (packetId ushr 8).toByte(), packetId.toByte()))
        }
        onMessage(topic, payload)
    }

    private suspend fun sendConnect(fd: Int) {
        var flags = 0x02 // clean session
        if (config.username != null) flags = flags or 0x80
        if (config.password != null) flags = flags or 0x40
        val payload = PacketBuilder()
        payload.mqttString("MQTT")
        payload.byte(4) // MQTT 3.1.1
        payload.byte(flags)
        payload.u16(config.keepAliveSeconds)
        payload.mqttString("esp32-control-server-" + randomId(6))
        config.username?.let {
            payload.mqttString(it)
            if (config.password != null) payload.mqttString(config.password)
        }
        val body = payload.toByteArray()
        sendPacket(fd, fixedHeader(0x10, body))
    }

    private suspend fun readConnack(fd: Int) {
        val first = readByte(fd)
        val remaining = readRemainingLength(fd)
        val body = readExact(fd, remaining)
        if (((first.toInt() ushr 4) and 0x0F) != 2) {
            throw MqttUnavailableException("MQTT expected CONNACK")
        }
        if (body.size < 2) throw MqttUnavailableException("MQTT malformed CONNACK")
        if (body[1].toInt() != 0) {
            throw MqttUnavailableException("MQTT CONNACK rejected: ${body[1]}")
        }
    }

    private suspend fun sendSubscribe(fd: Int) {
        val payload = PacketBuilder()
        payload.u16(nextPacketId().toInt())
        payload.mqttString(config.topicPrefix + "/#")
        payload.byte(1) // requested QoS 1
        sendPacket(fd, fixedHeader(0x82, payload.toByteArray()))
    }

    private suspend fun readSubAck(fd: Int) {
        val first = readByte(fd)
        val remaining = readRemainingLength(fd)
        readExact(fd, remaining)
        if (((first.toInt() ushr 4) and 0x0F) != 9) {
            throw MqttUnavailableException("MQTT expected SUBACK")
        }
    }

    private suspend fun sendPublish(fd: Int, topic: String, payload: ByteArray, packetId: Int) {
        val body = PacketBuilder()
        body.mqttString(topic)
        body.u16(packetId)
        body.bytes(payload)
        sendPacket(fd, fixedHeader(0x32, body.toByteArray()))
    }

    private suspend fun sendPacket(fd: Int, packet: ByteArray) {
        writeMutex.withLock {
            sendAll(fd, packet)
        }
    }

    private fun nextPacketId(): UShort {
        val value = nextPacketId
        nextPacketId = if (nextPacketId >= 65535) 1 else nextPacketId + 1
        return value.toUShort()
    }

    private suspend fun failPending() {
        pendingMutex.withLock {
            pendingAcks.values.forEach {
                it.completeExceptionally(MqttUnavailableException("MQTT connection closed"))
            }
            pendingAcks.clear()
        }
        while (true) {
            val message = outgoing.tryReceive().getOrNull() ?: break
            if (message is Outgoing.Publish) {
                message.ack.completeExceptionally(MqttUnavailableException("MQTT connection closed"))
            }
        }
    }

    private fun closeSocket() {
        val fd = socketFd
        socketFd = -1
        if (fd >= 0) {
            runCatching { shutdown(fd, 2) }
            runCatching { close(fd) }
        }
    }

    private sealed interface Outgoing {
        data class Publish(
            val topic: String,
            val payload: ByteArray,
            val packetId: Int,
            val ack: CompletableDeferred<Unit>,
        ) : Outgoing
    }

    companion object {
        private const val EAGAIN_ERRNO = 11
        private fun fixedHeader(first: Int, body: ByteArray): ByteArray {
            val builder = PacketBuilder()
            builder.byte(first)
            builder.varInt(body.size)
            builder.bytes(body)
            return builder.toByteArray()
        }

        private suspend fun readByte(fd: Int): Byte {
            return readExact(fd, 1)[0]
        }

        private suspend fun readRemainingLength(fd: Int): Int {
            var multiplier = 1
            var value = 0
            var count = 0
            while (true) {
                val encoded = readByte(fd).toInt() and 0xFF
                value += (encoded and 0x7F) * multiplier
                count++
                if (encoded and 0x80 == 0) break
                if (count >= 4) throw MqttUnavailableException("MQTT malformed remaining length")
                multiplier *= 128
            }
            return value
        }

        private suspend fun readExact(fd: Int, count: Int): ByteArray {
            if (count < 0 || count > 16 * 1024 * 1024) {
                throw MqttUnavailableException("MQTT packet too large")
            }
            if (count == 0) return ByteArray(0)
            val buffer = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = buffer.usePinned {
                    recv(fd, it.addressOf(offset), (count - offset).convert(), 0)
                }.toInt()
                if (read < 0) {
                    val err = errno
                    if (err == EINTR) continue
                    if (err == EAGAIN_ERRNO) {
                        throw MqttUnavailableException("MQTT read timeout")
                    }
                    throw MqttUnavailableException("MQTT recv failed: $err")
                }
                if (read == 0) throw MqttUnavailableException("MQTT connection closed")
                offset += read
            }
            return buffer
        }

        private fun sendAll(fd: Int, data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val written = data.usePinned {
                    send(fd, it.addressOf(offset), (data.size - offset).convert(), 0)
                }.toInt()
                if (written < 0) {
                    val err = errno
                    if (err == EINTR) continue
                    throw MqttUnavailableException("MQTT send failed: $err")
                }
                if (written == 0) throw MqttUnavailableException("MQTT send returned 0")
                offset += written
            }
        }

        private fun openSocket(host: String, port: Int): Int {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            if (fd < 0) throw MqttUnavailableException("MQTT socket() failed: ${errno}")
            try {
                memScoped {
                    val sendTimeout = alloc<timeval>()
                    sendTimeout.tv_sec = 5L
                    sendTimeout.tv_usec = 0
                    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, sendTimeout.ptr, sizeOf<timeval>().convert())

                    // The reader must stay blocked between PINGREQ replies.
                    // A short receive timeout would look like a dead socket.
                    val receiveTimeout = alloc<timeval>()
                    receiveTimeout.tv_sec = 120L
                    receiveTimeout.tv_usec = 0
                    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, receiveTimeout.ptr, sizeOf<timeval>().convert())

                    val hints = alloc<addrinfo>()
                    hints.ai_family = AF_INET
                    hints.ai_socktype = SOCK_STREAM
                    val result = alloc<CPointerVar<addrinfo>>()
                    val rc = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
                    if (rc != 0) {
                        throw MqttUnavailableException("MQTT getaddrinfo failed: $rc")
                    }
                    val info = result.value ?: throw MqttUnavailableException("MQTT no address")
                    try {
                        if (connect(fd, info.pointed.ai_addr, info.pointed.ai_addrlen) != 0) {
                            throw MqttUnavailableException("MQTT connect failed: ${errno}")
                        }
                    } finally {
                        freeaddrinfo(info)
                    }
                }
            } catch (e: Throwable) {
                close(fd)
                throw e
            }
            return fd
        }

        private fun randomId(bytes: Int): String {
            val data = kotlin.random.Random.nextBytes(bytes)
            return data.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
        }
    }
}

private class PacketBuilder {
    private var data = ByteArray(64)
    private var size = 0

    fun byte(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(data, size)
        size += value.size
    }

    fun u16(value: Int) {
        byte((value ushr 8) and 0xFF)
        byte(value and 0xFF)
    }

    fun mqttString(value: String) {
        val encoded = value.encodeToByteArray()
        u16(encoded.size)
        bytes(encoded)
    }

    fun varInt(value: Int) {
        var remaining = value
        do {
            var digit = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining > 0) digit = digit or 0x80
            byte(digit)
        } while (remaining > 0)
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var next = data.size * 2
        while (next < size + extra) next *= 2
        data = data.copyOf(next)
    }
}
