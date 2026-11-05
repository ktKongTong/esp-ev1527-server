package com.esp32.control.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.esp32.control.gui.settings.rememberSettingsStore
import com.esp32.control.gui.theme.Esp32Theme
import com.esp32.control.gui.ui.AppShapes
import com.esp32.control.gui.ui.BottomChrome
import com.esp32.control.gui.ui.BottomTab
import com.esp32.control.gui.ui.DeleteIcon
import com.esp32.control.gui.ui.DeviceCard
import com.esp32.control.gui.ui.DeviceDetailSheet
import com.esp32.control.gui.ui.DeviceHeader
import com.esp32.control.gui.ui.EmptyDevices
import com.esp32.control.gui.ui.ErrorBanner
import com.esp32.control.gui.ui.LearningSheet
import com.esp32.control.gui.ui.LoadingDevices
import com.esp32.control.gui.ui.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun App() {
    Esp32Theme {
        val settingsStore = rememberSettingsStore()
        val scope = rememberCoroutineScope()
        var serverUrl by remember { mutableStateOf<String?>(null) }
        var selectedTab by remember { mutableStateOf(BottomTab.Devices) }

        LaunchedEffect(settingsStore) {
            serverUrl = settingsStore.serverUrl.first()
        }

        val currentUrl = serverUrl
        if (currentUrl == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Esp32Theme
        }

        key(currentUrl) {
            val api = remember(currentUrl) { Esp32Api(currentUrl) }
            AppContent(
                api = api,
                serverUrl = currentUrl,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onServerUrlChange = { value ->
                    serverUrl = value
                    scope.launch { settingsStore.setServerUrl(value) }
                },
            )
        }
    }
}

@Composable
private fun AppContent(
    api: Esp32Api,
    serverUrl: String,
    selectedTab: BottomTab,
    onSelectTab: (BottomTab) -> Unit,
    onServerUrlChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Serializes learning session create/cancel so a close followed by an
    // immediate reopen cannot hit the server's single-active-session limit.
    val sessionMutex = remember { Mutex() }

    var health by remember { mutableStateOf<HealthResponse?>(null) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingDeviceId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var removalTarget by remember { mutableStateOf<Device?>(null) }
    var detailDevice by remember { mutableStateOf<Device?>(null) }

    var showLearning by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf<LearningSession?>(null) }
    var learningId by remember { mutableStateOf<String?>(null) }
    var learningBusy by remember { mutableStateOf(false) }
    var learningError by remember { mutableStateOf<String?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var completedDeviceName by remember { mutableStateOf<String?>(null) }

    val gatewayOnline = health?.gateway?.state == GatewayState.ONLINE

    suspend fun loadAll() {
        loading = true
        try {
            health = api.health()
            val response = api.deviceList()
            devices = response.devices
            detailDevice = detailDevice?.let { current ->
                response.devices.firstOrNull { it.id == current.id }
            }
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
                detailDevice = detailDevice?.let { current ->
                    devices.firstOrNull { it.id == current.id }
                }
                health = api.health()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error = t.readableMessage()
            }
        }
    }

    fun sendCommand(device: Device, action: CommandAction) {
        if (pendingDeviceId != null) return
        pendingDeviceId = device.id
        scope.launch {
            try {
                api.sendCommand(
                    deviceId = device.id,
                    action = action,
                    idempotencyKey = newIdempotencyKey(),
                )
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
                if (detailDevice?.id == device.id) detailDevice = null
                error = null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ClientApiException && (t.status == 404 || t.status == 410)) {
                    devices = devices.filterNot { it.id == device.id }
                    if (detailDevice?.id == device.id) detailDevice = null
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
                sessionMutex.withLock {
                    val session = api.startLearning()
                    if (showLearning) {
                        learning = session
                        learningId = session.id
                        testMessage = null
                        completedDeviceName = null
                    } else {
                        // Sheet dismissed while the request was in flight.
                        runCatching { api.cancelLearning(session.id) }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (showLearning) learningError = t.readableMessage()
            } finally {
                learningBusy = false
            }
        }
    }

    fun openLearning() {
        showLearning = true
        learningError = null
        testMessage = null
        completedDeviceName = null
        if (gatewayOnline) {
            startLearning()
        } else {
            // Refresh health now instead of waiting for the 30s poll; the
            // auto-start effect fires as soon as the gateway is online.
            scope.launch { runCatching { health = api.health() } }
        }
    }

    fun closeLearning() {
        val session = learning
        showLearning = false
        learningId = null
        learning = null
        learningError = null
        testMessage = null
        completedDeviceName = null
        if (session != null && session.state !in TERMINAL_LEARNING_STATES) {
            scope.launch {
                sessionMutex.withLock {
                    runCatching { api.cancelLearning(session.id) }
                }
            }
        }
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

    // The sheet owns the session lifecycle: opening it starts a session
    // as soon as the gateway is online, and closing it cancels the session.
    LaunchedEffect(showLearning, gatewayOnline) {
        if (showLearning && gatewayOnline && learning == null &&
            learningId == null && !learningBusy
        ) {
            startLearning()
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
                if (t is ClientApiException && t.status == 404) {
                    // Sessions live in server memory and die with the process.
                    learningId = null
                    learning = null
                    showLearning = false
                    learningError = null
                    testMessage = null
                    completedDeviceName = null
                    error = "学习会话已失效，请重新开始"
                    break
                }
                // The session lives on the server, so a failed poll (for
                // example a dropped keep-alive connection) is transient:
                // keep polling and let the next tick resync.
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { api.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth >= 1040.dp -> 3
                maxWidth >= 660.dp -> 2
                else -> 1
            }
            val horizontalPadding = if (maxWidth >= 1040.dp) 32.dp else 20.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (selectedTab == BottomTab.Settings) {
                    SettingsScreen(
                        serverUrl = serverUrl,
                        onSave = onServerUrlChange,
                        modifier = Modifier.widthIn(max = 720.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier
                            .widthIn(max = 1120.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            top = 8.dp,
                            bottom = 140.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            DeviceHeader(health = health)
                        }

                        error?.let { message ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                ErrorBanner(message = message, onDismiss = { error = null })
                            }
                        }

                        when {
                            devices.isEmpty() && loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                                LoadingDevices()
                            }

                            devices.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                                EmptyDevices(
                                    gatewayOnline = gatewayOnline,
                                    onLearn = ::openLearning,
                                )
                            }

                            else -> items(devices, key = { it.id }) { device ->
                                DeviceCard(
                                    device = device,
                                    pending = pendingDeviceId == device.id,
                                    gatewayOnline = gatewayOnline,
                                    onCommand = { action -> sendCommand(device, action) },
                                    onOpen = { detailDevice = device },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!showLearning) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 1120.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                BottomChrome(
                    selected = selectedTab,
                    onSelect = onSelectTab,
                    onLearn = ::openLearning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    detailDevice?.let { device ->
        DeviceDetailSheet(
            device = device,
            pending = pendingDeviceId == device.id,
            gatewayOnline = gatewayOnline,
            errorText = error,
            onCommand = { action -> sendCommand(device, action) },
            onRemove = { removalTarget = device },
            onDismissError = { error = null },
            onClose = { detailDevice = null },
        )
    }

    if (showLearning) {
        LearningSheet(
            learning = learning,
            busy = learningBusy,
            gatewayOnline = gatewayOnline,
            // The sheet reports learning failures only; a stale command
            // or refresh error is unrelated to this flow.
            errorText = learningError,
            testMessage = testMessage,
            completedDeviceName = completedDeviceName,
            deviceNameForId = { id -> devices.firstOrNull { it.id == id }?.name },
            onStart = ::startLearning,
            onAccept = ::acceptCandidate,
            onRescan = ::rescan,
            onTest = ::test,
            onConfirm = ::confirmLearning,
            onDismissError = {
                learningError = null
                error = null
            },
            onClose = ::closeLearning,
        )
    }

    removalTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removalTarget = null },
            shape = AppShapes.Dialog,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DeleteIcon(Modifier.size(22.dp))
                    }
                }
            },
            title = { Text("移除遥控器？") },
            text = {
                Text(
                    "将从服务端删除“${target.name}”，并释放开码/关码。" +
                        "此操作不会向网关发送任何 RF 指令，也无法撤销已下发的物理动作。",
                )
            },
            confirmButton = {
                Button(
                    onClick = { removeDevice(target) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
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
