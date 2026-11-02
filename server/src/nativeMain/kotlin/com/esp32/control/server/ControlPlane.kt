package com.esp32.control.server

import com.esp32.control.contract.ActionType
import com.esp32.control.contract.ApiErrorBody
import com.esp32.control.contract.ApiErrorEnvelope
import com.esp32.control.contract.CommandAccepted
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.CommandRecord
import com.esp32.control.contract.CommandStatus
import com.esp32.control.contract.CommandUpdatedEvent
import com.esp32.control.contract.ConfirmLearningRequest
import com.esp32.control.contract.ContractJson
import com.esp32.control.contract.DataSource
import com.esp32.control.contract.Device
import com.esp32.control.contract.DeviceAction
import com.esp32.control.contract.DeviceAddedEvent
import com.esp32.control.contract.DeviceListResponse
import com.esp32.control.contract.DeviceRemovedEvent
import com.esp32.control.contract.DeviceState
import com.esp32.control.contract.DeviceUpdatedEvent
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.GatewayStatus
import com.esp32.control.contract.GatewayUpdatedEvent
import com.esp32.control.contract.HealthResponse
import com.esp32.control.contract.HealthStatus
import com.esp32.control.contract.LearningCandidate
import com.esp32.control.contract.LearningConfirmation
import com.esp32.control.contract.LearningSession
import com.esp32.control.contract.LearningSlot
import com.esp32.control.contract.LearningState
import com.esp32.control.contract.LearningTestResult
import com.esp32.control.contract.LearningUpdatedEvent
import com.esp32.control.contract.ReceiverState
import com.esp32.control.contract.ReceiverStatus
import com.esp32.control.contract.RfParameters
import com.esp32.control.contract.StartLearningRequest
import com.esp32.control.contract.StateEvidence
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class CommandResult(
    val accepted: CommandAccepted,
    val replayed: Boolean,
)

class ControlPlane(
    private val config: ServerConfig,
    private val hub: SseHub,
    private val startedAtEpochMs: Long = nowEpochMs(),
) {
    private val mutex = Mutex()
    private val devices = LinkedHashMap<String, Device>()
    private val tombstones = LinkedHashMap<String, Tombstone>()
    private val commands = LinkedHashMap<String, CommandRecord>()
    private val ownership = HashMap<CodeKey, String>()
    private val idempotency = LinkedHashMap<String, IdempotencyEntry>()
    private val inFlight = HashMap<String, InFlightCommand>()
    private val recentTx = HashMap<CodeKey, RecentTx>()
    private val learningSessions = LinkedHashMap<String, LearningSession>()
    private val learningTerminalAt = HashMap<String, Long>()
    private val learningDeviceIds = HashMap<String, String>()
    private val testRateLimit = HashMap<String, Long>()
    private var activeLearningId: String? = null

    private var gatewayState = GatewayState.UNKNOWN
    private var gatewayVersion: String? = null
    private var gatewayChangedAt = startedAtEpochMs
    private var receiverState = ReceiverState.UNKNOWN
    private var receiverLastCaptureAt: Long? = null
    private var receiverCaptureCount = 0L

    private var transport: (suspend (topic: String, payload: ByteArray) -> Unit)? = null

    fun attachTransport(publish: suspend (topic: String, payload: ByteArray) -> Unit) {
        transport = publish
    }

    suspend fun health(): HealthResponse = mutex.withLock {
        val now = nowEpochMs()
        HealthResponse(
            status = HealthStatus.OK,
            service = "esp32-control-server",
            apiVersion = "1",
            version = SERVER_VERSION,
            runtime = "Kotlin/Native",
            uptimeSeconds = ((now - startedAtEpochMs).coerceAtLeast(0)) / 1000,
            serverTimeEpochMs = now,
            dataSource = config.dataSource,
            gateway = gatewaySnapshotLocked(),
        )
    }

    suspend fun listDevices(): DeviceListResponse = mutex.withLock {
        DeviceListResponse(
            devices = devices.values.toList(),
            serverTimeEpochMs = nowEpochMs(),
        )
    }

    suspend fun getDevice(deviceId: String): Device = mutex.withLock {
        requireDeviceId(deviceId)
        devices[deviceId]?.let { return@withLock it }
        tombstones[deviceId]?.let { tombstone ->
            throw ApiException(
                HttpStatusCode.Gone,
                "device_removed",
                "Device has been removed",
                mapOf("removedAtEpochMs" to tombstone.removedAtEpochMs.toString()),
            )
        }
        throw ApiException(HttpStatusCode.NotFound, "device_not_found", "Device not found")
    }

    suspend fun getCommand(commandId: String): CommandRecord = mutex.withLock {
        commands[commandId]
            ?: throw ApiException(HttpStatusCode.NotFound, "command_not_found", "Command not found")
    }

    suspend fun issueCommand(
        deviceId: String,
        action: CommandAction,
        idempotencyKey: String,
    ): CommandResult {
        requireDeviceId(deviceId)
        if (idempotencyKey.isBlank() || idempotencyKey.length > 64) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Idempotency-Key must be 1-64 characters",
            )
        }

        var publishSpec: TxPayload? = null
        val commandId = newId("cmd")
        var replayResult: CommandResult? = null

        mutex.withLock {
            val now = nowEpochMs()
            purgeLocked(now)

            val existing = idempotency[idempotencyKey]
            if (existing != null) {
                if (existing.deviceId != deviceId || existing.action != action) {
                    throw ApiException(
                        HttpStatusCode.Conflict,
                        "idempotency_conflict",
                        "Idempotency-Key was already used for a different request",
                    )
                }
                val command = commands[existing.commandId]
                if (command != null) {
                    val device = devices[deviceId] ?: throw deviceRemovedLocked(deviceId)
                    replayResult = CommandResult(CommandAccepted(command, device), replayed = true)
                    return@withLock
                }
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "command_in_progress",
                    "The original command is still being dispatched",
                )
            }

            val device = devices[deviceId] ?: run {
                tombstones[deviceId]?.let { throw deviceRemovedLocked(deviceId) }
                throw ApiException(HttpStatusCode.NotFound, "device_not_found", "Device not found")
            }
            if (gatewayState != GatewayState.ONLINE) {
                throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "gateway_unavailable",
                    "Gateway is not online",
                )
            }
            if (!device.online) {
                throw ApiException(HttpStatusCode.Conflict, "device_offline", "Device is offline")
            }
            if (inFlight.containsKey(deviceId)) {
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "command_in_progress",
                    "Another command is in progress for this device",
                )
            }

            val spec = TxPayload(
                value = if (action == CommandAction.TURN_ON) device.codeOn else device.codeOff,
                protocol = device.rf.protocol,
                length = device.rf.length,
                repeat = device.rf.repeat,
            )
            publishSpec = spec
            inFlight[deviceId] = InFlightCommand(
                commandId = commandId,
                deviceId = deviceId,
                action = action,
                requestedAtEpochMs = now,
                idempotencyKey = idempotencyKey,
                payload = spec,
            )
            idempotency[idempotencyKey] = IdempotencyEntry(
                commandId = commandId,
                deviceId = deviceId,
                action = action,
                createdAtEpochMs = now,
            )
        }

        replayResult?.let { return it }
        val spec = publishSpec ?: error("missing publish payload")

        // Do not publish if a concurrent DELETE cancelled the in-flight command.
        val mayPublish = mutex.withLock {
            val current = inFlight[deviceId]
            current != null && current.commandId == commandId && !current.cancelled && devices.containsKey(deviceId)
        }

        if (!mayPublish) {
            mutex.withLock {
                inFlight.remove(deviceId)
                commands[commandId] = CommandRecord(
                    commandId = commandId,
                    deviceId = deviceId,
                    action = action,
                    status = CommandStatus.CANCELLED,
                    requestedAtEpochMs = nowEpochMs(),
                )
                emitCommandLocked(commands.getValue(commandId))
            }
            throw ApiException(HttpStatusCode.Gone, "device_removed", "Device was removed")
        }

        val publishError = runCatching {
            val publish = transport ?: throw MqttUnavailableException("No MQTT transport configured")
            publish(config.commandTopic, ContractJson.encodeToString(spec).encodeToByteArray())
        }.exceptionOrNull()

        val result = mutex.withLock {
            val now = nowEpochMs()
            val current = inFlight.remove(deviceId)
            if (publishError != null) {
                val cancelled = current?.cancelled == true
                val record = CommandRecord(
                    commandId = commandId,
                    deviceId = deviceId,
                    action = action,
                    status = if (cancelled) CommandStatus.CANCELLED else CommandStatus.FAILED,
                    requestedAtEpochMs = current?.requestedAtEpochMs ?: now,
                    failureReason = if (cancelled) "device_removed" else "mqtt_publish_failed",
                )
                commands[commandId] = record
                emitCommandLocked(record)
                throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "gateway_unavailable",
                    "Failed to publish command to the gateway",
                )
            }

            val device = devices[deviceId]
            if (device == null) {
                val record = CommandRecord(
                    commandId = commandId,
                    deviceId = deviceId,
                    action = action,
                    status = CommandStatus.DISPATCHED,
                    requestedAtEpochMs = current?.requestedAtEpochMs ?: now,
                    dispatchedAtEpochMs = now,
                )
                commands[commandId] = record
                emitCommandLocked(record)
                throw ApiException(HttpStatusCode.Gone, "device_removed", "Device was removed")
            }

            val updated = device.copy(
                state = if (action == CommandAction.TURN_ON) DeviceState.ON else DeviceState.OFF,
                stateEvidence = StateEvidence.COMMANDED,
                lastAction = DeviceAction(
                    type = if (action == CommandAction.TURN_ON) ActionType.TURNED_ON else ActionType.TURNED_OFF,
                    atEpochMs = now,
                    commandId = commandId,
                ),
                revision = device.revision + 1,
            )
            devices[deviceId] = updated
            recentTx[spec.key()] = RecentTx(
                commandId = commandId,
                deviceId = deviceId,
                atEpochMs = now,
                action = action,
            )
            val record = CommandRecord(
                commandId = commandId,
                deviceId = deviceId,
                action = action,
                status = CommandStatus.DISPATCHED,
                requestedAtEpochMs = current?.requestedAtEpochMs ?: now,
                dispatchedAtEpochMs = now,
            )
            commands[commandId] = record
            emitDeviceLocked(updated, added = false)
            emitCommandLocked(record)
            CommandResult(CommandAccepted(record, updated), replayed = false)
        }

        return result
    }

    suspend fun deleteDevice(deviceId: String): Boolean {
        requireDeviceId(deviceId)
        return mutex.withLock {
            val now = nowEpochMs()
            val device = devices[deviceId]
            if (device == null) {
                if (tombstones.containsKey(deviceId)) return@withLock false
                throw ApiException(HttpStatusCode.NotFound, "device_not_found", "Device not found")
            }

            val revision = device.revision + 1
            devices.remove(deviceId)
            ownership.remove(CodeKey(device.rf.protocol, device.rf.length, device.codeOn))
            ownership.remove(CodeKey(device.rf.protocol, device.rf.length, device.codeOff))
            inFlight[deviceId]?.cancelled = true
            tombstones[deviceId] = Tombstone(deviceId, now, revision)
            hub.emit(
                DeviceRemovedEvent(
                    eventId = newId("evt"),
                    atEpochMs = now,
                    deviceId = deviceId,
                    removedAtEpochMs = now,
                    revision = revision,
                ),
            )
            return@withLock true
        }
    }

    // ---------------------------------------------------------------------
    // Learning
    // ---------------------------------------------------------------------

    suspend fun startLearning(request: StartLearningRequest): LearningSession = mutex.withLock {
        val now = nowEpochMs()
        purgeLocked(now)
        if (gatewayState != GatewayState.ONLINE) {
            throw ApiException(
                HttpStatusCode.ServiceUnavailable,
                "gateway_unavailable",
                "Gateway is not online",
            )
        }
        if (receiverState == ReceiverState.UNAVAILABLE) {
            throw ApiException(
                HttpStatusCode.ServiceUnavailable,
                "receiver_unavailable",
                "The 315MHz receiver is unavailable",
            )
        }
        activeLearningId?.let { active ->
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_busy",
                "A learning session is already active",
                mapOf("activeSessionId" to active),
            )
        }

        request.nameHint?.let {
            if (it.length > 64) {
                throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "nameHint is too long")
            }
        }
        request.locationHint?.let {
            if (it.length > 64) {
                throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "locationHint is too long")
            }
        }

        val session = LearningSession(
            id = newId("ls"),
            state = LearningState.COLLECTING_ON,
            revision = 1,
            createdAtEpochMs = now,
            sessionExpiresAtEpochMs = now + SESSION_TTL_MS,
            collectionExpiresAtEpochMs = now + COLLECTION_WINDOW_MS,
            candidateExpiresAtEpochMs = null,
            requiredHits = REQUIRED_HITS,
            candidates = emptyList(),
        )
        learningSessions[session.id] = session
        activeLearningId = session.id
        emitLearningLocked(session)
        session
    }

    suspend fun getLearning(sessionId: String): LearningSession = mutex.withLock {
        purgeLocked(nowEpochMs())
        learningSessions[sessionId]
            ?: throw ApiException(
                HttpStatusCode.NotFound,
                "learning_session_not_found",
                "Learning session not found",
            )
    }

    suspend fun acceptCandidate(sessionId: String, candidateId: String): LearningSession = mutex.withLock {
        val now = nowEpochMs()
        val session = activeSessionLocked(sessionId)
        val candidate = session.candidates.firstOrNull { it.id == candidateId }
            ?: throw ApiException(HttpStatusCode.NotFound, "candidate_not_found", "Candidate not found")
        val alreadyAccepted = candidate.accepted && (
            session.acceptedOnCandidateId == candidate.id ||
                session.acceptedOffCandidateId == candidate.id
            )
        if (alreadyAccepted) return@withLock session

        val state = session.state
        if (state != LearningState.CANDIDATE_ON_READY && state != LearningState.CANDIDATE_OFF_READY) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "The session is not waiting for candidate confirmation",
            )
        }
        val currentSlot = if (state == LearningState.CANDIDATE_OFF_READY) {
            LearningSlot.TURN_OFF
        } else {
            LearningSlot.TURN_ON
        }

        if (candidate.slot != currentSlot || !candidate.qualified || candidate.accepted) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "candidate_not_ready",
                "Candidate cannot be accepted in the current state",
            )
        }

        val owner = ownership[CodeKey(candidate.protocol, candidate.length, candidate.value)]
        if (owner != null) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "code_already_registered",
                "RF code is already owned by another device",
                mapOf("deviceId" to owner, "value" to candidate.value.toString()),
            )
        }

        if (currentSlot == LearningSlot.TURN_ON) {
            if (gatewayState != GatewayState.ONLINE) {
                throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "gateway_unavailable",
                    "Gateway is not online",
                )
            }
            if (receiverState == ReceiverState.UNAVAILABLE) {
                throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "receiver_unavailable",
                    "The 315MHz receiver is unavailable",
                )
            }
        }

        val acceptedCandidate = candidate.copy(accepted = true)
        fun mark(c: LearningCandidate): LearningCandidate =
            if (c.id == acceptedCandidate.id) acceptedCandidate else c

        val updated = if (currentSlot == LearningSlot.TURN_ON) {
            val remaining = session.candidates
                .filter { it.accepted || it.id == candidate.id }
                .map(::mark)
            session.copy(
                state = LearningState.COLLECTING_OFF,
                revision = session.revision + 1,
                collectionExpiresAtEpochMs = now + COLLECTION_WINDOW_MS,
                candidateExpiresAtEpochMs = null,
                candidates = remaining,
                acceptedOnCandidateId = candidate.id,
            )
        } else {
            val remaining = session.candidates
                .filter { it.accepted || it.id == candidate.id }
                .map(::mark)
            session.copy(
                state = LearningState.READY,
                revision = session.revision + 1,
                sessionExpiresAtEpochMs = now + READY_WINDOW_MS,
                collectionExpiresAtEpochMs = null,
                candidateExpiresAtEpochMs = null,
                candidates = remaining,
                acceptedOffCandidateId = candidate.id,
            )
        }
        learningSessions[updated.id] = updated
        emitLearningLocked(updated)
        updated
    }

    suspend fun rescan(sessionId: String): LearningSession = mutex.withLock {
        val now = nowEpochMs()
        val session = activeSessionLocked(sessionId)
        val slot = when (session.state) {
            LearningState.COLLECTING_ON, LearningState.CANDIDATE_ON_READY -> LearningSlot.TURN_ON
            LearningState.COLLECTING_OFF, LearningState.CANDIDATE_OFF_READY -> LearningSlot.TURN_OFF
            else -> throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "The session cannot be rescanned in its current state",
            )
        }
        if (gatewayState != GatewayState.ONLINE) {
            throw ApiException(
                HttpStatusCode.ServiceUnavailable,
                "gateway_unavailable",
                "Gateway is not online",
            )
        }
        if (receiverState == ReceiverState.UNAVAILABLE) {
            throw ApiException(
                HttpStatusCode.ServiceUnavailable,
                "receiver_unavailable",
                "The 315MHz receiver is unavailable",
            )
        }

        val updated = session.copy(
            state = if (slot == LearningSlot.TURN_ON) LearningState.COLLECTING_ON else LearningState.COLLECTING_OFF,
            revision = session.revision + 1,
            collectionExpiresAtEpochMs = now + COLLECTION_WINDOW_MS,
            candidateExpiresAtEpochMs = null,
            candidates = session.candidates.filter { it.slot != slot || it.accepted },
        )
        learningSessions[updated.id] = updated
        emitLearningLocked(updated)
        updated
    }

    suspend fun testLearning(sessionId: String, slot: LearningSlot): LearningTestResult {
        var candidate: LearningCandidate? = null
        val testId = newId("test")
        mutex.withLock {
            val now = nowEpochMs()
            val session = activeSessionLocked(sessionId)
            if (session.state != LearningState.READY) {
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "learning_not_ready",
                    "The session is not ready for testing",
                )
            }
            if (gatewayState != GatewayState.ONLINE) {
                throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "gateway_unavailable",
                    "Gateway is not online",
                )
            }
            val acceptedId = if (slot == LearningSlot.TURN_ON) {
                session.acceptedOnCandidateId
            } else {
                session.acceptedOffCandidateId
            }
            candidate = session.candidates.firstOrNull { it.id == acceptedId && it.accepted }
                ?: throw ApiException(
                    HttpStatusCode.Conflict,
                    "learning_not_ready",
                    "The requested slot has no accepted candidate",
                )
            val last = testRateLimit[sessionId] ?: 0L
            if (now - last < TEST_RATE_LIMIT_MS) {
                throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Test again later")
            }
            testRateLimit[sessionId] = now
        }

        val accepted = candidate ?: error("accepted candidate missing")
        val payload = TxPayload(
            value = accepted.value,
            protocol = accepted.protocol,
            length = accepted.length,
            repeat = 5,
        )
        val dispatchedAt = nowEpochMs()
        val publish = transport ?: throw ApiException(
            HttpStatusCode.ServiceUnavailable,
            "gateway_unavailable",
            "No MQTT transport configured",
        )
        try {
            publish(config.commandTopic, ContractJson.encodeToString(payload).encodeToByteArray())
        } catch (_: Throwable) {
            throw ApiException(
                HttpStatusCode.ServiceUnavailable,
                "gateway_unavailable",
                "Failed to publish test code",
            )
        }
        return LearningTestResult(
            testId = testId,
            sessionId = sessionId,
            slot = slot,
            value = accepted.value,
            dispatchedAtEpochMs = dispatchedAt,
            status = "dispatched",
        )
    }

    suspend fun confirmLearning(
        sessionId: String,
        request: ConfirmLearningRequest,
    ): LearningConfirmation = mutex.withLock {
        val now = nowEpochMs()
        val existingSession = learningSessions[sessionId]
            ?: throw ApiException(
                HttpStatusCode.NotFound,
                "learning_session_not_found",
                "Learning session not found",
            )
        if (existingSession.state == LearningState.COMPLETED) {
            val deviceId = learningDeviceIds[sessionId]
                ?: throw ApiException(
                    HttpStatusCode.Conflict,
                    "learning_not_ready",
                    "Learning session is already completed",
                )
            val device = devices[deviceId] ?: throw deviceRemovedLocked(deviceId)
            return@withLock LearningConfirmation(device = device, session = existingSession)
        }
        val session = activeSessionLocked(sessionId)
        if (session.state != LearningState.READY) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "The session is not ready to save",
            )
        }
        val name = request.name.trim()
        val location = request.location.trim()
        if (name.isEmpty() || name.length > 64 || location.isEmpty() || location.length > 64) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "name and location must be 1-64 characters",
            )
        }

        val on = session.candidates.firstOrNull {
            it.id == session.acceptedOnCandidateId && it.accepted && it.slot == LearningSlot.TURN_ON
        } ?: throw ApiException(
            HttpStatusCode.Conflict,
            "learning_not_ready",
            "Accepted ON candidate is missing",
        )
        val off = session.candidates.firstOrNull {
            it.id == session.acceptedOffCandidateId && it.accepted && it.slot == LearningSlot.TURN_OFF
        } ?: throw ApiException(
            HttpStatusCode.Conflict,
            "learning_not_ready",
            "Accepted OFF candidate is missing",
        )

        if (on.value == off.value) {
            throw codePairInvalid("same_code")
        }
        if (on.protocol != off.protocol) {
            throw codePairInvalid("protocol_mismatch")
        }
        if (on.length != off.length) {
            throw codePairInvalid("length_mismatch")
        }
        if (!validCode(on.value, on.length) || !validCode(off.value, off.length)) {
            throw codePairInvalid("code_out_of_range")
        }
        for (code in listOf(on, off)) {
            ownership[CodeKey(code.protocol, code.length, code.value)]?.let { owner ->
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "code_already_registered",
                    "RF code is already owned by another device",
                    mapOf("deviceId" to owner, "value" to code.value.toString()),
                )
            }
        }

        val device = Device(
            id = newId("dev"),
            name = name,
            location = location,
            state = DeviceState.UNKNOWN,
            stateEvidence = StateEvidence.UNKNOWN,
            online = gatewayState == GatewayState.ONLINE,
            codeOn = on.value,
            codeOff = off.value,
            rf = RfParameters(protocol = on.protocol, length = on.length, repeat = 5),
            lastAction = null,
            revision = 1,
            createdAtEpochMs = now,
        )
        devices[device.id] = device
        ownership[CodeKey(device.rf.protocol, device.rf.length, device.codeOn)] = device.id
        ownership[CodeKey(device.rf.protocol, device.rf.length, device.codeOff)] = device.id

        val completed = session.copy(
            state = LearningState.COMPLETED,
            revision = session.revision + 1,
            collectionExpiresAtEpochMs = null,
            candidateExpiresAtEpochMs = null,
        )
        learningSessions[completed.id] = completed
        learningDeviceIds[completed.id] = device.id
        activeLearningId = null
        learningTerminalAt[completed.id] = now

        emitDeviceLocked(device, added = true)
        emitLearningLocked(completed)
        LearningConfirmation(device = device, session = completed)
    }

    suspend fun cancelLearning(sessionId: String) {
        mutex.withLock {
            val now = nowEpochMs()
            val session = learningSessions[sessionId] ?: return@withLock
            if (session.state in TERMINAL_STATES) return@withLock
            val cancelled = session.copy(
                state = LearningState.CANCELLED,
                revision = session.revision + 1,
                collectionExpiresAtEpochMs = null,
                candidateExpiresAtEpochMs = null,
            )
            learningSessions[cancelled.id] = cancelled
            if (activeLearningId == cancelled.id) activeLearningId = null
            learningTerminalAt[cancelled.id] = now
            testRateLimit.remove(cancelled.id)
            emitLearningLocked(cancelled)
        }
    }

    // ---------------------------------------------------------------------
    // Gateway / MQTT ingest
    // ---------------------------------------------------------------------

    suspend fun onMqttConnected() {
        mutex.withLock {
            // The retained LWT/version follows immediately. Do not manufacture
            // an online state from the broker connection alone.
            if (gatewayState == GatewayState.UNKNOWN) {
                gatewayChangedAt = nowEpochMs()
                hub.emit(gatewayEventLocked())
            }
        }
    }

    suspend fun onMqttDisconnected() {
        setGatewayState(GatewayState.OFFLINE, null)
    }

    suspend fun onMqttMessage(topic: String, payload: ByteArray) {
        val prefix = config.subscribedTopicFilter.removeSuffix("/#")
        if (!topic.startsWith("$prefix/")) return
        val text = payload.decodeToString().trim()
        when {
            topic == "$prefix/LWT" -> {
                when (text.trim('"')) {
                    "online" -> setGatewayState(GatewayState.ONLINE, null)
                    "offline" -> setGatewayState(GatewayState.OFFLINE, null)
                }
            }

            topic == "$prefix/version" -> {
                val version = text.trim('"').takeIf { it.isNotEmpty() }
                setGatewayState(GatewayState.ONLINE, version)
            }

            topic.startsWith("$prefix/commands/") -> Unit
            else -> {
                val capture = parseCapture(text) ?: return
                ingestCapture(capture)
            }
        }
    }

    suspend fun tick() {
        mutex.withLock {
            val now = nowEpochMs()
            purgeLocked(now)
            val active = activeLearningId?.let { learningSessions[it] } ?: return@withLock
            when (active.state) {
                LearningState.COLLECTING_ON, LearningState.COLLECTING_OFF -> {
                    val collectionDeadline = active.collectionExpiresAtEpochMs
                    if (collectionDeadline != null && now >= collectionDeadline) {
                        val slot = if (active.state == LearningState.COLLECTING_ON) {
                            LearningSlot.TURN_ON
                        } else {
                            LearningSlot.TURN_OFF
                        }
                        val hadSignal = active.candidates.any { it.slot == slot }
                        expireLearningLocked(
                            active,
                            if (hadSignal) "insufficient_hits" else "no_signal",
                            now,
                        )
                        return@withLock
                    }
                    if (now >= active.sessionExpiresAtEpochMs) {
                        expireLearningLocked(active, "session_timeout", now)
                    }
                }

                LearningState.CANDIDATE_ON_READY, LearningState.CANDIDATE_OFF_READY -> {
                    val deadline = active.candidateExpiresAtEpochMs
                    if (deadline != null && now >= deadline) {
                        expireLearningLocked(active, "candidate_timeout", now)
                    } else if (now >= active.sessionExpiresAtEpochMs) {
                        expireLearningLocked(active, "session_timeout", now)
                    }
                }

                LearningState.READY -> {
                    if (now >= active.sessionExpiresAtEpochMs) {
                        expireLearningLocked(active, "ready_timeout", now)
                    }
                }

                else -> Unit
            }
        }
    }

    private suspend fun ingestCapture(capture: Capture) {
        mutex.withLock {
            val now = nowEpochMs()
            val wasReceiving = receiverState == ReceiverState.RECEIVING
            receiverState = ReceiverState.RECEIVING
            receiverLastCaptureAt = now
            receiverCaptureCount += 1
            if (!wasReceiving) hub.emit(gatewayEventLocked())

            val key = CodeKey(capture.protocol, capture.length, capture.value)

            // A matching TX echo within 2 s is command evidence, never a
            // learning candidate. This keeps the server's own transmission
            // out of the press counter.
            val tx = recentTx[key]
            if (tx != null && now - tx.atEpochMs <= TX_CONFIRM_WINDOW_MS) {
                recentTx.remove(key)
                val command = commands[tx.commandId]
                if (command != null && command.status == CommandStatus.DISPATCHED) {
                    val confirmed = command.copy(
                        status = CommandStatus.CONFIRMED,
                        confirmedAtEpochMs = now,
                    )
                    commands[confirmed.commandId] = confirmed
                    emitCommandLocked(confirmed)
                    devices[tx.deviceId]?.let { device ->
                        val updated = device.copy(
                            stateEvidence = StateEvidence.REPORTED,
                            revision = device.revision + 1,
                        )
                        devices[updated.id] = updated
                        emitDeviceLocked(updated, added = false)
                    }
                }
                return@withLock
            }

            val sessionId = activeLearningId ?: return@withLock
            val session = learningSessions[sessionId] ?: return@withLock
            val state = session.state
            if (state != LearningState.COLLECTING_ON && state != LearningState.COLLECTING_OFF) {
                return@withLock
            }
            val deadline = session.collectionExpiresAtEpochMs ?: return@withLock
            if (now >= deadline) return@withLock

            val slot = if (state == LearningState.COLLECTING_ON) {
                LearningSlot.TURN_ON
            } else {
                LearningSlot.TURN_OFF
            }
            val existing = session.candidates.firstOrNull {
                it.slot == slot && it.protocol == capture.protocol &&
                    it.length == capture.length && it.value == capture.value
            }
            if (existing == null && session.candidates.size >= MAX_CANDIDATES) {
                return@withLock
            }
            val conflict = ownership[key]
            val updatedCandidate = if (existing == null) {
                LearningCandidate(
                    id = newId("cand"),
                    slot = slot,
                    value = capture.value,
                    protocol = capture.protocol,
                    length = capture.length,
                    delayMs = capture.delayMs,
                    hitCount = 1,
                    frameCount = 1,
                    qualified = REQUIRED_HITS <= 1,
                    accepted = false,
                    firstSeenAtEpochMs = now,
                    lastSeenAtEpochMs = now,
                    conflictDeviceId = conflict,
                )
            } else {
                val gap = now - existing.lastSeenAtEpochMs
                val newHits = if (gap >= PRESS_GAP_MS) existing.hitCount + 1 else existing.hitCount
                existing.copy(
                    delayMs = capture.delayMs,
                    hitCount = newHits,
                    frameCount = existing.frameCount + 1,
                    qualified = newHits >= REQUIRED_HITS,
                    lastSeenAtEpochMs = now,
                    conflictDeviceId = conflict,
                )
            }

            var candidates = session.candidates.toMutableList()
            if (existing == null) {
                candidates.add(updatedCandidate)
            } else {
                val index = candidates.indexOfFirst { it.id == existing.id }
                candidates[index] = updatedCandidate
            }

            val qualifiedNow = candidates.any { it.slot == slot && it.hitCount >= REQUIRED_HITS }
            candidates = candidates.map {
                if (it.slot == slot && it.hitCount >= REQUIRED_HITS) it.copy(qualified = true) else it
            }.toMutableList()

            val updated = session.copy(
                state = if (qualifiedNow) {
                    if (slot == LearningSlot.TURN_ON) {
                        LearningState.CANDIDATE_ON_READY
                    } else {
                        LearningState.CANDIDATE_OFF_READY
                    }
                } else {
                    session.state
                },
                revision = session.revision + 1,
                collectionExpiresAtEpochMs = if (qualifiedNow) null else session.collectionExpiresAtEpochMs,
                candidateExpiresAtEpochMs = if (qualifiedNow) now + CANDIDATE_WINDOW_MS else null,
                candidates = candidates,
            )
            learningSessions[updated.id] = updated
            emitLearningLocked(updated)
        }
    }

    private suspend fun setGatewayState(state: GatewayState, version: String?) {
        mutex.withLock {
            val now = nowEpochMs()
            val previousState = gatewayState
            val previousVersion = gatewayVersion
            val stateChanged = previousState != state
            val versionChanged = version != null && version != previousVersion
            if (!stateChanged && !versionChanged) return@withLock

            gatewayState = state
            if (version != null) gatewayVersion = version
            gatewayChangedAt = now

            if (state == GatewayState.ONLINE && previousState != GatewayState.ONLINE) {
                receiverState = ReceiverState.UNKNOWN
                receiverLastCaptureAt = null
                receiverCaptureCount = 0
            } else if (state != GatewayState.ONLINE) {
                receiverState = ReceiverState.UNKNOWN
            }

            val online = state == GatewayState.ONLINE
            for (id in devices.keys.toList()) {
                val device = devices[id] ?: continue
                if (device.online != online) {
                    val updated = device.copy(online = online, revision = device.revision + 1)
                    devices[id] = updated
                    emitDeviceLocked(updated, added = false)
                }
            }

            activeLearningId?.let { id ->
                val session = learningSessions[id] ?: return@let
                if (state != GatewayState.ONLINE &&
                    (session.state == LearningState.COLLECTING_ON || session.state == LearningState.COLLECTING_OFF)
                ) {
                    failLearningLocked(session, "gateway_offline", now)
                }
            }

            hub.emit(gatewayEventLocked())
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun gatewaySnapshotLocked(): GatewayStatus = GatewayStatus(
        state = gatewayState,
        version = gatewayVersion,
        clientId = config.gatewayClientId,
        lastChangedAtEpochMs = gatewayChangedAt,
        receiver = ReceiverStatus(
            state = receiverState,
            lastCaptureAtEpochMs = receiverLastCaptureAt,
            captureCount = receiverCaptureCount,
        ),
    )

    private fun gatewayEventLocked(): GatewayUpdatedEvent = GatewayUpdatedEvent(
        eventId = newId("evt"),
        atEpochMs = nowEpochMs(),
        gateway = gatewaySnapshotLocked(),
    )

    private fun emitDeviceLocked(device: Device, added: Boolean) {
        val now = nowEpochMs()
        hub.emit(
            if (added) {
                DeviceAddedEvent(newId("evt"), now, device)
            } else {
                DeviceUpdatedEvent(newId("evt"), now, device)
            },
        )
    }

    private fun emitCommandLocked(command: CommandRecord) {
        hub.emit(CommandUpdatedEvent(newId("evt"), nowEpochMs(), command))
    }

    private fun emitLearningLocked(session: LearningSession) {
        hub.emit(LearningUpdatedEvent(newId("evt"), nowEpochMs(), session))
    }

    private fun activeSessionLocked(sessionId: String): LearningSession {
        purgeLocked(nowEpochMs())
        val session = learningSessions[sessionId]
            ?: throw ApiException(
                HttpStatusCode.NotFound,
                "learning_session_not_found",
                "Learning session not found",
            )
        if (session.state in TERMINAL_STATES || activeLearningId != sessionId) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "Learning session is no longer active",
            )
        }
        return session
    }

    private fun failLearningLocked(session: LearningSession, reason: String, now: Long) {
        val failed = session.copy(
            state = LearningState.FAILED,
            revision = session.revision + 1,
            collectionExpiresAtEpochMs = null,
            candidateExpiresAtEpochMs = null,
            failureReason = reason,
        )
        learningSessions[failed.id] = failed
        if (activeLearningId == failed.id) activeLearningId = null
        learningTerminalAt[failed.id] = now
        testRateLimit.remove(failed.id)
        emitLearningLocked(failed)
    }

    private fun expireLearningLocked(session: LearningSession, reason: String, now: Long) {
        val expired = session.copy(
            state = LearningState.EXPIRED,
            revision = session.revision + 1,
            collectionExpiresAtEpochMs = null,
            candidateExpiresAtEpochMs = null,
            failureReason = reason,
        )
        learningSessions[expired.id] = expired
        if (activeLearningId == expired.id) activeLearningId = null
        learningTerminalAt[expired.id] = now
        testRateLimit.remove(expired.id)
        emitLearningLocked(expired)
    }

    private fun purgeLocked(now: Long) {
        val idemCutoff = now - IDEMPOTENCY_TTL_MS
        idempotency.entries.removeAll { it.value.createdAtEpochMs < idemCutoff }
        tombstones.entries.removeAll { it.value.removedAtEpochMs < now - TOMBSTONE_TTL_MS }
        recentTx.entries.removeAll { it.value.atEpochMs < now - TX_CONFIRM_WINDOW_MS }
        val terminalCutoff = now - TERMINAL_RETENTION_MS
        val expiredIds = learningTerminalAt.filterValues { it < terminalCutoff }.keys
        for (id in expiredIds) {
            learningSessions.remove(id)
            learningTerminalAt.remove(id)
            learningDeviceIds.remove(id)
            testRateLimit.remove(id)
        }
    }

    private fun deviceRemovedLocked(deviceId: String): ApiException {
        val tombstone = tombstones[deviceId]
        return if (tombstone != null) {
            ApiException(
                HttpStatusCode.Gone,
                "device_removed",
                "Device has been removed",
                mapOf("removedAtEpochMs" to tombstone.removedAtEpochMs.toString()),
            )
        } else {
            ApiException(HttpStatusCode.NotFound, "device_not_found", "Device not found")
        }
    }

    private fun requireDeviceId(deviceId: String) {
        if (!DEVICE_ID.matches(deviceId)) {
            throw ApiException(
                HttpStatusCode.BadRequest,
                "invalid_device_id",
                "Invalid device id",
            )
        }
    }

    private fun parseCapture(text: String): Capture? {
        val payload = runCatching {
            ContractJson.decodeFromString<GatewayCapturePayload>(text)
        }.getOrNull() ?: return null
        if (payload.protocol <= 0) return null
        if (payload.length !in 1..32) return null
        if (!validCode(payload.value, payload.length)) return null
        return Capture(
            value = payload.value,
            protocol = payload.protocol,
            length = payload.length,
            delayMs = payload.delay.coerceAtLeast(0),
        )
    }

    private fun validCode(value: Long, length: Int): Boolean {
        if (length !in 1..32) return false
        if (value < 0) return false
        return value < (1L shl length)
    }

    private fun codePairInvalid(reason: String): ApiException = ApiException(
        HttpStatusCode.UnprocessableEntity,
        "code_pair_invalid",
        "The learned ON/OFF pair is invalid",
        mapOf("reason" to reason),
    )

    private data class Tombstone(
        val deviceId: String,
        val removedAtEpochMs: Long,
        val revision: Long,
    )

    private data class IdempotencyEntry(
        val commandId: String,
        val deviceId: String,
        val action: CommandAction,
        val createdAtEpochMs: Long,
    )

    private class InFlightCommand(
        val commandId: String,
        val deviceId: String,
        val action: CommandAction,
        val requestedAtEpochMs: Long,
        val idempotencyKey: String,
        val payload: TxPayload,
        var cancelled: Boolean = false,
    )

    private data class RecentTx(
        val commandId: String,
        val deviceId: String,
        val atEpochMs: Long,
        val action: CommandAction,
    )

    private data class Capture(
        val value: Long,
        val protocol: Int,
        val length: Int,
        val delayMs: Int,
    )

    @Serializable
    private data class GatewayCapturePayload(
        val value: Long,
        val protocol: Int,
        val length: Int,
        val delay: Int = 330,
    )

    @Serializable
    private data class TxPayload(
        val value: Long,
        val protocol: Int,
        val length: Int,
        val repeat: Int,
    ) {
        fun key(): CodeKey = CodeKey(protocol, length, value)
    }

    private data class CodeKey(val protocol: Int, val length: Int, val value: Long)

    companion object {
        private const val SESSION_TTL_MS = 10 * 60 * 1000L
        private const val COLLECTION_WINDOW_MS = 60 * 1000L
        private const val CANDIDATE_WINDOW_MS = 30 * 1000L
        private const val READY_WINDOW_MS = 5 * 60 * 1000L
        private const val TERMINAL_RETENTION_MS = 5 * 60 * 1000L
        private const val IDEMPOTENCY_TTL_MS = 10 * 60 * 1000L
        private const val TOMBSTONE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val TX_CONFIRM_WINDOW_MS = 2 * 1000L
        private const val TEST_RATE_LIMIT_MS = 2 * 1000L
        private const val REQUIRED_HITS = 3
        private const val PRESS_GAP_MS = 600L
        private const val MAX_CANDIDATES = 32
        private val TERMINAL_STATES = setOf(
            LearningState.COMPLETED,
            LearningState.CANCELLED,
            LearningState.EXPIRED,
            LearningState.FAILED,
        )
        private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
