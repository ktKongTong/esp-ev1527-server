package com.esp32.control.gui.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.Device
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.HealthResponse

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

internal enum class BottomTab { Devices, Groups, Schedules, Settings }

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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            TabButton(
                checked = selected == BottomTab.Devices,
                description = "设备",
                onClick = { onSelect(BottomTab.Devices) },
            ) { DeviceIcon(Modifier.size(ToggleButtonDefaults.IconSize)) }
            TabButton(
                checked = selected == BottomTab.Groups,
                description = "分组",
                onClick = { onSelect(BottomTab.Groups) },
            ) { GroupsIcon(Modifier.size(ToggleButtonDefaults.IconSize)) }
            TabButton(
                checked = selected == BottomTab.Schedules,
                description = "定时",
                onClick = { onSelect(BottomTab.Schedules) },
            ) { ScheduleIcon(Modifier.size(ToggleButtonDefaults.IconSize)) }
            TabButton(
                checked = selected == BottomTab.Settings,
                description = "设置",
                onClick = { onSelect(BottomTab.Settings) },
            ) { SettingsIcon(Modifier.size(ToggleButtonDefaults.IconSize)) }
        }

        // The settings tab has no primary action. The other tabs add the
        // item they list.
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

@Composable
private fun TabButton(
    checked: Boolean,
    description: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        icon = icon,
        modifier = Modifier.semantics { contentDescription = description },
    ) {}
}

internal fun gatewayChipLabel(state: GatewayState?): String = when (state) {
    GatewayState.ONLINE -> "网关在线"
    GatewayState.OFFLINE -> "网关离线"
    GatewayState.UNKNOWN -> "网关未知"
    null -> "未连接"
}
