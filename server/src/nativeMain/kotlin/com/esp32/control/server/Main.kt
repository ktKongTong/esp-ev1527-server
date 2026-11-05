package com.esp32.control.server

import com.esp32.control.contract.API_VERSION
import com.esp32.control.contract.ApiErrorBody
import com.esp32.control.contract.ApiErrorEnvelope
import com.esp32.control.contract.CONTRACT_VERSION
import com.esp32.control.contract.ContractJson
import com.esp32.control.contract.HealthResponse
import com.esp32.control.contract.HealthStatus
import com.esp32.control.server.data.DeviceRepository
import com.esp32.control.server.data.IdempotencyRepository
import com.esp32.control.server.db.createDatabase
import com.esp32.control.server.domain.CommandService
import com.esp32.control.server.domain.DeviceService
import com.esp32.control.server.domain.LearningService
import com.esp32.control.server.mqtt.GatewayIngest
import com.esp32.control.server.mqtt.GatewayStatusStore
import com.esp32.control.server.mqtt.MqttRfTransmitter
import com.esp32.control.server.mqtt.MqttTransport
import com.esp32.control.server.routes.deviceRoute
import com.esp32.control.server.routes.learningRoute
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ensureActive


fun main() {
    val config = runCatching { ServerConfig.fromEnvironment() }.getOrElse { cause ->
        println("Configuration error: ${cause.message}")
        exitProcess(1)
    }
    val startedAtEpochMs = nowEpochMs()
    val database = createDatabase(config.databasePath)
    val deviceRepository = DeviceRepository(database)
    val idempotencyRepository = IdempotencyRepository(database)
    val gateway = GatewayStatusStore(config.gatewayClientId)

    // MQTT callbacks need the ingest service, while the services need the MQTT
    // transmitter. The holder closes that wiring cycle without a god object.
    var ingest: GatewayIngest? = null

    val mqtt = MqttTransport(
        config = config.mqtt,
        topicFilter = config.subscribedTopicFilter,
        onMessage = { message -> ingest?.onMessage(message.topic, message.payload) },
        onDisconnected = { ingest?.onDisconnected() },
    )
    val transmitter = MqttRfTransmitter(mqtt, config.commandTopic)

    val commandService = CommandService(
        db = database,
        devices = deviceRepository,
        idempotency = idempotencyRepository,
        gateway = gateway,
        transmitter = transmitter,
    )
    val deviceService = DeviceService(
        db = database,
        devices = deviceRepository,
    )
    val learningService = LearningService(
        db = database,
        devices = deviceRepository,
        gateway = gateway,
        transmitter = transmitter,
    )
    val gatewayIngest = GatewayIngest(
        config = config,
        gateway = gateway,
        learning = learningService,
    )
    ingest = gatewayIngest

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 单独一个协程做 mqtt client
    mqtt.start()
    // 定时任务
    scope.launch {
        while (isActive) {
            delay(500.milliseconds)
            runCatching { learningService.tick() }.onFailure {
                if (it is CancellationException) {
                    ensureActive()
                }
            }
            runCatching { commandService.purge(nowEpochMs()) }.onFailure {
                if (it is CancellationException) {
                    ensureActive()
                }
            }
            runCatching { deviceService.purge(nowEpochMs()) }.onFailure {
                if (it is CancellationException) {
                    ensureActive()
                }
            }
        }
    }

    embeddedServer(CIO, host = config.host, port = config.port) {
        installModules()

        routing {
            get("/") {
                call.respondText(
                    "ESP32 control server ${SERVER_VERSION} (contract $CONTRACT_VERSION)\n" +
                            "API base: /api/v1\n",
                )
            }

            route("/api/v1") {
                get("/health") {
                    val now = nowEpochMs()
                    call.respond(
                        HealthResponse(
                            status = HealthStatus.OK,
                            service = "esp32-control-server",
                            apiVersion = API_VERSION,
                            version = SERVER_VERSION,
                            runtime = "Kotlin/Native",
                            uptimeSeconds = (now - startedAtEpochMs).coerceAtLeast(0) / 1000,
                            serverTimeEpochMs = now,
                            gateway = gateway.snapshot(),
                        ),
                    )
                }
                deviceRoute(deviceService, commandService)
                learningRoute(learningService)
            }
        }
    }.start(wait = true)
}