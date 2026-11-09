package dev.koft.ev1527.gui.data

import dev.koft.ev1527.contract.CommandAction
import dev.koft.ev1527.contract.CreateScheduleRequest
import dev.koft.ev1527.contract.Device
import dev.koft.ev1527.contract.DeviceListResponse
import dev.koft.ev1527.contract.Group
import dev.koft.ev1527.contract.GroupCommandDispatched
import dev.koft.ev1527.contract.GroupListResponse
import dev.koft.ev1527.contract.HealthResponse
import dev.koft.ev1527.contract.LearningConfirmation
import dev.koft.ev1527.contract.LearningSession
import dev.koft.ev1527.contract.LearningSlot
import dev.koft.ev1527.contract.LearningTestResult
import dev.koft.ev1527.contract.Schedule
import dev.koft.ev1527.contract.ScheduleListResponse
import dev.koft.ev1527.contract.ScheduleTarget
import dev.koft.ev1527.contract.UpdateScheduleRequest
import dev.koft.ev1527.gui.api.ClientApiException
import dev.koft.ev1527.gui.api.Esp32Api
import dev.koft.ev1527.gui.api.isGone
import dev.koft.ev1527.gui.api.newIdempotencyKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single data boundary for the client.
 *
 * Owns the HTTP client and the local read cache, so callers never create a
 * client or decide which server a request goes to. Reads come from
 * [LocalStore]; writes go to the server and update the store only after the
 * server confirms.
 */
internal class Esp32Repository(
    private val localStore: LocalStore,
) {
    private var client: Esp32Api? = null

    private val _health = MutableStateFlow<HealthResponse?>(null)
    val health: StateFlow<HealthResponse?> = _health.asStateFlow()

    val devices: Flow<List<Device>> = localStore.devices
    val groups: Flow<List<Group>> = localStore.groups
    val schedules: Flow<List<Schedule>> = localStore.schedules
    val lastSyncedAtEpochMs: Flow<Long?> = localStore.lastSyncedAtEpochMs

    // A refresh that started before a write must not replace the store with
    // its older snapshot. Writes bump the epoch; snapshots check it under the
    // same lock before committing.
    private val cacheMutex = Mutex()
    private var writeEpoch = 0L

    // Serializes learning session create/cancel so a close followed by an
    // immediate reopen cannot hit the server's single-active-session limit.
    private val sessionMutex = Mutex()

    fun connect(serverUrl: String, token: String) {
        client?.close()
        client = Esp32Api(baseUrl = serverUrl, token = token)
        _health.value = null
    }

    fun close() {
        client?.close()
        client = null
        _health.value = null
    }

    /** Pulls a full snapshot into the cache. Health is applied independently. */
    suspend fun refresh(): Result<Unit> {
        val current = client ?: return Result.failure(NotConnectedException())
        try {
            // Cached ids belong to the server they came from.
            if (localStore.cachedServerUrl() != current.baseUrl) {
                localStore.clear()
            }
            val epoch = writeEpoch
            val snapshot = coroutineScope {
                val health = async { capture { current.health() } }
                val devices = async { capture { current.deviceList() } }
                val groups = async { capture { current.groups() } }
                val schedules = async { capture { current.schedules() } }
                RefreshSnapshot(
                    health = health.await(),
                    devices = devices.await(),
                    groups = groups.await(),
                    schedules = schedules.await(),
                )
            }
            // Health is not cached: a gateway outage must not block the cache.
            snapshot.health.onSuccess { _health.value = it }
            val devices = snapshot.devices.getOrThrow()
            replaceSnapshot(
                epoch = epoch,
                client = current,
                serverUrl = current.baseUrl,
                devices = devices.devices,
                groups = snapshot.groups.getOrThrow().groups,
                schedules = snapshot.schedules.getOrThrow().schedules,
                syncedAtEpochMs = devices.serverTimeEpochMs,
            )
            return Result.success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return Result.failure(t)
        }
    }

    suspend fun refreshHealth() {
        _health.value = requireClient().health()
    }

    suspend fun saveDevice(
        deviceId: String,
        name: String,
        location: String,
    ): Result<Unit> = write(
        onGone = { current -> commitWrite(current) { localStore.deleteDevice(deviceId) } },
    ) { current ->
        val updated = current.updateDevice(deviceId, name, location)
        commitWrite(current) { localStore.upsertDevice(updated) }
    }

    suspend fun removeDevice(deviceId: String): Result<Unit> = write(
        onGone = { current -> commitWrite(current) { localStore.deleteDevice(deviceId) } },
    ) { current ->
        current.removeDevice(deviceId)
        commitWrite(current) { localStore.deleteDevice(deviceId) }
    }

    suspend fun saveGroup(
        groupId: String?,
        name: String,
        deviceIds: List<String>,
    ): Result<Unit> = write(
        onGone = groupId?.let { id ->
            { current -> commitWrite(current) { localStore.deleteGroup(id) } }
        },
    ) { current ->
        val saved = if (groupId == null) {
            current.createGroup(name, deviceIds)
        } else {
            current.updateGroup(groupId, name, deviceIds)
        }
        commitWrite(current) { localStore.upsertGroup(saved) }
    }

    suspend fun removeGroup(groupId: String): Result<Unit> = write(
        onGone = { current -> commitWrite(current) { localStore.deleteGroup(groupId) } },
    ) { current ->
        current.removeGroup(groupId)
        commitWrite(current) { localStore.deleteGroup(groupId) }
    }

    suspend fun saveSchedule(
        scheduleId: String?,
        name: String,
        targetType: ScheduleTarget,
        targetId: String,
        action: CommandAction,
        rrule: String,
        timeZone: String,
        startsAtEpochMs: Long,
        enabled: Boolean,
    ): Result<Unit> {
        val update = scheduleId?.let { id -> localStore.schedule(id) }?.let { current ->
            UpdateScheduleRequest(
                name = name.takeIf { it != current.name },
                targetType = targetType.takeIf { it != current.targetType },
                targetId = targetId.takeIf { it != current.targetId },
                action = action.takeIf { it != current.action },
                rrule = rrule.takeIf { it != current.rrule },
                timeZone = timeZone.takeIf { it != current.timeZone },
                startsAtEpochMs = startsAtEpochMs.takeIf { it != current.startsAtEpochMs },
                enabled = enabled.takeIf { it != current.enabled },
            )
        }
        if (update != null && update.isEmpty()) {
            return Result.success(Unit)
        }
        return write(
            onGone = scheduleId?.let { id ->
                { current -> commitWrite(current) { localStore.deleteSchedule(id) } }
            },
        ) { current ->
            val saved = if (scheduleId == null) {
                current.createSchedule(
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
                current.updateSchedule(scheduleId, update ?: UpdateScheduleRequest())
            }
            commitWrite(current) { localStore.upsertSchedule(saved) }
        }
    }

    suspend fun removeSchedule(scheduleId: String): Result<Unit> = write(
        onGone = { current -> commitWrite(current) { localStore.deleteSchedule(scheduleId) } },
    ) { current ->
        current.removeSchedule(scheduleId)
        commitWrite(current) { localStore.deleteSchedule(scheduleId) }
    }

    suspend fun setScheduleEnabled(scheduleId: String, enabled: Boolean): Result<Unit> = write(
        onGone = { current -> commitWrite(current) { localStore.deleteSchedule(scheduleId) } },
    ) { current ->
        val saved = current.updateSchedule(scheduleId, UpdateScheduleRequest(enabled = enabled))
        commitWrite(current) { localStore.upsertSchedule(saved) }
    }

    suspend fun sendCommand(deviceId: String, action: CommandAction): Result<Unit> = write { current ->
        current.sendCommand(
            deviceId = deviceId,
            action = action,
            idempotencyKey = newIdempotencyKey(),
        )
    }

    suspend fun sendGroupCommand(
        groupId: String,
        action: CommandAction,
    ): Result<GroupCommandDispatched> = write { current ->
        current.sendGroupCommand(
            groupId = groupId,
            action = action,
            idempotencyKey = newIdempotencyKey(),
        )
    }

    suspend fun startLearning(): LearningSession = sessionMutex.withLock {
        requireClient().startLearning()
    }

    suspend fun learning(sessionId: String): LearningSession =
        requireClient().learning(sessionId)

    suspend fun acceptCandidate(sessionId: String, candidateId: String): LearningSession =
        requireClient().acceptCandidate(sessionId, candidateId)

    suspend fun rescan(sessionId: String): LearningSession =
        requireClient().rescan(sessionId)

    suspend fun testLearning(sessionId: String, slot: LearningSlot): LearningTestResult =
        requireClient().testLearning(sessionId, slot)

    suspend fun confirmLearning(
        sessionId: String,
        name: String,
        location: String,
    ): LearningConfirmation {
        val current = requireClient()
        val confirmation = current.confirmLearning(sessionId, name, location)
        commitWrite(current) { localStore.upsertDevice(confirmation.device) }
        return confirmation
    }

    suspend fun cancelLearning(sessionId: String) {
        sessionMutex.withLock {
            client?.cancelLearning(sessionId)
        }
    }

    private fun requireClient(): Esp32Api =
        client ?: throw NotConnectedException()

    /**
     * Runs one write against the connection it started on and updates the
     * store only if that connection is still current.
     */
    private suspend fun <T> write(
        onGone: (suspend (Esp32Api) -> Unit)? = null,
        block: suspend (Esp32Api) -> T,
    ): Result<T> {
        val current = client ?: return Result.failure(NotConnectedException())
        return try {
            Result.success(block(current))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (onGone != null && t.isGone()) {
                onGone(current)
            }
            Result.failure(t)
        }
    }

    private suspend fun replaceSnapshot(
        epoch: Long,
        client: Esp32Api,
        serverUrl: String,
        devices: List<Device>,
        groups: List<Group>,
        schedules: List<Schedule>,
        syncedAtEpochMs: Long,
    ) {
        cacheMutex.withLock {
            if (epoch != writeEpoch) return@withLock
            // A reconnect may have completed while this snapshot was in flight.
            if (this.client !== client) return@withLock
            localStore.replaceAll(
                serverUrl = serverUrl,
                devices = devices,
                groups = groups,
                schedules = schedules,
                syncedAtEpochMs = syncedAtEpochMs,
            )
        }
    }

    private suspend fun commitWrite(client: Esp32Api, block: suspend () -> Unit) {
        cacheMutex.withLock {
            if (this.client !== client) return@withLock
            writeEpoch += 1
            block()
        }
    }
}

internal class NotConnectedException : IllegalStateException("not connected")

private data class RefreshSnapshot(
    val health: Result<HealthResponse>,
    val devices: Result<DeviceListResponse>,
    val groups: Result<GroupListResponse>,
    val schedules: Result<ScheduleListResponse>,
)

private suspend fun <T> capture(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Result.failure(t)
    }

private fun UpdateScheduleRequest.isEmpty(): Boolean =
    name == null && targetType == null && targetId == null && action == null &&
        rrule == null && timeZone == null && startsAtEpochMs == null && enabled == null
