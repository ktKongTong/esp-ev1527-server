package dev.koft.ev1527.gui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.koft.ev1527.contract.CommandAction
import dev.koft.ev1527.contract.Device
import dev.koft.ev1527.contract.GatewayState
import dev.koft.ev1527.contract.HealthResponse
import dev.koft.ev1527.gui.AppUiState
import dev.koft.ev1527.gui.AppViewModel
import dev.koft.ev1527.gui.resources.Res
import dev.koft.ev1527.gui.resources.ic_groups
import dev.koft.ev1527.gui.resources.ic_schedule
import dev.koft.ev1527.gui.resources.ic_settings
import dev.koft.ev1527.gui.resources.ic_settings_remote
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun DeviceHeader(health: HealthResponse?) {
    val gateway = health?.gateway
    val gatewayTone = when (gateway?.state) {
        GatewayState.ONLINE -> StatusTone.Active
        GatewayState.OFFLINE -> StatusTone.Warning
        else -> StatusTone.Neutral
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "遥控器",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        StatusChip(
            text = gatewayChipLabel(gateway?.state),
            tone = gatewayTone,
        )
    }
}

@Composable
internal fun DeviceCard(
    device: Device,
    pending: Boolean,
    gatewayOnline: Boolean,
    onCommand: (CommandAction) -> Unit,
    onOpen: () -> Unit,
) {
    val enabled = gatewayOnline && !pending

    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            // Same filled pair as the detail sheet: 开 is primary, 关 is
            // tonal. A plain Row avoids ButtonGroup's press animation.
            Button(
                onClick = { onCommand(CommandAction.TURN_ON) },
                enabled = enabled,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp),
            ) {
                Text("开", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onCommand(CommandAction.TURN_OFF) },
                enabled = enabled,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp),
            ) {
                Text("关", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
internal fun EmptyDevices(gatewayOnline: Boolean, onLearn: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(64.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DeviceIcon(Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "还没有遥控器",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (gatewayOnline) {
                    "设备只能通过学习创建。服务端等待网关接收器上报捕获，不会向网关发送学习命令。"
                } else {
                    "网关当前不在线。控制与学习都要求网关 online，请先启动 MQTT Broker 与 mock/。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onLearn,
                enabled = gatewayOnline,
                shape = CircleShape,
                modifier = Modifier.height(48.dp),
            ) {
                Text("开始学习")
            }
        }
    }
}

@Composable
internal fun LoadingDevices(label: String = "正在同步遥控器…") {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal enum class BottomTab(
    val key: String,
    val label: String,
    val icon: DrawableResource,
    val addDescription: String,
) {
    Devices("devices", "设备", Res.drawable.ic_settings_remote, "学习新设备"),
    Groups("groups", "分组", Res.drawable.ic_groups, "创建分组"),
    Schedules("schedules", "定时", Res.drawable.ic_schedule, "创建定时"),
    Settings("settings", "设置", Res.drawable.ic_settings, "设置"),
}

/**
 * One bottom row: icon tabs on the left, add action on the right. The
 * toolbar sizes itself to its items, so the dock stays compact and the
 * remaining space pushes the FAB to the right edge.
 */
@Composable
internal fun BottomChrome(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    onAdd: () -> Unit,
    addDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        ) {
            BottomTab.entries.forEach { tab ->
                ToggleButton(
                    checked = selected == tab,
                    onCheckedChange = { onSelect(tab) },
                ) {
                    Icon(
                        painter = painterResource(tab.icon),
                        contentDescription = tab.label,
                        modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = selected != BottomTab.Settings,
            enter = fadeIn(tween(durationMillis = 150)) + scaleIn(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                initialScale = 0.8f,
            ),
            exit = fadeOut(tween(durationMillis = 120)) + scaleOut(
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                targetScale = 0.8f,
            ),
        ) {
            FloatingActionButton(
                onClick = onAdd,
                shape = AppShapes.Fab,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.semantics { contentDescription = addDescription },
            ) {
                AddIcon(Modifier.size(24.dp))
            }
        }
    }
}

internal fun gatewayChipLabel(state: GatewayState?): String = when (state) {
    GatewayState.ONLINE -> "网关在线"
    GatewayState.OFFLINE -> "网关离线"
    GatewayState.UNKNOWN -> "网关未知"
    null -> "未连接"
}

/**
 * Device-domain sheet and dialog state. Owned by the app shell because the
 * bottom bar's add action lives outside the pager; the device screen renders
 * the surfaces themselves.
 */
@Stable
internal class DeviceScreenState(
    detailDeviceId: String? = null,
    editingDeviceId: String? = null,
    removalDeviceId: String? = null,
) {
    var detailDeviceId by mutableStateOf(detailDeviceId)
        private set
    var editingDeviceId by mutableStateOf(editingDeviceId)
        private set
    var removalDeviceId by mutableStateOf(removalDeviceId)
        private set

    fun showDetail(deviceId: String) {
        detailDeviceId = deviceId
    }

    fun showEditor(deviceId: String) {
        editingDeviceId = deviceId
    }

    fun requestRemoval(deviceId: String) {
        removalDeviceId = deviceId
    }

    fun dismissDetail() {
        detailDeviceId = null
    }

    fun dismissEditor() {
        editingDeviceId = null
    }

    fun dismissRemoval() {
        removalDeviceId = null
    }

    fun closeAll() {
        detailDeviceId = null
        editingDeviceId = null
        removalDeviceId = null
    }
}

private val DeviceScreenStateSaver: Saver<DeviceScreenState, Any> = listSaver(
    save = {
        listOf(
            it.detailDeviceId.orEmpty(),
            it.editingDeviceId.orEmpty(),
            it.removalDeviceId.orEmpty(),
        )
    },
    restore = {
        DeviceScreenState(
            detailDeviceId = it.getOrNull(0).orNullIfEmpty(),
            editingDeviceId = it.getOrNull(1).orNullIfEmpty(),
            removalDeviceId = it.getOrNull(2).orNullIfEmpty(),
        )
    },
)

@Composable
internal fun rememberDeviceScreenState(): DeviceScreenState =
    rememberSaveable(saver = DeviceScreenStateSaver) { DeviceScreenState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    screen: DeviceScreenState,
    devices: List<Device>,
    columns: Int,
    horizontalPadding: Dp,
) {
    PullToRefreshBox(
        isRefreshing = state.refresh.running,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .widthIn(max = 1120.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DeviceHeader(health = state.health)
            }

            when {
                devices.isEmpty() && state.loading ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadingDevices()
                    }

                !state.refresh.hasSnapshot && state.refresh.error != null ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CacheErrorState(
                            message = state.refresh.error.orEmpty(),
                            onRetry = viewModel::refresh,
                        )
                    }

                devices.isEmpty() ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyDevices(
                            gatewayOnline = state.gatewayOnline,
                            onLearn = viewModel::openLearning,
                        )
                    }

                else -> items(devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        pending = state.pendingDeviceId == device.id,
                        gatewayOnline = state.gatewayOnline,
                        onCommand = { action ->
                            viewModel.sendCommand(device.id, action)
                        },
                        onOpen = { screen.showDetail(device.id) },
                    )
                }
            }
        }
    }

    screen.detailDeviceId?.let { id -> devices.firstOrNull { it.id == id } }?.let { device ->
        DeviceDetailSheet(
            device = device,
            pending = state.pendingDeviceId == device.id,
            gatewayOnline = state.gatewayOnline,
            onCommand = { action -> viewModel.sendCommand(device.id, action) },
            onEdit = { screen.showEditor(device.id) },
            onRemove = { screen.requestRemoval(device.id) },
            onClose = screen::dismissDetail,
        )
    }

    screen.editingDeviceId?.let { id -> devices.firstOrNull { it.id == id } }?.let { device ->
        DeviceEditDialog(
            device = device,
            onSave = { name, location -> viewModel.saveDevice(device.id, name, location) },
            onDismiss = screen::dismissEditor,
        )
    }

    if (state.learning.visible) {
        LearningSheet(
            learning = state.learning.session,
            busy = state.learning.busy,
            gatewayOnline = state.gatewayOnline,
            // The sheet reports learning failures only; a stale command
            // or refresh error is unrelated to this flow.
            errorText = state.learning.error,
            testMessage = state.learning.testMessage,
            completedDeviceName = state.learning.completedDeviceName,
            deviceNameForId = { id -> devices.firstOrNull { it.id == id }?.name },
            onStart = viewModel::startLearning,
            onAccept = viewModel::acceptCandidate,
            onRescan = viewModel::rescan,
            onTest = viewModel::test,
            onConfirm = viewModel::confirmLearning,
            onClose = viewModel::closeLearning,
        )
    }

    screen.removalDeviceId?.let { id -> devices.firstOrNull { it.id == id } }?.let { target ->
        AlertDialog(
            onDismissRequest = screen::dismissRemoval,
            shape = AppShapes.Dialog,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { DeleteDialogIcon() },
            title = { Text("移除遥控器？") },
            text = {
                Text(
                    "将从服务端删除“${target.name}”，并释放开码/关码。" +
                        "此操作不会向网关发送任何 RF 指令，也无法撤销已下发的物理动作。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        screen.dismissRemoval()
                        viewModel.removeDevice(target.id)
                    },
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
                TextButton(onClick = screen::dismissRemoval) {
                    Text("取消")
                }
            },
        )
    }
}

private fun String?.orNullIfEmpty(): String? = this?.ifEmpty { null }
