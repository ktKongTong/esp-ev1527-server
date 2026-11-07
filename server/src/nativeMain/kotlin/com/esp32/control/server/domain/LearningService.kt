package com.esp32.control.server.domain

import androidx.room3.withWriteTransaction
import com.esp32.control.contract.ConfirmLearningRequest
import com.esp32.control.contract.Device
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.LearningCandidate
import com.esp32.control.contract.LearningConfirmation
import com.esp32.control.contract.LearningSession
import com.esp32.control.contract.LearningSlot
import com.esp32.control.contract.LearningState
import com.esp32.control.contract.LearningTestResult
import com.esp32.control.contract.ReceiverState
import com.esp32.control.contract.RfParameters
import com.esp32.control.contract.StartLearningRequest
import com.esp32.control.server.ApiException
import com.esp32.control.server.Log
import com.esp32.control.server.data.DeviceRepository
import com.esp32.control.server.db.AppDatabase
import com.esp32.control.server.deviceRemoved
import com.esp32.control.server.invalidRequest
import com.esp32.control.server.learningNotFound
import com.esp32.control.server.mqtt.GatewayStatusStore
import com.esp32.control.server.mqtt.RfCapture
import com.esp32.control.server.mqtt.RfPayload
import com.esp32.control.server.mqtt.RfTransmitter
import com.esp32.control.server.newId
import com.esp32.control.server.nowEpochMs
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Learning sessions are runtime state. They live only in this map so a
 * restart invalidates in-progress sessions; only saved devices are persisted.
 * Every access happens under [mutex].
 */
class LearningService(
    private val db: AppDatabase,
    private val devices: DeviceRepository,
    private val gateway: GatewayStatusStore,
    private val transmitter: RfTransmitter,
) {
    private val mutex = Mutex()
    private val sessions = HashMap<String, LearningRecord>()

    suspend fun start(request: StartLearningRequest): LearningSession = mutex.withLock {
        val now = nowEpochMs()
        purgeLocked(now)

        val gatewayStatus = gateway.snapshot()
        if (gatewayStatus.state != GatewayState.ONLINE) {
            Log.warn {
                "[learning] start rejected: gateway=${gatewayStatus.state} " +
                    "receiver=${gatewayStatus.receiver.state}"
            }
            throw unavailable("gateway_unavailable", "Gateway is not online")
        }
        if (gatewayStatus.receiver.state == ReceiverState.UNAVAILABLE) {
            Log.warn { "[learning] start rejected: receiver unavailable" }
            throw unavailable("receiver_unavailable", "The 315MHz receiver is unavailable")
        }
        activeLocked()?.let { active ->
            Log.warn {
                "[learning] start rejected: session ${active.session.id} " +
                    "already active (${active.session.state})"
            }
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_busy",
                "A learning session is already active",
                mapOf("activeSessionId" to active.session.id),
            )
        }
        request.nameHint?.let {
            if (it.length > 64) {
                throw invalidRequest("nameHint is too long")
            }
        }
        request.locationHint?.let {
            if (it.length > 64) {
                throw invalidRequest("locationHint is too long")
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
        sessions[session.id] = LearningRecord(
            session = session,
            active = true,
            terminalAtEpochMs = null,
            deviceId = null,
        )
        Log.info {
            "[learning] session ${session.id} started: collect window " +
                "${COLLECTION_WINDOW_MS / 1000}s"
        }
        session
    }

    suspend fun getLearning(sessionId: String): LearningSession = mutex.withLock {
        purgeLocked(nowEpochMs())
        sessions[sessionId]?.session ?: throw learningNotFound()
    }

    suspend fun acceptCandidate(sessionId: String, candidateId: String): LearningSession =
        mutex.withLock {
            val now = nowEpochMs()
            val record = activeRecord(sessionId)
            val session = record.session
            val candidate = session.candidates.firstOrNull { it.id == candidateId }
                ?: throw ApiException(
                    HttpStatusCode.NotFound,
                    "candidate_not_found",
                    "Candidate not found",
                )

            val alreadyAccepted = candidate.accepted && (
                session.acceptedOnCandidateId == candidate.id ||
                    session.acceptedOffCandidateId == candidate.id
                )
            if (alreadyAccepted) {
                Log.debug { "[learning] accept ${candidate.id} ignored: already accepted" }
                return@withLock session
            }

            val state = session.state
            if (state != LearningState.CANDIDATE_ON_READY &&
                state != LearningState.CANDIDATE_OFF_READY
            ) {
                Log.warn {
                    "[learning] accept ${candidate.id} rejected: session ${session.id} state=$state"
                }
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
                Log.warn {
                    "[learning] accept ${candidate.id} rejected: slot=${candidate.slot} " +
                        "qualified=${candidate.qualified} accepted=${candidate.accepted} " +
                        "currentSlot=$currentSlot"
                }
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "candidate_not_ready",
                    "Candidate cannot be accepted in the current state",
                )
            }

            devices.findOwner(candidate.length, candidate.value)?.let { owner ->
                Log.warn {
                    "[learning] accept ${candidate.id} rejected: code ${candidate.value} " +
                        "owned by $owner"
                }
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "code_already_registered",
                    "RF code is already owned by another device",
                    mapOf("deviceId" to owner, "value" to candidate.value.toString()),
                )
            }
            if (currentSlot == LearningSlot.TURN_ON) {
                val gatewayStatus = gateway.snapshot()
                if (gatewayStatus.state != GatewayState.ONLINE) {
                    Log.warn {
                        "[learning] accept ${candidate.id} rejected: gateway=${gatewayStatus.state}"
                    }
                    throw unavailable("gateway_unavailable", "Gateway is not online")
                }
                if (gatewayStatus.receiver.state == ReceiverState.UNAVAILABLE) {
                    Log.warn {
                        "[learning] accept ${candidate.id} rejected: receiver unavailable"
                    }
                    throw unavailable(
                        "receiver_unavailable",
                        "The 315MHz receiver is unavailable",
                    )
                }
            }

            val acceptedCandidate = candidate.copy(accepted = true)
            fun mark(value: LearningCandidate): LearningCandidate =
                if (value.id == acceptedCandidate.id) acceptedCandidate else value

            val remaining = session.candidates
                .filter { it.accepted || it.id == candidate.id }
                .map(::mark)

            val updated = if (currentSlot == LearningSlot.TURN_ON) {
                session.copy(
                    state = LearningState.COLLECTING_OFF,
                    revision = session.revision + 1,
                    collectionExpiresAtEpochMs = now + COLLECTION_WINDOW_MS,
                    candidateExpiresAtEpochMs = null,
                    candidates = remaining,
                    acceptedOnCandidateId = candidate.id,
                )
            } else {
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
            sessions[sessionId] = record.copy(session = updated)
            Log.info {
                "[learning] session $sessionId accepted ${candidate.id} " +
                    "slot=$currentSlot value=${candidate.value} -> ${updated.state}"
            }
            updated
        }

    suspend fun rescan(sessionId: String): LearningSession = mutex.withLock {
        val now = nowEpochMs()
        val record = activeRecord(sessionId)
        val session = record.session
        val slot = when (session.state) {
            LearningState.COLLECTING_ON, LearningState.CANDIDATE_ON_READY ->
                LearningSlot.TURN_ON

            LearningState.COLLECTING_OFF, LearningState.CANDIDATE_OFF_READY ->
                LearningSlot.TURN_OFF

            else -> {
                Log.warn {
                    "[learning] rescan ${session.id} rejected: state=${session.state}"
                }
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "learning_not_ready",
                    "The session cannot be rescanned in its current state",
                )
            }
        }
        val gatewayStatus = gateway.snapshot()
        if (gatewayStatus.state != GatewayState.ONLINE) {
            Log.warn {
                "[learning] rescan ${session.id} rejected: gateway=${gatewayStatus.state}"
            }
            throw unavailable("gateway_unavailable", "Gateway is not online")
        }
        if (gatewayStatus.receiver.state == ReceiverState.UNAVAILABLE) {
            Log.warn { "[learning] rescan ${session.id} rejected: receiver unavailable" }
            throw unavailable("receiver_unavailable", "The 315MHz receiver is unavailable")
        }

        val updated = session.copy(
            state = if (slot == LearningSlot.TURN_ON) {
                LearningState.COLLECTING_ON
            } else {
                LearningState.COLLECTING_OFF
            },
            revision = session.revision + 1,
            collectionExpiresAtEpochMs = now + COLLECTION_WINDOW_MS,
            candidateExpiresAtEpochMs = null,
            candidates = session.candidates.filter { it.slot != slot || it.accepted },
        )
        sessions[sessionId] = record.copy(session = updated)
        Log.info { "[learning] session $sessionId rescanned: slot=$slot -> ${updated.state}" }
        updated
    }

    suspend fun testLearning(sessionId: String, slot: LearningSlot): LearningTestResult {
        var candidate: LearningCandidate? = null
        val testId = newId("test")

        mutex.withLock {
            val record = activeRecord(sessionId)
            val session = record.session
            if (session.state != LearningState.READY) {
                Log.warn {
                    "[learning] test ${session.id} rejected: state=${session.state}"
                }
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "learning_not_ready",
                    "The session is not ready for testing",
                )
            }
            if (!gateway.isOnline()) {
                Log.warn { "[learning] test ${session.id} rejected: gateway offline" }
                throw unavailable("gateway_unavailable", "Gateway is not online")
            }
            val acceptedId = if (slot == LearningSlot.TURN_ON) {
                session.acceptedOnCandidateId
            } else {
                session.acceptedOffCandidateId
            }
            candidate = session.candidates.firstOrNull { it.id == acceptedId && it.accepted }
                ?: run {
                    Log.warn {
                        "[learning] test ${session.id} rejected: no accepted candidate " +
                            "for slot=$slot"
                    }
                    throw ApiException(
                        HttpStatusCode.Conflict,
                        "learning_not_ready",
                        "The requested slot has no accepted candidate",
                    )
                }
        }

        val accepted = candidate ?: error("accepted candidate missing")
        val dispatchedAt = nowEpochMs()
        try {
            transmitter.send(
                RfPayload(
                    value = accepted.value,
                    protocol = accepted.protocol,
                    length = accepted.length,
                    repeat = 5,
                ),
            )
        } catch (e: ApiException) {
            throw e
        } catch (cause: Throwable) {
            Log.error(cause) { "[learning] test dispatch failed for session $sessionId" }
            throw unavailable("gateway_unavailable", "Failed to publish test code")
        }

        Log.info {
            "[learning] session $sessionId test dispatched slot=$slot value=${accepted.value}"
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
        val existing = sessions[sessionId] ?: throw learningNotFound()

        if (existing.session.state == LearningState.COMPLETED) {
            val deviceId = existing.deviceId ?: throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "Learning session is already completed",
            )
            val device = devices.find(deviceId) ?: run {
                devices.findTombstone(deviceId)?.let { throw deviceRemoved(it) }
                throw ApiException(
                    HttpStatusCode.Gone,
                    "device_removed",
                    "Device was removed",
                )
            }
            return@withLock LearningConfirmation(device = device, session = existing.session)
        }
        if (!existing.active) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "Learning session is no longer active",
            )
        }
        val session = existing.session
        if (session.state != LearningState.READY) {
            Log.warn { "[learning] confirm ${session.id} rejected: state=${session.state}" }
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "The session is not ready to save",
            )
        }

        val name = normalizeDeviceLabel(request.name)
        val location = normalizeDeviceLabel(request.location)
        if (name == null || location == null) {
            Log.warn { "[learning] confirm ${session.id} rejected: invalid name or location" }
            throw invalidRequest("name and location must be 1-64 characters")
        }

        val on = session.candidates.firstOrNull {
            it.id == session.acceptedOnCandidateId &&
                it.accepted &&
                it.slot == LearningSlot.TURN_ON
        } ?: throw ApiException(
            HttpStatusCode.Conflict,
            "learning_not_ready",
            "Accepted ON candidate is missing",
        )
        val off = session.candidates.firstOrNull {
            it.id == session.acceptedOffCandidateId &&
                it.accepted &&
                it.slot == LearningSlot.TURN_OFF
        } ?: throw ApiException(
            HttpStatusCode.Conflict,
            "learning_not_ready",
            "Accepted OFF candidate is missing",
        )

        if (on.value == off.value) throw codePairInvalid("same_code")
        if (!Ev1527.isValid(on.value) || !Ev1527.isValid(off.value)) {
            throw codePairInvalid("code_out_of_range")
        }
        if (!Ev1527.sameAddress(on.value, off.value)) {
            throw codePairInvalid("address_mismatch")
        }

        val device = db.withWriteTransaction {
            for (code in listOf(on, off)) {
                devices.findOwner(code.length, code.value)?.let { owner ->
                    throw ApiException(
                        HttpStatusCode.Conflict,
                        "code_already_registered",
                        "RF code is already owned by another device",
                        mapOf("deviceId" to owner, "value" to code.value.toString()),
                    )
                }
            }

            val created = Device(
                id = newId("dev"),
                name = name,
                location = location,
                codeOn = on.value,
                codeOff = off.value,
                rf = RfParameters(protocol = on.protocol, length = on.length, repeat = 5),
                createdAtEpochMs = now,
            )
            devices.insert(created)
            created
        }

        val completed = session.copy(
            state = LearningState.COMPLETED,
            revision = session.revision + 1,
            collectionExpiresAtEpochMs = null,
            candidateExpiresAtEpochMs = null,
        )
        sessions[sessionId] = LearningRecord(
            session = completed,
            active = false,
            terminalAtEpochMs = now,
            deviceId = device.id,
        )
        Log.info {
            "[learning] session $sessionId completed: device ${device.id} " +
                "on=${on.value} off=${off.value}"
        }
        LearningConfirmation(device = device, session = completed)
    }

    suspend fun cancelLearning(sessionId: String) {
        mutex.withLock {
            val record = sessions[sessionId] ?: run {
                Log.debug { "[learning] cancel $sessionId ignored: session not found" }
                return@withLock
            }
            if (record.session.state in TERMINAL_STATES) {
                Log.debug {
                    "[learning] cancel $sessionId ignored: state=${record.session.state}"
                }
                return@withLock
            }

            val cancelled = record.session.copy(
                state = LearningState.CANCELLED,
                revision = record.session.revision + 1,
                collectionExpiresAtEpochMs = null,
                candidateExpiresAtEpochMs = null,
            )
            val now = nowEpochMs()
            sessions[sessionId] = record.copy(
                session = cancelled,
                active = false,
                terminalAtEpochMs = now,
            )
            Log.info { "[learning] session $sessionId cancelled" }
        }
    }

    /**
     * A gateway that goes offline during collection fails the session. Candidate
     * confirmation and ready states are left for their own timers.
     */
    suspend fun onGatewayOffline(now: Long) {
        mutex.withLock {
            val record = activeLocked() ?: return@withLock
            val state = record.session.state
            if (state == LearningState.COLLECTING_ON || state == LearningState.COLLECTING_OFF) {
                failLocked(record, "gateway_offline", now)
            } else {
                Log.info {
                    "[learning] gateway offline during session ${record.session.id} " +
                        "state=$state, session kept"
                }
            }
        }
    }

    suspend fun onCapture(capture: RfCapture, now: Long) {
        mutex.withLock {
            val record = activeLocked()
            if (record == null) {
                Log.debug {
                    "[learning] capture ${capture.value} ignored: no active session"
                }
                return@withLock
            }
            val session = record.session
            val state = session.state
            if (state != LearningState.COLLECTING_ON && state != LearningState.COLLECTING_OFF) {
                Log.debug {
                    "[learning] capture ${capture.value} ignored: session ${session.id} " +
                        "state=$state (slot closed)"
                }
                return@withLock
            }
            val deadline = session.collectionExpiresAtEpochMs
            if (deadline == null) {
                Log.debug {
                    "[learning] capture ${capture.value} ignored: session ${session.id} " +
                        "has no collection window"
                }
                return@withLock
            }
            if (now >= deadline) {
                Log.debug {
                    "[learning] capture ${capture.value} ignored: collection window ended " +
                        "${now - deadline}ms ago"
                }
                return@withLock
            }
            // The gateway gates EV1527 before this point; keep the domain boundary closed too.
            if (!Ev1527.isValid(capture.value)) {
                Log.debug {
                    "[learning] capture ${capture.value} ignored: value out of EV1527 range"
                }
                return@withLock
            }

            val slot = if (state == LearningState.COLLECTING_ON) {
                LearningSlot.TURN_ON
            } else {
                LearningSlot.TURN_OFF
            }

            val existing = session.candidates.firstOrNull {
                it.slot == slot && it.value == capture.value
            }
            if (existing == null && session.candidates.size >= MAX_CANDIDATES) {
                Log.warn {
                    "[learning] capture ${capture.value} dropped: candidate limit " +
                        "$MAX_CANDIDATES reached"
                }
                return@withLock
            }
            val conflict = devices.findOwner(capture.length, capture.value)
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
                val newHits = if (gap >= PRESS_GAP_MS) {
                    existing.hitCount + 1
                } else {
                    existing.hitCount
                }
                existing.copy(
                    delayMs = capture.delayMs,
                    hitCount = newHits,
                    frameCount = existing.frameCount + 1,
                    qualified = newHits >= REQUIRED_HITS,
                    lastSeenAtEpochMs = now,
                    conflictDeviceId = conflict,
                )
            }

            Log.debug {
                "[learning] capture ${capture.value} slot=$slot " +
                    "hits=${updatedCandidate.hitCount}/$REQUIRED_HITS " +
                    "frames=${updatedCandidate.frameCount}" +
                    (conflict?.let { " conflict=$it" } ?: "")
            }

            var candidates = session.candidates.toMutableList()
            if (existing == null) {
                candidates.add(updatedCandidate)
            } else {
                val index = candidates.indexOfFirst { it.id == existing.id }
                candidates[index] = updatedCandidate
            }

            val qualifiedNow = candidates.any {
                it.slot == slot && it.hitCount >= REQUIRED_HITS
            }
            candidates = candidates.map {
                if (it.slot == slot && it.hitCount >= REQUIRED_HITS) {
                    it.copy(qualified = true)
                } else {
                    it
                }
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
                collectionExpiresAtEpochMs = if (qualifiedNow) {
                    null
                } else {
                    session.collectionExpiresAtEpochMs
                },
                candidateExpiresAtEpochMs = if (qualifiedNow) {
                    now + CANDIDATE_WINDOW_MS
                } else {
                    null
                },
                candidates = candidates,
            )
            sessions[record.session.id] = record.copy(session = updated)
            if (qualifiedNow) {
                Log.info {
                    "[learning] session ${session.id} slot=$slot qualified " +
                        "value=${capture.value} -> ${updated.state}"
                }
            }
        }
    }

    suspend fun tick() {
        mutex.withLock {
            val now = nowEpochMs()
            purgeLocked(now)
            val record = activeLocked() ?: return@withLock
            val active = record.session
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
                        expireLocked(
                            record,
                            if (hadSignal) "insufficient_hits" else "no_signal",
                            now,
                        )
                        return@withLock
                    }
                    if (now >= active.sessionExpiresAtEpochMs) {
                        expireLocked(record, "session_timeout", now)
                    }
                }

                LearningState.CANDIDATE_ON_READY, LearningState.CANDIDATE_OFF_READY -> {
                    val deadline = active.candidateExpiresAtEpochMs
                    if (deadline != null && now >= deadline) {
                        expireLocked(record, "candidate_timeout", now)
                    } else if (now >= active.sessionExpiresAtEpochMs) {
                        expireLocked(record, "session_timeout", now)
                    }
                }

                LearningState.READY -> {
                    if (now >= active.sessionExpiresAtEpochMs) {
                        expireLocked(record, "ready_timeout", now)
                    }
                }

                else -> Unit
            }
        }
    }

    private fun activeLocked(): LearningRecord? =
        sessions.values.firstOrNull { it.active }

    private fun activeRecord(sessionId: String): LearningRecord {
        purgeLocked(nowEpochMs())
        val record = sessions[sessionId] ?: throw learningNotFound()
        if (record.session.state in TERMINAL_STATES || !record.active) {
            throw ApiException(
                HttpStatusCode.Conflict,
                "learning_not_ready",
                "Learning session is no longer active",
            )
        }
        return record
    }

    private fun failLocked(record: LearningRecord, reason: String, now: Long) {
        val failed = record.session.copy(
            state = LearningState.FAILED,
            revision = record.session.revision + 1,
            collectionExpiresAtEpochMs = null,
            candidateExpiresAtEpochMs = null,
            failureReason = reason,
        )
        sessions[record.session.id] = record.copy(
            session = failed,
            active = false,
            terminalAtEpochMs = now,
        )
        Log.warn {
            "[learning] session ${record.session.id} failed: $reason " +
                "(was ${record.session.state})"
        }
    }

    private fun expireLocked(record: LearningRecord, reason: String, now: Long) {
        val expired = record.session.copy(
            state = LearningState.EXPIRED,
            revision = record.session.revision + 1,
            collectionExpiresAtEpochMs = null,
            candidateExpiresAtEpochMs = null,
            failureReason = reason,
        )
        sessions[record.session.id] = record.copy(
            session = expired,
            active = false,
            terminalAtEpochMs = now,
        )
        Log.info {
            "[learning] session ${record.session.id} expired: $reason " +
                "(was ${record.session.state})"
        }
    }

    private fun purgeLocked(now: Long) {
        val cutoffEpochMs = now - TERMINAL_RETENTION_MS
        sessions.values.removeAll { record ->
            !record.active &&
                record.terminalAtEpochMs != null &&
                record.terminalAtEpochMs < cutoffEpochMs
        }
    }

    private fun unavailable(code: String, message: String): ApiException =
        ApiException(HttpStatusCode.ServiceUnavailable, code, message)

    private fun codePairInvalid(reason: String): ApiException = ApiException(
        HttpStatusCode.UnprocessableEntity,
        "code_pair_invalid",
        "The learned ON/OFF pair is invalid",
        mapOf("reason" to reason),
    )

    companion object {
        private const val SESSION_TTL_MS = 10 * 60 * 1000L
        private const val COLLECTION_WINDOW_MS = 60 * 1000L
        private const val CANDIDATE_WINDOW_MS = 30 * 1000L
        private const val READY_WINDOW_MS = 5 * 60 * 1000L
        private const val TERMINAL_RETENTION_MS = 5 * 60 * 1000L
        private const val REQUIRED_HITS = 3
        private const val PRESS_GAP_MS = 600L
        private const val MAX_CANDIDATES = 32
        private val TERMINAL_STATES = setOf(
            LearningState.COMPLETED,
            LearningState.CANCELLED,
            LearningState.EXPIRED,
            LearningState.FAILED,
        )
    }
}

/**
 * A learning session plus the server-only lifecycle fields that are not part
 * of the wire contract. These values never leave this process.
 */
private data class LearningRecord(
    val session: LearningSession,
    val active: Boolean,
    val terminalAtEpochMs: Long?,
    val deviceId: String?,
)
