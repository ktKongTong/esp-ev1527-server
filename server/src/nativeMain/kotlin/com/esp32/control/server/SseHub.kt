package com.esp32.control.server

import com.esp32.control.contract.ServerEvent
import com.esp32.control.contract.StreamResetEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * In-memory SSE fan-out with a 256-event replay cache. A client that presents
 * an unknown Last-Event-ID receives a stream.reset before live events.
 */
class SseHub {
    private val events = MutableSharedFlow<ServerEvent>(
        replay = 256,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    fun emit(event: ServerEvent) {
        events.tryEmit(event)
    }

    suspend fun stream(
        lastEventId: String?,
        write: suspend (ServerEvent) -> Unit,
    ) = coroutineScope {
        val snapshot = events.replayCache
        val initialIds = snapshot.mapTo(mutableSetOf()) { it.eventId }
        val channel = Channel<ServerEvent>(UNLIMITED)

        if (lastEventId == null) {
            // A fresh SSE client gets a live tail; snapshots still come from
            // the REST endpoints.
        } else {
            val index = snapshot.indexOfLast { it.eventId == lastEventId }
            if (index >= 0) {
                for (i in index + 1 until snapshot.size) {
                    channel.send(snapshot[i])
                }
            } else {
                channel.send(
                    StreamResetEvent(
                        eventId = newId("evt"),
                        atEpochMs = nowEpochMs(),
                        reason = "last_event_id_not_in_buffer",
                    ),
                )
            }
        }

        val collector = launch {
            events.collect { event ->
                if (event.eventId in initialIds) return@collect
                channel.send(event)
            }
        }

        try {
            while (isActive) {
                write(channel.receive())
            }
        } finally {
            collector.cancel()
        }
    }

}
