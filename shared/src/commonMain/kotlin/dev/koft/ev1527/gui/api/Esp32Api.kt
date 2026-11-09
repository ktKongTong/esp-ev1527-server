package dev.koft.ev1527.gui.api

import dev.koft.ev1527.contract.ApiErrorEnvelope
import dev.koft.ev1527.contract.CommandAction
import dev.koft.ev1527.contract.CommandRequest
import dev.koft.ev1527.contract.ConfirmLearningRequest
import dev.koft.ev1527.contract.ContractJson
import dev.koft.ev1527.contract.CreateGroupRequest
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
import dev.koft.ev1527.contract.StartLearningRequest
import dev.koft.ev1527.contract.TestLearningRequest
import dev.koft.ev1527.contract.UpdateDeviceRequest
import dev.koft.ev1527.contract.UpdateGroupRequest
import dev.koft.ev1527.contract.UpdateScheduleRequest
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.uuid.Uuid

expect fun createPlatformHttpClient(authToken: String = ""): HttpClient

expect fun apiBaseUrl(): String

internal fun HttpClientConfig<*>.configureEsp32Client(authToken: String = "") {
    expectSuccess = false
    install(ContentNegotiation) {
        json(ContractJson)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 10_000
    }
    if (authToken.isNotEmpty()) {
        // Sent on every call, including health: the client never needs a
        // separate authentication step, and the token is not part of the URL.
        install(DefaultRequest) {
            header(HttpHeaders.Authorization, "Bearer $authToken")
        }
    }
}

class ClientApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val requestId: String? = null,
) : RuntimeException(message)

/** The addressed resource no longer exists on the current server. */
internal fun Throwable.isGone(): Boolean =
    this is ClientApiException && (status == 404 || status == 410)

class Esp32Api(
    val baseUrl: String,
    val token: String = "",
    private val client: HttpClient = createPlatformHttpClient(token),
) {
    private val root: String = "$baseUrl/api/v1"

    suspend fun health(): HealthResponse = client.get("$root/health").bodyOrThrow()

    suspend fun deviceList(): DeviceListResponse = client.get("$root/devices").bodyOrThrow()

    suspend fun devices(): List<Device> = deviceList().devices

    /**
     * The command is fire-and-forget: nothing confirms that the RF code was
     * received, so the response body carries no data the client can use.
     * Only the status code matters.
     */
    suspend fun sendCommand(
        deviceId: String,
        action: CommandAction,
        idempotencyKey: String,
    ) {
        client.post("$root/devices/$deviceId/commands") {
            header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(CommandRequest(action))
        }.ensureSuccess()
    }

    suspend fun updateDevice(
        deviceId: String,
        name: String,
        location: String,
    ): Device = client.patch("$root/devices/$deviceId") {
        contentType(ContentType.Application.Json)
        setBody(UpdateDeviceRequest(name = name, location = location))
    }.bodyOrThrow()

    suspend fun removeDevice(deviceId: String) {
        client.delete("$root/devices/$deviceId").ensureSuccess()
    }

    suspend fun groups(): GroupListResponse = client.get("$root/groups").bodyOrThrow()

    suspend fun createGroup(name: String, deviceIds: List<String>): Group =
        client.post("$root/groups") {
            contentType(ContentType.Application.Json)
            setBody(CreateGroupRequest(name = name, deviceIds = deviceIds))
        }.bodyOrThrow()

    suspend fun updateGroup(
        groupId: String,
        name: String? = null,
        deviceIds: List<String>? = null,
    ): Group = client.patch("$root/groups/$groupId") {
        contentType(ContentType.Application.Json)
        setBody(UpdateGroupRequest(name = name, deviceIds = deviceIds))
    }.bodyOrThrow()

    suspend fun removeGroup(groupId: String) {
        client.delete("$root/groups/$groupId").ensureSuccess()
    }

    suspend fun sendGroupCommand(
        groupId: String,
        action: CommandAction,
        idempotencyKey: String,
    ): GroupCommandDispatched =
        client.post("$root/groups/$groupId/commands") {
            header("Idempotency-Key", idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(CommandRequest(action))
        }.bodyOrThrow()

    suspend fun schedules(): ScheduleListResponse =
        client.get("$root/schedules").bodyOrThrow()

    suspend fun createSchedule(request: CreateScheduleRequest): Schedule =
        client.post("$root/schedules") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    suspend fun updateSchedule(
        scheduleId: String,
        request: UpdateScheduleRequest,
    ): Schedule = client.patch("$root/schedules/$scheduleId") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.bodyOrThrow()

    suspend fun removeSchedule(scheduleId: String) {
        client.delete("$root/schedules/$scheduleId").ensureSuccess()
    }

    suspend fun startLearning(
        nameHint: String? = null,
        locationHint: String? = null,
    ): LearningSession = client.post("$root/learning/sessions") {
        contentType(ContentType.Application.Json)
        setBody(StartLearningRequest(nameHint = nameHint, locationHint = locationHint))
    }.bodyOrThrow()

    suspend fun learning(sessionId: String): LearningSession =
        client.get("$root/learning/sessions/$sessionId").bodyOrThrow()

    suspend fun acceptCandidate(sessionId: String, candidateId: String): LearningSession =
        client.post("$root/learning/sessions/$sessionId/candidates/$candidateId/accept").bodyOrThrow()

    suspend fun rescan(sessionId: String): LearningSession =
        client.post("$root/learning/sessions/$sessionId/rescan").bodyOrThrow()

    suspend fun testLearning(sessionId: String, slot: LearningSlot): LearningTestResult =
        client.post("$root/learning/sessions/$sessionId/test") {
            contentType(ContentType.Application.Json)
            setBody(TestLearningRequest(slot))
        }.bodyOrThrow()

    suspend fun confirmLearning(
        sessionId: String,
        name: String,
        location: String,
    ): LearningConfirmation = client.post("$root/learning/sessions/$sessionId/confirm") {
        contentType(ContentType.Application.Json)
        setBody(ConfirmLearningRequest(name = name, location = location))
    }.bodyOrThrow()

    suspend fun cancelLearning(sessionId: String) {
        client.delete("$root/learning/sessions/$sessionId").ensureSuccess()
    }

    fun close() {
        client.close()
    }

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        ensureSuccess()
        return body()
    }

    private suspend fun HttpResponse.ensureSuccess() {
        if (status.value in 200..299) return
        val envelope = runCatching { body<ApiErrorEnvelope>() }.getOrNull()
        throw ClientApiException(
            status = status.value,
            code = envelope?.error?.code ?: "http_${status.value}",
            message = envelope?.error?.message ?: "HTTP ${status.value}",
            requestId = envelope?.error?.requestId,
        )
    }
}

fun newIdempotencyKey(): String = Uuid.random().toString()
