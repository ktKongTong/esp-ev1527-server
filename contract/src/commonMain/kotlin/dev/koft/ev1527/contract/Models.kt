package dev.koft.ev1527.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Semantic version of this shared contract module. */
const val CONTRACT_VERSION: String = "2.3.0"

/** HTTP API version carried in [HealthResponse.apiVersion]. */
const val API_VERSION: String = "1"

@Serializable
data class RfParameters(
    val protocol: Int,
    val length: Int,
    val repeat: Int = 5,
)

/**
 * A paired 315MHz remote. The RF link has no acknowledgement, so the server
 * cannot know whether the actuator is on or off; it only stores the two codes
 * that the remote sends.
 */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val location: String,
    val codeOn: Long,
    val codeOff: Long,
    val rf: RfParameters,
    val createdAtEpochMs: Long,
)

@Serializable
data class DeviceListResponse(
    val devices: List<Device>,
    val serverTimeEpochMs: Long,
)

/**
 * Partial update for a device. A null field leaves that value unchanged;
 * codes and RF parameters are immutable after learning.
 */
@Serializable
data class UpdateDeviceRequest(
    val name: String? = null,
    val location: String? = null,
)

@Serializable
enum class GatewayState {
    @SerialName("online") ONLINE,
    @SerialName("offline") OFFLINE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class ReceiverState {
    @SerialName("unknown") UNKNOWN,
    @SerialName("receiving") RECEIVING,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class ReceiverStatus(
    val state: ReceiverState,
    val lastCaptureAtEpochMs: Long? = null,
    val captureCount: Long,
)

@Serializable
data class GatewayStatus(
    val state: GatewayState,
    val version: String? = null,
    val clientId: String? = null,
    val lastChangedAtEpochMs: Long,
    val receiver: ReceiverStatus,
)

@Serializable
enum class HealthStatus {
    @SerialName("ok") OK,
    @SerialName("degraded") DEGRADED,
    @SerialName("down") DOWN,
}

@Serializable
data class HealthResponse(
    val status: HealthStatus,
    val service: String,
    val apiVersion: String,
    val version: String,
    val runtime: String,
    val uptimeSeconds: Long,
    val serverTimeEpochMs: Long,
    val gateway: GatewayStatus,
)

@Serializable
enum class CommandAction {
    @SerialName("turn_on") TURN_ON,
    @SerialName("turn_off") TURN_OFF,
}

@Serializable
data class CommandRequest(val action: CommandAction)

/** Result of a fire-and-forget command. Once published, nothing confirms it. */
@Serializable
data class CommandDispatched(
    val deviceId: String,
    val action: CommandAction,
    val dispatchedAtEpochMs: Long,
)

@Serializable
data class CommandFailure(
    val deviceId: String,
    val code: String,
)

/** Result of a group command fan-out. Per-device failures do not fail the batch. */
@Serializable
data class GroupCommandDispatched(
    val groupId: String,
    val action: CommandAction,
    val dispatchedCount: Int,
    val failedCount: Int,
    val failures: List<CommandFailure>,
    val dispatchedAtEpochMs: Long,
)

/**
 * A named set of devices. Membership is many-to-many: one device can belong to
 * several groups, and a group can contain any number of devices.
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val deviceIds: List<String>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class GroupListResponse(
    val groups: List<Group>,
    val serverTimeEpochMs: Long,
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val deviceIds: List<String>,
)

/** A null field stays unchanged; a present deviceIds replaces the full set. */
@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    val deviceIds: List<String>? = null,
)

@Serializable
enum class ScheduleTarget {
    @SerialName("device") DEVICE,
    @SerialName("group") GROUP,
}

@Serializable
enum class ScheduleResult {
    @SerialName("dispatched") DISPATCHED,
    @SerialName("partial") PARTIAL,
    @SerialName("skipped") SKIPPED,
    @SerialName("failed") FAILED,
}

/**
 * An alarm-like schedule. Recurrence is an RFC 5545 RRULE interpreted in
 * [timeZone]; [startsAtEpochMs] is DTSTART. The rule is not limited to a fixed
 * once/daily/weekly set.
 */
@Serializable
data class Schedule(
    val id: String,
    val name: String,
    val targetType: ScheduleTarget,
    val targetId: String,
    val action: CommandAction,
    val rrule: String,
    val timeZone: String,
    val startsAtEpochMs: Long,
    val enabled: Boolean,
    val nextRunAtEpochMs: Long? = null,
    val lastRunAtEpochMs: Long? = null,
    val lastResult: ScheduleResult? = null,
    val lastError: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class ScheduleListResponse(
    val schedules: List<Schedule>,
    val serverTimeEpochMs: Long,
)

@Serializable
data class CreateScheduleRequest(
    val name: String = "",
    val targetType: ScheduleTarget,
    val targetId: String,
    val action: CommandAction,
    val rrule: String,
    val timeZone: String,
    val startsAtEpochMs: Long,
    val enabled: Boolean? = null,
)

/** A null field stays unchanged. */
@Serializable
data class UpdateScheduleRequest(
    val name: String? = null,
    val targetType: ScheduleTarget? = null,
    val targetId: String? = null,
    val action: CommandAction? = null,
    val rrule: String? = null,
    val timeZone: String? = null,
    val startsAtEpochMs: Long? = null,
    val enabled: Boolean? = null,
)

@Serializable
enum class LearningState {
    @SerialName("collecting_on") COLLECTING_ON,
    @SerialName("candidate_on_ready") CANDIDATE_ON_READY,
    @SerialName("collecting_off") COLLECTING_OFF,
    @SerialName("candidate_off_ready") CANDIDATE_OFF_READY,
    @SerialName("ready") READY,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("expired") EXPIRED,
    @SerialName("failed") FAILED,
}

@Serializable
enum class LearningSlot {
    @SerialName("turn_on") TURN_ON,
    @SerialName("turn_off") TURN_OFF,
}

@Serializable
data class LearningCandidate(
    val id: String,
    val slot: LearningSlot,
    val value: Long,
    val protocol: Int,
    val length: Int,
    val delayMs: Int,
    val hitCount: Int,
    val frameCount: Int,
    val qualified: Boolean,
    val accepted: Boolean,
    val firstSeenAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val conflictDeviceId: String? = null,
)

@Serializable
data class LearningSession(
    val id: String,
    val state: LearningState,
    val revision: Long,
    val createdAtEpochMs: Long,
    val sessionExpiresAtEpochMs: Long,
    val collectionExpiresAtEpochMs: Long? = null,
    val candidateExpiresAtEpochMs: Long? = null,
    val requiredHits: Int,
    val candidates: List<LearningCandidate>,
    val acceptedOnCandidateId: String? = null,
    val acceptedOffCandidateId: String? = null,
    val failureReason: String? = null,
)

@Serializable
data class StartLearningRequest(
    val nameHint: String? = null,
    val locationHint: String? = null,
)

@Serializable
data class ConfirmLearningRequest(
    val name: String,
    val location: String,
)

@Serializable
data class LearningConfirmation(
    val device: Device,
    val session: LearningSession,
)

@Serializable
data class TestLearningRequest(val slot: LearningSlot)

@Serializable
data class LearningTestResult(
    val testId: String,
    val sessionId: String,
    val slot: LearningSlot,
    val value: Long,
    val dispatchedAtEpochMs: Long,
    val status: String = "dispatched",
)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val requestId: String,
    val details: Map<String, String>? = null,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)
