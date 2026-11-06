package com.esp32.control.server.mqtt

import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.GatewayStatus
import com.esp32.control.contract.ReceiverState
import com.esp32.control.contract.ReceiverStatus
import com.esp32.control.server.Log
import com.esp32.control.server.nowEpochMs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime gateway and receiver state shared by the ingest, command, learning
 * and health paths. This is intentionally not persisted: both values describe
 * the live MQTT link and reset when the process restarts.
 */
class GatewayStatusStore(
    private val gatewayClientId: String,
    startedAtEpochMs: Long = nowEpochMs(),
) {
    private val mutex = Mutex()

    private var state = GatewayState.UNKNOWN
    private var version: String? = null
    private var changedAtEpochMs = startedAtEpochMs
    private var receiverState = ReceiverState.UNKNOWN
    private var receiverLastCaptureAtEpochMs: Long? = null
    private var receiverCaptureCount = 0L

    suspend fun snapshot(): GatewayStatus = mutex.withLock { snapshotLocked() }

    suspend fun isOnline(): Boolean = mutex.withLock { state == GatewayState.ONLINE }

    /**
     * The broker connection alone does not make the gateway online: only the
     * retained LWT/version message does. Returns true when state or version
     * changed, so callers can run transition side effects once.
     */
    suspend fun setGateway(state: GatewayState, version: String?): Boolean =
        mutex.withLock {
            val now = nowEpochMs()
            val previousState = this.state
            val stateChanged = previousState != state
            val versionChanged = version != null && version != this.version
            if (!stateChanged && !versionChanged) {
                return@withLock false
            }

            this.state = state
            if (version != null) this.version = version
            changedAtEpochMs = now

            if (state == GatewayState.ONLINE && previousState != GatewayState.ONLINE) {
                receiverState = ReceiverState.UNKNOWN
                receiverLastCaptureAtEpochMs = null
                receiverCaptureCount = 0
            } else if (state != GatewayState.ONLINE) {
                receiverState = ReceiverState.UNKNOWN
            }

            Log.info {
                "[gateway] $previousState -> $state clientId=$gatewayClientId " +
                    "version=${this.version ?: "n/a"}"
            }
            true
        }

    suspend fun onCapture(now: Long) {
        mutex.withLock {
            receiverState = ReceiverState.RECEIVING
            receiverLastCaptureAtEpochMs = now
            receiverCaptureCount += 1
        }
    }

    private fun snapshotLocked(): GatewayStatus = GatewayStatus(
        state = state,
        version = version,
        clientId = gatewayClientId,
        lastChangedAtEpochMs = changedAtEpochMs,
        receiver = ReceiverStatus(
            state = receiverState,
            lastCaptureAtEpochMs = receiverLastCaptureAtEpochMs,
            captureCount = receiverCaptureCount,
        ),
    )
}
