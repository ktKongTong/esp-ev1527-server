package com.esp32.control.gui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.CreateScheduleRequest
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
import com.esp32.control.contract.UpdateScheduleRequest
import com.esp32.control.gui.api.ClientApiException
import com.esp32.control.gui.api.Esp32Api
import com.esp32.control.gui.api.newIdempotencyKey
import com.esp32.control.gui.settings.SettingsStore
import com.esp32.control.gui.settings.normalizeServerUrl
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


//
/**
 * Owns all client state and the network work behind it. The composables only
 * render [state] and call the actions here, so configuration changes and
 * navigation do not restart polling or lose device state.
 */
internal data class AppUiState(
    val serverUrl: String? = null,
    val apiToken: String = "",
    val health: HealthResponse? = null,
    val devices: List<Device> = emptyList(),
    val groups: List<Group> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val loading: Boolean = true,
    val pendingDeviceId: String? = null,
    val error: String? = null,
    val detailDeviceId: String? = null,
    val editingDeviceId: String? = null,
    val editBusy: Boolean = false,
    val editError: String? = null,
    val removalTargetId: String? = null,
    val pendingGroupId: String? = null,
    val groupNotice: String? = null,
    val groupEditorVisible: Boolean = false,
    val groupEditorTarget: Group? = null,
    val groupBusy: Boolean = false,
    val groupError: String? = null,
    val groupRemovalTargetId: String? = null,
    val pendingScheduleId: String? = null,
    val scheduleEditorVisible: Boolean = false,
    val scheduleEditorTarget: Schedule? = null,
    val scheduleBusy: Boolean = false,
    val scheduleError: String? = null,
    val scheduleRemovalTargetId: String? = null,
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

    val editingDevice: Device?
        get() = editingDeviceId?.let { id -> devices.firstOrNull { it.id == id } }

    val removalTarget: Device?
        get() = removalTargetId?.let { id -> devices.firstOrNull { it.id == id } }

    val groupRemovalTarget: Group?
        get() = groupRemovalTargetId?.let { id -> groups.firstOrNull { it.id == id } }

    val scheduleRemovalTarget: Schedule?
        get() = scheduleRemovalTargetId?.let { id -> schedules.firstOrNull { it.id == id } }
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

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun openDetails(deviceId: String) {
        _state.update { it.copy(detailDeviceId = deviceId) }
    }

    fun closeDetails() {
        _state.update { it.copy(detailDeviceId = null) }
    }

    fun openEditor(deviceId: String) {
        _state.update { it.copy(editingDeviceId = deviceId, editError = null) }
    }

    fun closeEditor() {
        if (_state.value.editBusy) return
        _state.update { it.copy(editingDeviceId = null, editError = null) }
    }

    fun saveDevice(deviceId: String, name: String, location: String) {
        if (_state.value.editBusy) return
        val client = api ?: return
        _state.update { it.copy(editBusy = true, editError = null) }
        connectionScope.launch {
            try {
                val updated = client.updateDevice(deviceId, name, location)
                _state.update { current ->
                    current.copy(
                        devices = current.devices.map { device ->
                            if (device.id == updated.id) updated else device
                        },
                        editingDeviceId = null,
                        editBusy = false,
                        editError = null,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    dropDevice(deviceId)
                    _state.update {
                        it.copy(editBusy = false, editError = null, error = t.readableMessage())
                    }
                } else {
                    _state.update { it.copy(editBusy = false, editError = t.readableMessage()) }
                }
            }
        }
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

    fun openGroupEditor(groupId: String? = null) {
        val target = groupId?.let { id -> _state.value.groups.firstOrNull { it.id == id } }
        if (groupId != null && target == null) return
        _state.update {
            it.copy(
                groupEditorVisible = true,
                groupEditorTarget = target,
                groupError = null,
            )
        }
    }

    fun closeGroupEditor() {
        if (_state.value.groupBusy) return
        _state.update {
            it.copy(groupEditorVisible = false, groupEditorTarget = null, groupError = null)
        }
    }

    fun saveGroup(groupId: String?, name: String, deviceIds: List<String>) {
        if (_state.value.groupBusy) return
        val client = api ?: return
        _state.update { it.copy(groupBusy = true, groupError = null) }
        connectionScope.launch {
            try {
                val saved = if (groupId == null) {
                    client.createGroup(name, deviceIds)
                } else {
                    client.updateGroup(groupId, name, deviceIds)
                }
                _state.update { current ->
                    val exists = current.groups.any { it.id == saved.id }
                    current.copy(
                        groups = if (exists) {
                            current.groups.map { if (it.id == saved.id) saved else it }
                        } else {
                            current.groups + saved
                        },
                        groupEditorVisible = false,
                        groupEditorTarget = null,
                        groupBusy = false,
                        groupError = null,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    refreshGroups()
                }
                _state.update { it.copy(groupBusy = false, groupError = t.readableMessage()) }
            }
        }
    }

    fun requestGroupRemoval(groupId: String) {
        _state.update { it.copy(groupRemovalTargetId = groupId) }
    }

    fun dismissGroupRemoval() {
        _state.update { it.copy(groupRemovalTargetId = null) }
    }

    fun removeGroup(groupId: String) {
        val client = api ?: return
        _state.update { it.copy(groupRemovalTargetId = null) }
        connectionScope.launch {
            try {
                client.removeGroup(groupId)
                dropGroup(groupId)
                _state.update { it.copy(error = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    dropGroup(groupId)
                } else {
                    _state.update { it.copy(error = t.readableMessage()) }
                }
            }
        }
    }

    fun sendGroupCommand(groupId: String, action: CommandAction) {
        if (_state.value.pendingGroupId != null) return
        val client = api ?: return
        _state.update { it.copy(pendingGroupId = groupId, groupNotice = null) }
        connectionScope.launch {
            try {
                val result = client.sendGroupCommand(
                    groupId = groupId,
                    action = action,
                    idempotencyKey = newIdempotencyKey(),
                )
                val notice = buildString {
                    append("已下发 ")
                    append(result.dispatchedCount)
                    append(" 台")
                    if (result.failedCount > 0) {
                        append("，")
                        append(result.failedCount)
                        append(" 台失败（")
                        append(result.failures.joinToString { it.code })
                        append("）")
                    }
                }
                _state.update { it.copy(groupNotice = notice, error = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = t.readableMessage()) }
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    refreshGroups()
                }
            } finally {
                if (api === client) {
                    _state.update { it.copy(pendingGroupId = null) }
                }
            }
        }
    }

    fun openScheduleEditor(scheduleId: String? = null) {
        val target = scheduleId?.let { id -> _state.value.schedules.firstOrNull { it.id == id } }
        if (scheduleId != null && target == null) return
        _state.update {
            it.copy(
                scheduleEditorVisible = true,
                scheduleEditorTarget = target,
                scheduleError = null,
            )
        }
    }

    fun closeScheduleEditor() {
        if (_state.value.scheduleBusy) return
        _state.update {
            it.copy(
                scheduleEditorVisible = false,
                scheduleEditorTarget = null,
                scheduleError = null,
            )
        }
    }

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
    ) {
        if (_state.value.scheduleBusy) return
        val client = api ?: return
        val existing = scheduleId?.let { id -> _state.value.schedules.firstOrNull { it.id == id } }
        val update = existing?.let {
            UpdateScheduleRequest(
                name = name.takeIf { value -> value != it.name },
                targetType = targetType.takeIf { value -> value != it.targetType },
                targetId = targetId.takeIf { value -> value != it.targetId },
                action = action.takeIf { value -> value != it.action },
                rrule = rrule.takeIf { value -> value != it.rrule },
                timeZone = timeZone.takeIf { value -> value != it.timeZone },
                startsAtEpochMs = startsAtEpochMs.takeIf { value -> value != it.startsAtEpochMs },
                enabled = enabled.takeIf { value -> value != it.enabled },
            )
        }
        if (update != null && update.isEmpty()) {
            closeScheduleEditor()
            return
        }
        _state.update { it.copy(scheduleBusy = true, scheduleError = null) }
        connectionScope.launch {
            try {
                val saved = if (scheduleId == null) {
                    client.createSchedule(
                        CreateScheduleRequest(
                            name = name,
                            targetType = targetType,
                            targetId = targetId,
                            action = action,
                            rrule = rrule,
                            timeZone = timeZone,
                            startsAtEpochMs = startsAtEpochMs,
                            enabled = enabled,
                        ),
                    )
                } else {
                    client.updateSchedule(scheduleId, update ?: UpdateScheduleRequest())
                }
                _state.update { current ->
                    val exists = current.schedules.any { it.id == saved.id }
                    current.copy(
                        schedules = if (exists) {
                            current.schedules.map { if (it.id == saved.id) saved else it }
                        } else {
                            current.schedules + saved
                        },
                        scheduleEditorVisible = false,
                        scheduleEditorTarget = null,
                        scheduleBusy = false,
                        scheduleError = null,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    refreshSchedules()
                }
                _state.update { it.copy(scheduleBusy = false, scheduleError = t.readableMessage()) }
            }
        }
    }

    fun setScheduleEnabled(scheduleId: String, enabled: Boolean) {
        if (_state.value.pendingScheduleId != null) return
        val client = api ?: return
        _state.update { it.copy(pendingScheduleId = scheduleId) }
        connectionScope.launch {
            try {
                val saved = client.updateSchedule(
                    scheduleId,
                    UpdateScheduleRequest(enabled = enabled),
                )
                _state.update { current ->
                    current.copy(
                        schedules = current.schedules.map {
                            if (it.id == saved.id) saved else it
                        },
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.update { it.copy(error = t.readableMessage()) }
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    refreshSchedules()
                }
            } finally {
                if (api === client) {
                    _state.update { it.copy(pendingScheduleId = null) }
                }
            }
        }
    }

    fun requestScheduleRemoval(scheduleId: String) {
        _state.update { it.copy(scheduleRemovalTargetId = scheduleId) }
    }

    fun dismissScheduleRemoval() {
        _state.update { it.copy(scheduleRemovalTargetId = null) }
    }

    fun removeSchedule(scheduleId: String) {
        val client = api ?: return
        _state.update { it.copy(scheduleRemovalTargetId = null) }
        connectionScope.launch {
            try {
                client.removeSchedule(scheduleId)
                dropSchedule(scheduleId)
                _state.update { it.copy(error = null) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    dropSchedule(scheduleId)
                } else {
                    _state.update { it.copy(error = t.readableMessage()) }
                }
            }
        }
    }

    fun dismissGroupNotice() {
        _state.update { it.copy(groupNotice = null) }
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

    override fun onCleared() {
        connectionJob.cancel()
        api?.close()
    }

    private fun connect(url: String, token: String) {
        connectionJob.cancel()
        connectionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        connectionScope = CoroutineScope(viewModelScope.coroutineContext + connectionJob)
        learningPollJob = null
        api?.close()
        val client = Esp32Api(baseUrl = url, token = token)
        api = client
        _state.update {
            it.copy(
                serverUrl = url,
                apiToken = token,
                health = null,
                devices = emptyList(),
                groups = emptyList(),
                schedules = emptyList(),
                loading = true,
                pendingDeviceId = null,
                error = null,
                detailDeviceId = null,
                editingDeviceId = null,
                editBusy = false,
                editError = null,
                removalTargetId = null,
                pendingGroupId = null,
                groupNotice = null,
                groupEditorVisible = false,
                groupEditorTarget = null,
                groupBusy = false,
                groupError = null,
                groupRemovalTargetId = null,
                pendingScheduleId = null,
                scheduleEditorVisible = false,
                scheduleEditorTarget = null,
                scheduleBusy = false,
                scheduleError = null,
                scheduleRemovalTargetId = null,
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
        startPolling(client)
    }

    private fun loadAll(client: Esp32Api) {
        connectionScope.launch {
            try {
                val health = client.health()
                _state.update { it.copy(health = health) }
                val devices = client.devices()
                val groups = client.groups().groups
                val schedules = client.schedules().schedules
                _state.update {
                    it.copy(
                        devices = devices,
                        groups = groups,
                        schedules = schedules,
                        error = null,
                        loading = false,
                    )
                }
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

    private fun startPolling(client: Esp32Api) {
        connectionScope.launch {
            while (isActive) {
                delay(30_000)
                // The contract has no event stream: every view refreshes from
                // the same periodic snapshot. A failed poll is transient and
                // keeps the previous values.
                runCatching { client.health() }.onSuccess(::applyHealth)
                runCatching { client.devices() }.onSuccess { devices ->
                    _state.update { it.copy(devices = devices) }
                }
                runCatching { client.groups() }.onSuccess { response ->
                    _state.update { it.copy(groups = response.groups) }
                }
                runCatching { client.schedules() }.onSuccess { response ->
                    _state.update { it.copy(schedules = response.schedules) }
                }
            }
        }
    }

    private fun refreshGroups() {
        val client = api ?: return
        connectionScope.launch {
            runCatching { client.groups() }.onSuccess { response ->
                _state.update { it.copy(groups = response.groups) }
            }
        }
    }

    private fun refreshSchedules() {
        val client = api ?: return
        connectionScope.launch {
            runCatching { client.schedules() }.onSuccess { response ->
                _state.update { it.copy(schedules = response.schedules) }
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
            val scheduleEditorRemoved = current.scheduleEditorTarget?.let {
                it.targetType == ScheduleTarget.DEVICE && it.targetId == deviceId
            } == true
            current.copy(
                devices = current.devices.filterNot { it.id == deviceId },
                groups = current.groups.map { group ->
                    if (deviceId in group.deviceIds) {
                        group.copy(deviceIds = group.deviceIds.filterNot { it == deviceId })
                    } else {
                        group
                    }
                },
                // The server cascades device-targeted schedules on delete.
                schedules = current.schedules.filterNot {
                    it.targetType == ScheduleTarget.DEVICE && it.targetId == deviceId
                },
                detailDeviceId = current.detailDeviceId?.takeUnless { it == deviceId },
                editingDeviceId = current.editingDeviceId?.takeUnless { it == deviceId },
                removalTargetId = current.removalTargetId?.takeUnless { it == deviceId },
                scheduleEditorVisible =
                    if (scheduleEditorRemoved) false else current.scheduleEditorVisible,
                scheduleEditorTarget =
                    if (scheduleEditorRemoved) null else current.scheduleEditorTarget,
                scheduleError = if (scheduleEditorRemoved) null else current.scheduleError,
            )
        }
    }

    private fun dropGroup(groupId: String) {
        _state.update { current ->
            val editingRemoved = current.groupEditorTarget?.id == groupId
            // The server cascades group-targeted schedules on delete; an open
            // editor for one of them has no target left to save.
            val scheduleEditorRemoved = current.scheduleEditorTarget?.let {
                it.targetType == ScheduleTarget.GROUP && it.targetId == groupId
            } == true
            current.copy(
                groups = current.groups.filterNot { it.id == groupId },
                schedules = current.schedules.filterNot {
                    it.targetType == ScheduleTarget.GROUP && it.targetId == groupId
                },
                groupRemovalTargetId = current.groupRemovalTargetId?.takeUnless { it == groupId },
                groupEditorVisible = if (editingRemoved) false else current.groupEditorVisible,
                groupEditorTarget = current.groupEditorTarget?.takeUnless { it.id == groupId },
                groupError = if (editingRemoved) null else current.groupError,
                scheduleEditorVisible =
                    if (scheduleEditorRemoved) false else current.scheduleEditorVisible,
                scheduleEditorTarget =
                    if (scheduleEditorRemoved) null else current.scheduleEditorTarget,
                scheduleError = if (scheduleEditorRemoved) null else current.scheduleError,
            )
        }
    }

    private fun dropSchedule(scheduleId: String) {
        _state.update { current ->
            val editingRemoved = current.scheduleEditorTarget?.id == scheduleId
            current.copy(
                schedules = current.schedules.filterNot { it.id == scheduleId },
                scheduleRemovalTargetId =
                    current.scheduleRemovalTargetId?.takeUnless { it == scheduleId },
                scheduleEditorVisible =
                    if (editingRemoved) false else current.scheduleEditorVisible,
                scheduleEditorTarget =
                    current.scheduleEditorTarget?.takeUnless { it.id == scheduleId },
                scheduleError = if (editingRemoved) null else current.scheduleError,
            )
        }
    }
}

private fun UpdateScheduleRequest.isEmpty(): Boolean =
    name == null && targetType == null && targetId == null && action == null &&
        rrule == null && timeZone == null && startsAtEpochMs == null && enabled == null

private fun Throwable.readableMessage(): String {
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
