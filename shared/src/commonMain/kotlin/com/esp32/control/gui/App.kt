package com.esp32.control.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.CommandRecord
import com.esp32.control.contract.CommandStatus
import com.esp32.control.contract.Device
import com.esp32.control.contract.DeviceState
import com.esp32.control.contract.HealthResponse
import com.esp32.control.contract.HealthStatus
import com.esp32.control.contract.LearningCandidate
import com.esp32.control.contract.LearningSession
import com.esp32.control.contract.LearningSlot
import com.esp32.control.contract.LearningState
import com.esp32.control.contract.ReceiverState
import com.esp32.control.contract.StateEvidence
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.CONTRACT_VERSION
import com.esp32.control.gui.api.ClientApiException
import com.esp32.control.gui.api.Esp32Api
import com.esp32.control.gui.api.newIdempotencyKey
import com.esp32.control.gui.theme.Esp32Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    Esp32Theme {
        val api = remember { Esp32Api() }
        val scope = rememberCoroutineScope()

        var health by remember { mutableStateOf<HealthResponse?>(null) }
        var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var pendingDeviceId by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var commandStatuses by remember { mutableStateOf<Map<String, CommandRecord>>(emptyMap()) }
        var removalTarget by remember { mutableStateOf<Device?>(null) }

        var showLearning by remember { mutableStateOf(false) }
        var learning by remember { mutableStateOf<LearningSession?>(null) }
        var learningId by remember { mutableStateOf<String?>(null) }
        var learningBusy by remember { mutableStateOf(false) }
        var learningError by remember { mutableStateOf<String?>(null) }
        var testMessage by remember { mutableStateOf<String?>(null) }
        var completedDeviceName by remember { mutableStateOf<String?>(null) }

        suspend fun loadAll() {
            loading = true
            try {
                health = api.health()
                val response = api.deviceList()
                devices = response.devices
                error = null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error = t.readableMessage()
            } finally {
                loading = false
            }
        }

        fun refreshDevices() {
            scope.launch {
                try {
                    devices = api.devices()
                    health = api.health()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    error = t.readableMessage()
                }
            }
        }

        fun toggleDevice(device: Device) {
            if (pendingDeviceId != null) return
            pendingDeviceId = device.id
            scope.launch {
                try {
                    val action = if (device.state == DeviceState.ON) {
                        CommandAction.TURN_OFF
                    } else {
                        CommandAction.TURN_ON
                    }
                    val accepted = api.sendCommand(
                        deviceId = device.id,
                        action = action,
                        idempotencyKey = newIdempotencyKey(),
                    )
                    devices = devices.map { if (it.id == accepted.device.id) accepted.device else it }
                    commandStatuses = commandStatuses + (device.id to accepted.command)
                    error = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    error = t.readableMessage()
                    if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                        refreshDevices()
                    }
                } finally {
                    pendingDeviceId = null
                }
            }
        }

        fun removeDevice(device: Device) {
            removalTarget = null
            scope.launch {
                try {
                    api.removeDevice(device.id)
                    devices = devices.filterNot { it.id == device.id }
                    commandStatuses = commandStatuses - device.id
                    error = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                        devices = devices.filterNot { it.id == device.id }
                    } else {
                        error = t.readableMessage()
                    }
                }
            }
        }

        fun startLearning() {
            if (learningBusy) return
            learningBusy = true
            learningError = null
            scope.launch {
                try {
                    val session = api.startLearning()
                    learning = session
                    learningId = session.id
                    showLearning = true
                    testMessage = null
                    completedDeviceName = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    learningError = t.readableMessage()
                    showLearning = true
                } finally {
                    learningBusy = false
                }
            }
        }

        fun closeLearning() {
            val session = learning
            if (session != null && session.state !in TERMINAL_LEARNING_STATES) {
                scope.launch {
                    runCatching { api.cancelLearning(session.id) }
                }
            }
            showLearning = false
            learningId = null
            learning = null
            learningError = null
            testMessage = null
            completedDeviceName = null
        }

        fun acceptCandidate(candidate: LearningCandidate) {
            val session = learning ?: return
            if (learningBusy) return
            learningBusy = true
            scope.launch {
                try {
                    learning = api.acceptCandidate(session.id, candidate.id)
                    learningError = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    learningError = t.readableMessage()
                } finally {
                    learningBusy = false
                }
            }
        }

        fun rescan() {
            val session = learning ?: return
            if (learningBusy) return
            learningBusy = true
            scope.launch {
                try {
                    learning = api.rescan(session.id)
                    learningError = null
                    testMessage = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    learningError = t.readableMessage()
                } finally {
                    learningBusy = false
                }
            }
        }

        fun test(slot: LearningSlot) {
            val session = learning ?: return
            if (learningBusy) return
            learningBusy = true
            scope.launch {
                try {
                    api.testLearning(session.id, slot)
                    testMessage = if (slot == LearningSlot.TURN_ON) {
                        "已试发开码，请观察设备是否动作。"
                    } else {
                        "已试发关码，请观察设备是否停止。"
                    }
                    learningError = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    learningError = t.readableMessage()
                } finally {
                    learningBusy = false
                }
            }
        }

        fun confirmLearning(name: String, location: String) {
            val session = learning ?: return
            if (learningBusy) return
            learningBusy = true
            scope.launch {
                try {
                    val confirmation = api.confirmLearning(session.id, name, location)
                    learning = confirmation.session
                    learningId = null
                    completedDeviceName = confirmation.device.name
                    devices = api.devices()
                    health = api.health()
                    learningError = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    learningError = t.readableMessage()
                } finally {
                    learningBusy = false
                }
            }
        }

        LaunchedEffect(Unit) {
            loadAll()
        }

        LaunchedEffect(Unit) {
            while (isActive) {
                delay(30_000)
                runCatching { health = api.health() }
            }
        }

        LaunchedEffect(learningId) {
            val id = learningId ?: return@LaunchedEffect
            while (isActive) {
                delay(1_000)
                try {
                    val session = api.learning(id)
                    learning = session
                    if (session.state in TERMINAL_LEARNING_STATES) {
                        learningId = null
                        if (session.state == LearningState.COMPLETED) {
                            devices = api.devices()
                        }
                        break
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    learningError = t.readableMessage()
                    learningId = null
                    break
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose { api.close() }
        }

        val gatewayOnline = health?.gateway?.state == GatewayState.ONLINE
        val canStartLearning = gatewayOnline &&
            health?.gateway?.receiver?.state != ReceiverState.UNAVAILABLE &&
            learningId == null

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    title = {
                        Column {
                            Text(
                                text = if (showLearning) "学习新设备" else "ESP32 · 智能控制台",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (showLearning) {
                                    learning?.state?.let(::learningStateLabel) ?: "读取网关状态后开始"
                                } else {
                                    "Material You · ${platformLabel()}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (showLearning) {
                            TextButton(onClick = { closeLearning() }) {
                                Text("返回")
                            }
                        } else {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(
                                    onClick = { scope.launch { loadAll() } },
                                ) {
                                    Text("刷新")
                                }
                            }
                            TextButton(
                                onClick = {
                                    showLearning = true
                                    learningError = null
                                },
                                enabled = canStartLearning,
                            ) {
                                Text("学习新设备")
                            }
                        }
                    },
                )
            },
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (showLearning) {
                    LearningScreen(
                        health = health,
                        learning = learning,
                        busy = learningBusy,
                        errorText = learningError ?: error,
                        testMessage = testMessage,
                        completedDeviceName = completedDeviceName,
                        canStart = canStartLearning,
                        onStart = ::startLearning,
                        onRetryStart = {
                            learning = null
                            learningError = null
                            startLearning()
                        },
                        onAccept = ::acceptCandidate,
                        onRescan = ::rescan,
                        onTest = ::test,
                        onConfirm = ::confirmLearning,
                        onCancel = { closeLearning() },
                        onBackToDevices = { closeLearning() },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 1100.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        error?.let { message ->
                            ErrorBanner(message = message, onDismiss = { error = null })
                            Spacer(Modifier.height(16.dp))
                        }

                        HeroPanel(health = health)

                        Spacer(Modifier.height(16.dp))

                        ServerPanel(health = health, baseUrl = api.baseUrl)

                        Spacer(Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "设备",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = when {
                                        devices.isEmpty() && gatewayOnline ->
                                            "尚无设备，点击右上角“学习新设备”添加"
                                        devices.isEmpty() -> "网关未在线，暂时无法控制或学习"
                                        else -> "${devices.size} 台设备 · 显式开关命令"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (devices.isNotEmpty()) {
                                StatusPill(
                                    text = "${devices.count { it.state == DeviceState.ON }} 台开启",
                                    highlighted = devices.any { it.state == DeviceState.ON },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        when {
                            devices.isEmpty() && loading -> LoadingDevices()
                            devices.isEmpty() -> EmptyDevices(
                                gatewayOnline = gatewayOnline,
                                onLearn = {
                                    showLearning = true
                                    learningError = null
                                },
                            )
                            else -> DeviceGrid(
                                devices = devices,
                                pendingDeviceId = pendingDeviceId,
                                commandStatuses = commandStatuses,
                                gatewayOnline = gatewayOnline,
                                onToggle = ::toggleDevice,
                                onRemove = { removalTarget = it },
                            )
                        }

                        Spacer(Modifier.height(28.dp))
                        FooterPanel(apiBaseUrl = api.baseUrl)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        removalTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { removalTarget = null },
                title = { Text("移除设备？") },
                text = {
                    Text(
                        "将从服务端删除“${target.name}”，并释放开码/关码。" +
                            "此操作不会向网关发送任何 RF 指令，也无法撤销已下发的物理动作。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { removeDevice(target) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("移除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { removalTarget = null }) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

@Composable
private fun HeroPanel(health: HealthResponse?) {
    val gateway = health?.gateway
    val gatewayText = when (gateway?.state) {
        GatewayState.ONLINE -> "网关在线"
        GatewayState.OFFLINE -> "网关离线"
        GatewayState.UNKNOWN -> "网关状态未知"
        null -> "服务端未连接"
    }
    val receiverText = when (gateway?.receiver?.state) {
        ReceiverState.RECEIVING -> "接收器已观察到 RF"
        ReceiverState.UNAVAILABLE -> "接收器不可用"
        ReceiverState.UNKNOWN -> "尚未观察到 RF"
        null -> "接收器未知"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "315",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "鱼塘增氧控制",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "控制、学习、移除都由服务端状态机约束",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(text = gatewayText, highlighted = gateway?.state == GatewayState.ONLINE)
                StatusPill(text = receiverText, highlighted = gateway?.receiver?.state == ReceiverState.RECEIVING)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = if (gateway?.state == GatewayState.ONLINE) {
                    "Ktor + Kotlin/Native 服务端已连接 MQTT；学习时请按同一个遥控器按键 3 次。"
                } else {
                    "控制与学习要求网关 LWT 为 online。启动 mock/ 与 MQTT Broker 后点击“刷新”。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun ServerPanel(health: HealthResponse?, baseUrl: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "服务端状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = when (health?.status) {
                        HealthStatus.OK -> "API 正常"
                        HealthStatus.DEGRADED -> "服务降级"
                        HealthStatus.DOWN -> "服务不可用"
                        null -> "离线"
                    },
                    highlighted = health?.status == HealthStatus.OK,
                )
            }

            Spacer(Modifier.height(14.dp))

            HealthRow("API 地址", "$baseUrl/api/v1", monospace = true)
            HealthRow("运行时", health?.runtime ?: "—")
            HealthRow("版本", health?.version ?: "—")
            HealthRow("契约", "$CONTRACT_VERSION · apiVersion ${health?.apiVersion ?: "—"}")
            HealthRow("运行时长", health?.uptimeSeconds?.let(::formatUptime) ?: "—")
            HealthRow("网关", gatewayLabel(health?.gateway?.state))
            HealthRow("接收器", receiverLabel(health?.gateway?.receiver?.state))

            Spacer(Modifier.height(12.dp))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "已订阅 home/OpenMQTTGateway/#，命令发布到 MQTTto315。" +
                    "客户端使用 1 秒/30 秒轮询刷新状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceGrid(
    devices: List<Device>,
    pendingDeviceId: String?,
    commandStatuses: Map<String, CommandRecord>,
    gatewayOnline: Boolean,
    onToggle: (Device) -> Unit,
    onRemove: (Device) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 760.dp
        if (twoColumns) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                devices.chunked(2).forEach { rowDevices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        rowDevices.forEach { device ->
                            DeviceCard(
                                device = device,
                                pending = pendingDeviceId == device.id,
                                command = commandStatuses[device.id],
                                gatewayOnline = gatewayOnline,
                                onToggle = { onToggle(device) },
                                onRemove = { onRemove(device) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowDevices.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                devices.forEach { device ->
                    DeviceCard(
                        device = device,
                        pending = pendingDeviceId == device.id,
                        command = commandStatuses[device.id],
                        gatewayOnline = gatewayOnline,
                        onToggle = { onToggle(device) },
                        onRemove = { onRemove(device) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    pending: Boolean,
    command: CommandRecord?,
    gatewayOnline: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOn = device.state == DeviceState.ON
    val containerColor = if (isOn) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isOn) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isOn) "ON" else "OFF",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isOn) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = device.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.72f),
                    )
                }

                StatusPill(
                    text = deviceStatusLabel(device),
                    highlighted = isOn && device.online,
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "最近操作",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.68f),
                    )
                    Text(
                        text = lastActionLabel(device),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    command?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "命令：${commandStatusLabel(it.status)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.72f),
                        )
                    }
                }
                if (pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                } else {
                    Switch(
                        checked = isOn,
                        onCheckedChange = { onToggle() },
                        enabled = device.online && gatewayOnline,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            HorizontalDivider(color = contentColor.copy(alpha = 0.12f))

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                CodeLabel(label = "开码", code = device.codeOn)
                Spacer(Modifier.width(18.dp))
                CodeLabel(label = "关码", code = device.codeOff)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) {
                    Text("移除")
                }
            }
        }
    }
}

@Composable
private fun CodeLabel(label: String, code: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = code.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LearningScreen(
    health: HealthResponse?,
    learning: LearningSession?,
    busy: Boolean,
    errorText: String?,
    testMessage: String?,
    completedDeviceName: String?,
    canStart: Boolean,
    onStart: () -> Unit,
    onRetryStart: () -> Unit,
    onAccept: (LearningCandidate) -> Unit,
    onRescan: () -> Unit,
    onTest: (LearningSlot) -> Unit,
    onConfirm: (String, String) -> Unit,
    onCancel: () -> Unit,
    onBackToDevices: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        errorText?.let {
            ErrorBanner(message = it, onDismiss = {})
            Spacer(Modifier.height(16.dp))
        }

        val gateway = health?.gateway
        val receiver = gateway?.receiver?.state

        if (learning == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "准备学习",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "学习不是向网关发送“学习模式”命令。服务端只打开一个采集窗口，" +
                            "网关接收器常开并将 315MHz 捕获上报到 MQTT。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    LearningPrerequisite("网关连接", gatewayStateLabel(gateway?.state), gateway?.state == GatewayState.ONLINE)
                    LearningPrerequisite(
                        "315MHz 接收器",
                        receiverLabel(receiver),
                        receiver != ReceiverState.UNAVAILABLE,
                    )
                    LearningPrerequisite(
                        "按键要求",
                        "同一个键连续按 3 次，每次间隔约 1 秒",
                        true,
                    )
                    if (receiver == ReceiverState.UNKNOWN) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "尚未观察到 RF 信号。unknown 不等于故障，可以开始，但请先确认天线与遥控器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onStart,
                        enabled = canStart && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text("开始学习")
                    }
                    if (!canStart) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "开始学习要求网关 online 且接收器不是 unavailable。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            return
        }

        when (learning.state) {
            LearningState.COLLECTING_ON, LearningState.COLLECTING_OFF ->
                CollectingPanel(learning = learning, busy = busy, onRescan = onRescan)

            LearningState.CANDIDATE_ON_READY, LearningState.CANDIDATE_OFF_READY ->
                CandidatePanel(
                    learning = learning,
                    busy = busy,
                    onAccept = onAccept,
                    onRescan = onRescan,
                )

            LearningState.READY ->
                ReadyPanel(
                    learning = learning,
                    busy = busy,
                    testMessage = testMessage,
                    onTest = onTest,
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                )

            LearningState.COMPLETED ->
                CompletedPanel(
                    learning = learning,
                    deviceName = completedDeviceName ?: "新设备",
                    onBack = onBackToDevices,
                )

            LearningState.EXPIRED, LearningState.FAILED, LearningState.CANCELLED ->
                TerminalPanel(
                    learning = learning,
                    onRetry = onRetryStart,
                    onBack = onBackToDevices,
                )
        }
    }
}

@Composable
private fun LearningPrerequisite(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(110.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        StatusPill(text = if (ok) "可继续" else "不可用", highlighted = ok)
    }
}

@Composable
private fun CollectingPanel(
    learning: LearningSession,
    busy: Boolean,
    onRescan: () -> Unit,
) {
    val slot = if (learning.state == LearningState.COLLECTING_ON) LearningSlot.TURN_ON else LearningSlot.TURN_OFF
    val candidates = learning.candidates.filter { it.slot == slot }
    val best = candidates.maxByOrNull { it.hitCount }
    val title = if (slot == LearningSlot.TURN_ON) {
        "请连续按遥控器开键 3 次"
    } else {
        "已确认开码，请连续按关键 3 次"
    }
    LearningCard(title = title) {
        Text(
            text = "当前进度：${best?.hitCount ?: 0}/${learning.requiredHits} 次独立按键" +
                if (best != null) " · ${best.frameCount} 帧" else "",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "每次按键间隔约 1 秒；400ms 内的重复帧会被合并为同一次按键。" +
                "采集窗口为 60 秒，达到 3 次后立即进入候选确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (candidates.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            candidates.sortedByDescending { it.hitCount }.take(6).forEach { candidate ->
                CandidateCompact(candidate = candidate, requiredHits = learning.requiredHits)
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRescan, enabled = !busy) {
            Text("都不对，重新采集")
        }
    }
}

@Composable
private fun CandidateCompact(candidate: LearningCandidate, requiredHits: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = candidate.value.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${candidate.hitCount}/$requiredHits 次 · ${candidate.frameCount} 帧",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (candidate.conflictDeviceId != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "冲突",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CandidatePanel(
    learning: LearningSession,
    busy: Boolean,
    onAccept: (LearningCandidate) -> Unit,
    onRescan: () -> Unit,
) {
    val slot = if (learning.state == LearningState.CANDIDATE_ON_READY) {
        LearningSlot.TURN_ON
    } else {
        LearningSlot.TURN_OFF
    }
    val candidates = learning.candidates.filter { it.slot == slot }
    LearningCard(
        title = if (slot == LearningSlot.TURN_ON) {
            "已识别到开码候选，请确认"
        } else {
            "已识别到关码候选，请确认"
        },
    ) {
        Text(
            text = "服务端不会自动判定开/关。选择正确的候选后才会进入下一步。" +
                "未达到 ${learning.requiredHits} 次独立按键的候选不能接受。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        candidates.sortedWith(
            compareByDescending<LearningCandidate> { it.qualified }.thenByDescending { it.hitCount },
        ).forEach { candidate ->
            CandidateCard(
                candidate = candidate,
                requiredHits = learning.requiredHits,
                slot = slot,
                busy = busy,
                onAccept = { onAccept(candidate) },
            )
            Spacer(Modifier.height(10.dp))
        }
        if (candidates.none { it.qualified }) {
            Text(
                text = "目前没有达标候选。请重新采集，并连续按同一个键 3 次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))
        }
        FilledTonalButton(onClick = onRescan, enabled = !busy) {
            Text("都不对，重新采集")
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: LearningCandidate,
    requiredHits: Int,
    slot: LearningSlot,
    busy: Boolean,
    onAccept: () -> Unit,
) {
    val conflict = candidate.conflictDeviceId != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (candidate.qualified) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (candidate.qualified) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "码值 ${candidate.value}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "协议 ${candidate.protocol} · ${candidate.length} bit · delay ${candidate.delayMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                StatusPill(
                    text = if (candidate.qualified) "已达标" else "未达标",
                    highlighted = candidate.qualified,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${candidate.hitCount}/$requiredHits 次独立按键 · ${candidate.frameCount} 帧",
                style = MaterialTheme.typography.bodySmall,
            )
            if (conflict) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "该码已被设备 ${candidate.conflictDeviceId} 使用，不能接受。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAccept,
                enabled = candidate.qualified && !candidate.accepted && !conflict && !busy,
            ) {
                Text(if (slot == LearningSlot.TURN_ON) "这是开键" else "这是关键")
            }
        }
    }
}

@Composable
private fun ReadyPanel(
    learning: LearningSession,
    busy: Boolean,
    testMessage: String?,
    onTest: (LearningSlot) -> Unit,
    onConfirm: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    val on = learning.candidates.firstOrNull { it.id == learning.acceptedOnCandidateId }
    val off = learning.candidates.firstOrNull { it.id == learning.acceptedOffCandidateId }
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }

    LearningCard(title = "等待试发、命名与保存") {
        Text(
            text = "开码与关码都已由你确认。可以先试发观察设备，再命名保存；保存成功后才会写入设备注册表与码归属。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        CodeRow(label = "开码", candidate = on, onTest = { onTest(LearningSlot.TURN_ON) }, busy = busy)
        Spacer(Modifier.height(8.dp))
        CodeRow(label = "关码", candidate = off, onTest = { onTest(LearningSlot.TURN_OFF) }, busy = busy)

        testMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(64) },
            label = { Text("设备名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = location,
            onValueChange = { location = it.take(64) },
            label = { Text("位置") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onConfirm(name.trim(), location.trim()) },
            enabled = !busy && name.isNotBlank() && location.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text("保存设备")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel, enabled = !busy) {
            Text("放弃本次学习")
        }
    }
}

@Composable
private fun CodeRow(
    label: String,
    candidate: LearningCandidate?,
    onTest: () -> Unit,
    busy: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(50.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = candidate?.value?.toString() ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onTest, enabled = candidate != null && !busy) {
            Text("试发")
        }
    }
}

@Composable
private fun CompletedPanel(
    learning: LearningSession,
    deviceName: String,
    onBack: () -> Unit,
) {
    LearningCard(title = "已保存") {
        Text(
            text = "设备“$deviceName”已创建，初始状态为未知。后续控制将分别回放开码/关码。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "会话状态：${learningStateLabel(learning.state)} · revision ${learning.revision}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回设备列表")
        }
    }
}

@Composable
private fun TerminalPanel(
    learning: LearningSession,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    LearningCard(title = learningStateLabel(learning.state)) {
        Text(
            text = failureHelp(learning),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (learning.failureReason != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "failureReason = ${learning.failureReason}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (learning.state == LearningState.EXPIRED || learning.state == LearningState.FAILED) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("重新开始学习")
            }
            Spacer(Modifier.height(8.dp))
        }
        FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回设备列表")
        }
    }
}

@Composable
private fun LearningCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun StatusPill(text: String, highlighted: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        },
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LoadingDevices() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("正在读取设备…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyDevices(gatewayOnline: Boolean, onLearn: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "还没有已学习的设备",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (gatewayOnline) {
                    "设备只能通过“学习新设备”创建。学习会等待网关接收器上报捕获，不会向网关发送学习命令。"
                } else {
                    "请先启动 MQTT Broker 与 mock/，让网关 LWT 变为 online，然后点击刷新。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onLearn, enabled = gatewayOnline) {
                Text("学习新设备")
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "请求失败",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun FooterPanel(apiBaseUrl: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Material You：Android 12+ 使用系统动态取色；Web 使用内置品牌配色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "API $apiBaseUrl/api/v1",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "本客户端使用 1 秒/30 秒轮询刷新；状态以服务端快照的 revision 为准。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

private fun deviceStatusLabel(device: Device): String = when {
    !device.online -> "离线"
    device.state == DeviceState.ON && device.stateEvidence == StateEvidence.COMMANDED -> "已开启·已下发"
    device.state == DeviceState.OFF && device.stateEvidence == StateEvidence.COMMANDED -> "已停止·已下发"
    device.state == DeviceState.OFF -> "已停止"
    else -> "状态未知"
}

private fun lastActionLabel(device: Device): String = when (device.lastAction?.type) {
    com.esp32.control.contract.ActionType.TURNED_ON -> "最近开启"
    com.esp32.control.contract.ActionType.TURNED_OFF -> "最近停止"
    null -> "待机"
}

private fun commandStatusLabel(status: CommandStatus): String = when (status) {
    CommandStatus.DISPATCHED -> "已下发"
    CommandStatus.CANCELLED -> "已取消"
    CommandStatus.FAILED -> "下发失败"
}

private fun learningStateLabel(state: LearningState): String = when (state) {
    LearningState.COLLECTING_ON -> "请连续按遥控器开键 3 次"
    LearningState.CANDIDATE_ON_READY -> "已识别到开码候选，请确认"
    LearningState.COLLECTING_OFF -> "已确认开码，请连续按关键 3 次"
    LearningState.CANDIDATE_OFF_READY -> "已识别到关码候选，请确认"
    LearningState.READY -> "等待试发/命名/保存"
    LearningState.COMPLETED -> "学习完成"
    LearningState.CANCELLED -> "学习已取消"
    LearningState.EXPIRED -> "学习超时"
    LearningState.FAILED -> "学习失败"
}

private fun failureHelp(session: LearningSession): String = when (session.failureReason) {
    "no_signal" ->
        "采集窗口内没有收到任何合法 RF 捕获。请检查接收器电源、天线、315MHz 频率、距离和遥控器电池。unknown 不等于接收器故障。"
    "insufficient_hits" ->
        "收到了信号，但没有同一个码被独立按满 3 次。请连续按同一个键 3 次，每次间隔约 1 秒。"
    "candidate_timeout" -> "候选已达标，但 30 秒内没有确认。可以重新开始学习。"
    "ready_timeout" -> "进入保存阶段后 5 分钟内没有保存。"
    "session_timeout" -> "学习会话超过总时长限制。"
    "gateway_offline" -> "采集阶段网关离线。请恢复网关后重新开始。"
    "receiver_unavailable" -> "网关明确报告 315MHz 接收器不可用。"
    "internal_error" -> "服务端内部错误，请查看服务端日志。"
    else -> when (session.state) {
        LearningState.CANCELLED -> "本次学习已取消，未创建任何设备。"
        LearningState.COMPLETED -> "学习已完成。"
        else -> "学习没有完成。可以重新开始。"
    }
}

private fun gatewayStateLabel(state: GatewayState?): String = when (state) {
    GatewayState.ONLINE -> "online"
    GatewayState.OFFLINE -> "offline"
    GatewayState.UNKNOWN -> "unknown"
    null -> "未读取"
}

private fun gatewayLabel(state: GatewayState?): String = when (state) {
    GatewayState.ONLINE -> "online · 可控制/可学习"
    GatewayState.OFFLINE -> "offline · 拒绝控制与采集"
    GatewayState.UNKNOWN -> "unknown · 等待 LWT"
    null -> "—"
}

private fun receiverLabel(state: ReceiverState?): String = when (state) {
    ReceiverState.UNKNOWN -> "unknown · 尚未观察到 RF"
    ReceiverState.RECEIVING -> "receiving · 已收到捕获"
    ReceiverState.UNAVAILABLE -> "unavailable · 拒绝学习"
    null -> "—"
}

private fun formatUptime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun Throwable.readableMessage(): String {
    if (this is ClientApiException) {
        return "$code：$message" + requestId?.let { "（request $it）" }.orEmpty()
    }
    val raw = message?.takeIf { it.isNotBlank() } ?: toString()
    return raw.lineSequence().firstOrNull()?.take(240) ?: "未知错误"
}

private val TERMINAL_LEARNING_STATES = setOf(
    LearningState.COMPLETED,
    LearningState.CANCELLED,
    LearningState.EXPIRED,
    LearningState.FAILED,
)
