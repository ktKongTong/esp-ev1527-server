package com.esp32.control.gui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.Device
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.HealthResponse
import com.esp32.control.contract.LearningCandidate
import com.esp32.control.contract.LearningSession
import com.esp32.control.contract.LearningSlot
import com.esp32.control.contract.LearningState
import com.esp32.control.gui.api.ClientApiException
import com.esp32.control.gui.api.Esp32Api
import com.esp32.control.gui.api.newIdempotencyKey
import com.esp32.control.gui.settings.SettingsStore
import com.esp32.control.gui.settings.normalizeServerUrl
import com.esp32.control.gui.ui.BottomTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns all client state and the network work behind it. The composables only
 * render [state] and call the actions here, so configuration changes and
 * navigation do not restart polling or lose device state.
 */
internal data class AppUiState(
    val serverUrl: String? = null,
    val selectedTab: BottomTab = BottomTab.Devices,
    val health: HealthResponse? = null,
    val devices: List<Device> = emptyList(),
    val loading: Boolean = true,
    val pendingDeviceId: String? = null,
    val error: String? = null,
    val detailDeviceId: String? = null,
    val removalTargetId: String? = null,
    val learningVisible: Boolean = false,
    val learning: LearningSession? = null,
    val learningId: String? = null,
    val learningBusy: Boolean = false,
    val learningError: String? = null,
    val testMessage: String? = null,
    val completedDeviceName: String? = null,
) {
    val gatewayOnline: Boolean
        get() = health?.gateway?.state == GatewayState.ONLINE

    val detailDevice: Device?
        get() = detailDeviceId?.let { id -> devices.firstOrNull { it.id == id } }

    val removalTarget: Device?
        get() = removalTargetId?.let { id -> devices.firstOrNull { it.id == id } }
}

internal class AppViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var api: Esp32Api? = null
    private var learningPollJob: Job? = null

    // Work for the current server URL. Reconnecting cancels this scope so
    // in-flight requests cannot write results into the new connection's state.
    private var connectionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var connectionScope = CoroutineScope(viewModelScope.coroutineContext + connectionJob)

    // Serializes learning session create/cancel so a close followed by an
    // immediate reopen cannot hit the server's single-active-session limit.
    private val sessionMutex = Mutex()

    init {
        viewModelScope.launch {
            connect(settingsStore.serverUrl.first())
        }
    }

    fun selectTab(tab: BottomTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun saveServerUrl(value: String) {
        val normalized = normalizeServerUrl(value)
        // Reconnect immediately, then persist: the UI should not wait for disk
        // I/O before showing the new server state.
        connect(normalized)
        viewModelScope.launch {
            settingsStore.setServerUrl(normalized)
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun openDetails(deviceId: String) {
        _state.update { it.copy(detailDeviceId = deviceId) }
    }

    fun closeDetails() {
        _state.update { it.copy(detailDeviceId = null) }
    }

    fun requestRemoval(deviceId: String) {
        _state.update { it.copy(removalTargetId = deviceId) }
    }

    fun dismissRemoval() {
        _state.update { it.copy(removalTargetId = null) }
    }

    fun sendCommand(deviceId: String, action: CommandAction) {
        if (_state.value.pendingDeviceId != null) return
        val client = api ?: return
        _state.update { it.copy(pendingDeviceId = deviceId) }
        connectionScope.launch {
            try {
                client.sendCommand(
                    deviceId = deviceId,
                    action = action,
                    idempotencyKey = newIdempotencyKey(),
                )
                _state.update { it.copy(error = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = t.readableMessage()) }
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    refreshDevices()
                }
            } finally {
                if (api === client) {
                    _state.update { it.copy(pendingDeviceId = null) }
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        val client = api ?: return
        _state.update { it.copy(removalTargetId = null) }
        connectionScope.launch {
            try {
                client.removeDevice(deviceId)
                dropDevice(deviceId)
                _state.update { it.copy(error = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    dropDevice(deviceId)
                } else {
                    _state.update { it.copy(error = t.readableMessage()) }
                }
            }
        }
    }

    fun openLearning() {
        _state.update {
            it.copy(
                learningVisible = true,
                learningError = null,
                testMessage = null,
                completedDeviceName = null,
            )
        }
        if (_state.value.gatewayOnline) {
            maybeStartLearning()
        } else {
            // Refresh health now instead of waiting for the 30s poll; the
            // gateway may already be back and the session can start at once.
            val client = api ?: return
            connectionScope.launch {
                runCatching { client.health() }.onSuccess(::applyHealth)
            }
        }
    }

    fun closeLearning() {
        val session = _state.value.learning
        learningPollJob?.cancel()
        _state.update {
            it.copy(
                learningVisible = false,
                learningId = null,
                learning = null,
                learningError = null,
                testMessage = null,
                completedDeviceName = null,
            )
        }
        if (session != null && session.state !in TERMINAL_LEARNING_STATES) {
            val client = api ?: return
            connectionScope.launch {
                sessionMutex.withLock {
                    runCatching { client.cancelLearning(session.id) }
                }
            }
        }
    }

    fun startLearning() {
        val client = api ?: return
        if (_state.value.learningBusy) return
        _state.update { it.copy(learningBusy = true, learningError = null) }
        connectionScope.launch {
            try {
                sessionMutex.withLock {
                    val session = client.startLearning()
                    if (_state.value.learningVisible) {
                        _state.update {
                            it.copy(
                                learning = session,
                                learningId = session.id,
                                testMessage = null,
                                completedDeviceName = null,
                            )
                        }
                        startLearningPoll(session.id)
                    } else {
                        // Sheet dismissed while the request was in flight.
                        runCatching { client.cancelLearning(session.id) }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (_state.value.learningVisible) {
                    _state.update { it.copy(learningError = t.readableMessage()) }
                }
            } finally {
                if (api === client) {
                    _state.update { it.copy(learningBusy = false) }
                }
            }
        }
    }

    fun acceptCandidate(candidate: LearningCandidate) {
        val session = _state.value.learning ?: return
        if (_state.value.learningBusy) return
        val client = api ?: return
        _state.update { it.copy(learningBusy = true) }
        connectionScope.launch {
            try {
                val updated = client.acceptCandidate(session.id, candidate.id)
                _state.update { it.copy(learning = updated, learningError = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(learningError = t.readableMessage()) }
            } finally {
                if (api === client) {
                    _state.update { it.copy(learningBusy = false) }
                }
            }
        }
    }

    fun rescan() {
        val session = _state.value.learning ?: return
        if (_state.value.learningBusy) return
        val client = api ?: return
        _state.update { it.copy(learningBusy = true) }
        connectionScope.launch {
            try {
                val updated = client.rescan(session.id)
                _state.update {
                    it.copy(learning = updated, learningError = null, testMessage = null)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(learningError = t.readableMessage()) }
            } finally {
                if (api === client) {
                    _state.update { it.copy(learningBusy = false) }
                }
            }
        }
    }

    fun test(slot: LearningSlot) {
        val session = _state.value.learning ?: return
        if (_state.value.learningBusy) return
        val client = api ?: return
        _state.update { it.copy(learningBusy = true) }
        connectionScope.launch {
            try {
                client.testLearning(session.id, slot)
                _state.update {
                    it.copy(
                        testMessage = if (slot == LearningSlot.TURN_ON) {
                            "已试发开码，请观察设备是否动作。"
                        } else {
                            "已试发关码，请观察设备是否停止。"
                        },
                        learningError = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(learningError = t.readableMessage()) }
            } finally {
                if (api === client) {
                    _state.update { it.copy(learningBusy = false) }
                }
            }
        }
    }

    fun confirmLearning(name: String, location: String) {
        val session = _state.value.learning ?: return
        if (_state.value.learningBusy) return
        val client = api ?: return
        _state.update { it.copy(learningBusy = true) }
        connectionScope.launch {
            try {
                val confirmation = client.confirmLearning(session.id, name, location)
                learningPollJob?.cancel()
                _state.update {
                    it.copy(
                        learning = confirmation.session,
                        learningId = null,
                        completedDeviceName = confirmation.device.name,
                        learningError = null,
                    )
                }
                val devices = client.devices()
                _state.update { it.copy(devices = devices) }
                val health = client.health()
                _state.update { it.copy(health = health) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(learningError = t.readableMessage()) }
            } finally {
                if (api === client) {
                    _state.update { it.copy(learningBusy = false) }
                }
            }
        }
    }

    fun dismissLearningError() {
        _state.update { it.copy(learningError = null, error = null) }
    }

    override fun onCleared() {
        connectionJob.cancel()
        api?.close()
    }

    private fun connect(url: String) {
        connectionJob.cancel()
        connectionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        connectionScope = CoroutineScope(viewModelScope.coroutineContext + connectionJob)
        learningPollJob = null
        api?.close()
        val client = Esp32Api(url)
        api = client
        _state.update {
            it.copy(
                serverUrl = url,
                health = null,
                devices = emptyList(),
                loading = true,
                pendingDeviceId = null,
                error = null,
                detailDeviceId = null,
                removalTargetId = null,
                learningVisible = false,
                learning = null,
                learningId = null,
                learningBusy = false,
                learningError = null,
                testMessage = null,
                completedDeviceName = null,
            )
        }
        loadAll(client)
        startHealthPolling(client)
    }

    private fun loadAll(client: Esp32Api) {
        connectionScope.launch {
            try {
                val health = client.health()
                _state.update { it.copy(health = health) }
                val devices = client.devices()
                _state.update { it.copy(devices = devices, error = null, loading = false) }
                maybeStartLearning()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = t.readableMessage(), loading = false) }
            }
        }
    }

    private fun refreshDevices() {
        val client = api ?: return
        connectionScope.launch {
            try {
                val devices = client.devices()
                _state.update { it.copy(devices = devices) }
                val health = client.health()
                applyHealth(health)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = t.readableMessage()) }
            }
        }
    }

    private fun startHealthPolling(client: Esp32Api) {
        connectionScope.launch {
            while (isActive) {
                delay(30_000)
                runCatching { client.health() }.onSuccess(::applyHealth)
            }
        }
    }

    private fun startLearningPoll(sessionId: String) {
        val client = api ?: return
        learningPollJob?.cancel()
        learningPollJob = connectionScope.launch {
            while (isActive) {
                delay(1_000)
                try {
                    val session = client.learning(sessionId)
                    _state.update { it.copy(learning = session) }
                    if (session.state in TERMINAL_LEARNING_STATES) {
                        _state.update { it.copy(learningId = null) }
                        if (session.state == LearningState.COMPLETED) {
                            runCatching { client.devices() }.onSuccess { devices ->
                                _state.update { it.copy(devices = devices) }
                            }
                        }
                        break
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is ClientApiException && t.status == 404) {
                        // Sessions live in server memory and die with the process.
                        _state.update {
                            it.copy(
                                learningId = null,
                                learning = null,
                                learningVisible = false,
                                learningError = null,
                                testMessage = null,
                                completedDeviceName = null,
                                error = "学习会话已失效，请重新开始",
                            )
                        }
                        break
                    }
                    // A failed poll is transient: keep polling and let the
                    // next tick resync.
                }
            }
        }
    }

    private fun maybeStartLearning() {
        val current = _state.value
        if (current.learningVisible &&
            current.gatewayOnline &&
            current.learning == null &&
            current.learningId == null &&
            !current.learningBusy
        ) {
            startLearning()
        }
    }

    private fun applyHealth(health: HealthResponse) {
        _state.update { it.copy(health = health) }
        maybeStartLearning()
    }

    private fun dropDevice(deviceId: String) {
        _state.update { current ->
            current.copy(
                devices = current.devices.filterNot { it.id == deviceId },
                detailDeviceId = current.detailDeviceId?.takeUnless { it == deviceId },
                removalTargetId = current.removalTargetId?.takeUnless { it == deviceId },
            )
        }
    }
}

private fun Throwable.readableMessage(): String {
    if (this is ClientApiException) {
        return "$code：$message" + requestId?.let { "（request $it）" }.orEmpty()
    }
    // Transport failures carry English platform messages that mean nothing to
    // the user; keep them out of the UI.
    return "网络连接异常，请稍后重试"
}

private val TERMINAL_LEARNING_STATES = setOf(
    LearningState.COMPLETED,
    LearningState.CANCELLED,
    LearningState.EXPIRED,
    LearningState.FAILED,
)
