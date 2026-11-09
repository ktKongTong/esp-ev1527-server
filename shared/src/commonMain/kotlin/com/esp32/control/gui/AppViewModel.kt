package com.esp32.control.gui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.Device
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.Group
import com.esp32.control.contract.HealthResponse
import com.esp32.control.contract.LearningCandidate
import com.esp32.control.contract.LearningSession
import com.esp32.control.contract.LearningSlot
import com.esp32.control.contract.LearningState
import com.esp32.control.contract.Schedule
import com.esp32.control.contract.ScheduleTarget
import com.esp32.control.gui.api.ClientApiException
import com.esp32.control.gui.api.isGone
import com.esp32.control.gui.data.Esp32Repository
import com.esp32.control.gui.data.NotConnectedException
import com.esp32.control.gui.settings.SettingsStore
import com.esp32.control.gui.settings.normalizeServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class RefreshUiState(
    val hasSnapshot: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
)

internal data class LearningUiState(
    val visible: Boolean = false,
    val session: LearningSession? = null,
    val pollingSessionId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val testMessage: String? = null,
    val completedDeviceName: String? = null,
)

/**
 * Screen state and user actions. All data access goes through
 * [Esp32Repository]; this class does not create clients or touch the cache.
 * Dialogs and editor sheets keep their open state and form fields locally.
 */
internal data class AppUiState(
    val serverUrl: String? = null,
    val apiToken: String = "",
    val health: HealthResponse? = null,
    val refresh: RefreshUiState = RefreshUiState(),
    val pendingDeviceId: String? = null,
    val pendingGroupId: String? = null,
    val pendingScheduleId: String? = null,
    val learning: LearningUiState = LearningUiState(),
) {
    /** Skeleton only before the first snapshot or a full-screen cache error. */
    val loading: Boolean
        get() = !refresh.hasSnapshot && refresh.error == null

    val gatewayOnline: Boolean
        get() = health?.gateway?.state == GatewayState.ONLINE
}

internal class AppViewModel(
    private val settingsStore: SettingsStore,
    private val repository: Esp32Repository,
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    // Cached reads stay Flows end to end: the UI collects them where they are
    // used instead of the ViewModel copying them into [AppUiState].
    val devices: StateFlow<List<Device>> =
        repository.devices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val groups: StateFlow<List<Group>> =
        repository.groups.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val schedules: StateFlow<List<Schedule>> =
        repository.schedules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // One-shot user-facing messages. Errors and notices must not be stored in
    // the screen state: a snackbar shows them once and the state stays clean.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var learningPollJob: Job? = null
    private var refreshJob: Job? = null

    // Results from the previous server must not change the new connection's
    // UI. The repository guards its cache the same way.
    private var connectionEpoch = 0

    init {
        viewModelScope.launch {
            repository.lastSyncedAtEpochMs.collect { syncedAt ->
                _state.update {
                    it.copy(refresh = it.refresh.copy(hasSnapshot = syncedAt != null))
                }
            }
        }
        viewModelScope.launch {
            repository.health.collect { health ->
                _state.update { it.copy(health = health) }
                maybeStartLearning()
            }
        }
        viewModelScope.launch {
            connect(
                url = settingsStore.serverUrl.first(),
                token = settingsStore.apiToken.first(),
            )
        }
    }

    fun saveConnection(url: String, token: String) {
        val normalized = normalizeServerUrl(url)
        // Reconnect immediately, then persist: the UI should not wait for disk
        // I/O before showing the new server state.
        connect(normalized, token)
        viewModelScope.launch {
            settingsStore.setServerUrl(normalized)
            settingsStore.setApiToken(token)
        }
    }

    /** Pull-to-refresh entry point. Concurrent calls share the running refresh. */
    fun refresh() {
        startRefresh(force = false)
    }

    /**
     * Saves the device and completes with null, or with a user-readable error.
     * The caller owns the dialog and its busy state.
     */
    fun saveDevice(deviceId: String, name: String, location: String): Deferred<String?> =
        runWrite { repository.saveDevice(deviceId, name, location) }

    fun sendCommand(deviceId: String, action: CommandAction) {
        if (_state.value.pendingDeviceId != null) return
        val epoch = connectionEpoch
        _state.update { it.copy(pendingDeviceId = deviceId) }
        viewModelScope.launch {
            try {
                val error = repository.sendCommand(deviceId, action).exceptionOrNull()
                if (epoch == connectionEpoch && error != null) {
                    postMessage(error.readableMessage())
                    if (error.isGone()) startRefresh(force = true)
                }
            } finally {
                if (epoch == connectionEpoch) {
                    _state.update { it.copy(pendingDeviceId = null) }
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        launchRemove { repository.removeDevice(deviceId) }
    }

    /**
     * Saves the group and completes with null, or with a user-readable error.
     * The caller owns the sheet and its busy state.
     */
    fun saveGroup(
        groupId: String?,
        name: String,
        deviceIds: List<String>,
    ): Deferred<String?> = runWrite { repository.saveGroup(groupId, name, deviceIds) }

    fun removeGroup(groupId: String) {
        launchRemove { repository.removeGroup(groupId) }
    }

    fun sendGroupCommand(groupId: String, action: CommandAction) {
        if (_state.value.pendingGroupId != null) return
        val epoch = connectionEpoch
        _state.update { it.copy(pendingGroupId = groupId) }
        viewModelScope.launch {
            try {
                val result = repository.sendGroupCommand(groupId, action)
                if (epoch != connectionEpoch) return@launch
                result.fold(
                    onSuccess = { dispatched ->
                        postMessage(
                            buildString {
                                append("已下发 ")
                                append(dispatched.dispatchedCount)
                                append(" 台")
                                if (dispatched.failedCount > 0) {
                                    append("，")
                                    append(dispatched.failedCount)
                                    append(" 台失败（")
                                    append(dispatched.failures.joinToString { it.code })
                                    append("）")
                                }
                            },
                        )
                    },
                    onFailure = { error ->
                        postMessage(error.readableMessage())
                        if (error.isGone()) startRefresh(force = true)
                    },
                )
            } finally {
                if (epoch == connectionEpoch) {
                    _state.update { it.copy(pendingGroupId = null) }
                }
            }
        }
    }

    /**
     * Saves the schedule and completes with null, or with a user-readable
     * error. The caller owns the sheet and its busy state.
     */
    fun saveSchedule(
        scheduleId: String?,
        name: String,
        targetType: ScheduleTarget,
        targetId: String,
        action: CommandAction,
        rrule: String,
        timeZone: String,
        startsAtEpochMs: Long,
        enabled: Boolean,
    ): Deferred<String?> = runWrite {
        repository.saveSchedule(
            scheduleId = scheduleId,
            name = name,
            targetType = targetType,
            targetId = targetId,
            action = action,
            rrule = rrule,
            timeZone = timeZone,
            startsAtEpochMs = startsAtEpochMs,
            enabled = enabled,
        )
    }

    fun setScheduleEnabled(scheduleId: String, enabled: Boolean) {
        if (_state.value.pendingScheduleId != null) return
        val epoch = connectionEpoch
        _state.update { it.copy(pendingScheduleId = scheduleId) }
        viewModelScope.launch {
            try {
                val error = repository.setScheduleEnabled(scheduleId, enabled).exceptionOrNull()
                if (epoch == connectionEpoch && error != null) {
                    postMessage(error.readableMessage())
                    if (error.isGone()) startRefresh(force = true)
                }
            } finally {
                if (epoch == connectionEpoch) {
                    _state.update { it.copy(pendingScheduleId = null) }
                }
            }
        }
    }

    fun removeSchedule(scheduleId: String) {
        launchRemove { repository.removeSchedule(scheduleId) }
    }

    fun openLearning() {
        updateLearning {
            it.copy(
                visible = true,
                error = null,
                testMessage = null,
                completedDeviceName = null,
            )
        }
        if (_state.value.gatewayOnline) {
            maybeStartLearning()
        } else {
            // Refresh health now instead of waiting for a poll; the gateway may
            // already be back and the session can start at once.
            viewModelScope.launch {
                ignoreFailure { repository.refreshHealth() }
            }
        }
    }

    fun closeLearning() {
        val session = _state.value.learning.session
        learningPollJob?.cancel()
        learningPollJob = null
        _state.update { it.copy(learning = LearningUiState()) }
        if (session != null && session.state !in TERMINAL_LEARNING_STATES) {
            viewModelScope.launch {
                ignoreFailure { repository.cancelLearning(session.id) }
            }
        }
    }

    fun startLearning() {
        if (_state.value.learning.busy) return
        val epoch = connectionEpoch
        updateLearning { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = repository.startLearning()
                if (epoch != connectionEpoch) return@launch
                if (_state.value.learning.visible) {
                    updateLearning {
                        it.copy(
                            session = session,
                            pollingSessionId = session.id,
                            testMessage = null,
                            completedDeviceName = null,
                        )
                    }
                    startLearningPoll(session.id)
                } else {
                    // Sheet dismissed while the request was in flight.
                    ignoreFailure { repository.cancelLearning(session.id) }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (epoch == connectionEpoch && _state.value.learning.visible) {
                    updateLearning { it.copy(error = t.readableMessage()) }
                }
            } finally {
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(busy = false) }
                }
            }
        }
    }

    fun acceptCandidate(candidate: LearningCandidate) {
        val session = _state.value.learning.session ?: return
        if (_state.value.learning.busy) return
        val epoch = connectionEpoch
        updateLearning { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val updated = repository.acceptCandidate(session.id, candidate.id)
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(session = updated, error = null) }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(error = t.readableMessage()) }
                }
            } finally {
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(busy = false) }
                }
            }
        }
    }

    fun rescan() {
        val session = _state.value.learning.session ?: return
        if (_state.value.learning.busy) return
        val epoch = connectionEpoch
        updateLearning { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val updated = repository.rescan(session.id)
                if (epoch == connectionEpoch) {
                    updateLearning {
                        it.copy(session = updated, error = null, testMessage = null)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(error = t.readableMessage()) }
                }
            } finally {
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(busy = false) }
                }
            }
        }
    }

    fun test(slot: LearningSlot) {
        val session = _state.value.learning.session ?: return
        if (_state.value.learning.busy) return
        val epoch = connectionEpoch
        updateLearning { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repository.testLearning(session.id, slot)
                if (epoch == connectionEpoch) {
                    updateLearning {
                        it.copy(
                            testMessage = if (slot == LearningSlot.TURN_ON) {
                                "已试发开码，请观察设备是否动作。"
                            } else {
                                "已试发关码，请观察设备是否停止。"
                            },
                            error = null,
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(error = t.readableMessage()) }
                }
            } finally {
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(busy = false) }
                }
            }
        }
    }

    fun confirmLearning(name: String, location: String) {
        val session = _state.value.learning.session ?: return
        if (_state.value.learning.busy) return
        val epoch = connectionEpoch
        updateLearning { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val confirmation = repository.confirmLearning(session.id, name, location)
                learningPollJob?.cancel()
                learningPollJob = null
                if (epoch == connectionEpoch) {
                    updateLearning {
                        it.copy(
                            session = confirmation.session,
                            pollingSessionId = null,
                            completedDeviceName = confirmation.device.name,
                            error = null,
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(error = t.readableMessage()) }
                }
            } finally {
                if (epoch == connectionEpoch) {
                    updateLearning { it.copy(busy = false) }
                }
            }
        }
    }

    override fun onCleared() {
        repository.close()
        _messages.close()
    }

    private fun connect(url: String, token: String) {
        connectionEpoch += 1
        learningPollJob?.cancel()
        learningPollJob = null
        refreshJob?.cancel()
        refreshJob = null
        repository.connect(url, token)
        _state.update {
            it.copy(
                serverUrl = url,
                apiToken = token,
                health = null,
                refresh = it.refresh.copy(running = true, error = null),
                pendingDeviceId = null,
                pendingGroupId = null,
                pendingScheduleId = null,
                learning = LearningUiState(),
            )
        }
        viewModelScope.launch { pull() }
    }

    private fun startRefresh(force: Boolean) {
        if (!force && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { pull() }
    }

    private suspend fun pull() {
        _state.update { it.copy(refresh = it.refresh.copy(running = true, error = null)) }
        repository.refresh().fold(
            onSuccess = {
                _state.update { it.copy(refresh = it.refresh.copy(running = false, error = null)) }
                maybeStartLearning()
            },
            onFailure = { t ->
                val message = t.readableMessage()
                val hasSnapshot = _state.value.refresh.hasSnapshot
                _state.update {
                    it.copy(refresh = it.refresh.copy(running = false, error = message))
                }
                // With a snapshot the content stays visible; report the
                // failure through the snackbar instead of a full-screen state.
                if (hasSnapshot) postMessage(message)
            },
        )
    }

    /**
     * Runs a write and completes with null, or with a user-readable error.
     * A write to a resource that disappeared also refreshes the snapshot.
     */
    private fun runWrite(block: suspend () -> Result<Unit>): Deferred<String?> {
        val epoch = connectionEpoch
        return viewModelScope.async {
            val error = block().exceptionOrNull()
            if (epoch != connectionEpoch) return@async null
            if (error == null) return@async null
            if (error.isGone()) {
                postMessage(error.readableMessage())
                startRefresh(force = true)
            }
            error.readableMessage()
        }
    }

    private fun launchRemove(block: suspend () -> Result<Unit>) {
        val epoch = connectionEpoch
        viewModelScope.launch {
            val error = block().exceptionOrNull()
            if (epoch != connectionEpoch) return@launch
            when {
                error == null || error.isGone() -> startRefresh(force = true)
                else -> postMessage(error.readableMessage())
            }
        }
    }

    private fun startLearningPoll(sessionId: String) {
        learningPollJob?.cancel()
        val epoch = connectionEpoch
        learningPollJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                try {
                    val session = repository.learning(sessionId)
                    if (epoch != connectionEpoch) return@launch
                    updateLearning { it.copy(session = session) }
                    if (session.state in TERMINAL_LEARNING_STATES) {
                        updateLearning { it.copy(pollingSessionId = null) }
                        if (session.state == LearningState.COMPLETED) {
                            startRefresh(force = true)
                        }
                        break
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is ClientApiException && t.status == 404) {
                        // Sessions live in server memory and die with the process.
                        _state.update { it.copy(learning = LearningUiState()) }
                        postMessage("学习会话已失效，请重新开始")
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
        if (current.learning.visible &&
            current.gatewayOnline &&
            current.learning.session == null &&
            current.learning.pollingSessionId == null &&
            !current.learning.busy
        ) {
            startLearning()
        }
    }

    private fun updateLearning(transform: (LearningUiState) -> LearningUiState) {
        _state.update { it.copy(learning = transform(it.learning)) }
    }

    private fun postMessage(message: String) {
        _messages.trySend(message)
    }
}

private suspend fun ignoreFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
    }
}

private fun Throwable.readableMessage(): String {
    if (this is NotConnectedException) {
        return "尚未连接服务端"
    }
    if (this is ClientApiException) {
        if (code == "unauthorized") {
            return "访问令牌无效或缺失，请在设置中更新" +
                requestId?.let { "（request $it）" }.orEmpty()
        }
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
