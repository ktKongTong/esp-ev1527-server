package com.esp32.control.gui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.Device
import com.esp32.control.contract.Group
import com.esp32.control.contract.HealthResponse

@Composable
internal fun GroupHeader(health: HealthResponse?) {
    val gateway = health?.gateway
    val gatewayTone = when (gateway?.state) {
        com.esp32.control.contract.GatewayState.ONLINE -> StatusTone.Active
        com.esp32.control.contract.GatewayState.OFFLINE -> StatusTone.Warning
        else -> StatusTone.Neutral
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "分组",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = gatewayChipLabel(gateway?.state), tone = gatewayTone)
    }
}

@Composable
internal fun GroupCard(
    group: Group,
    pending: Boolean,
    gatewayOnline: Boolean,
    onCommand: (CommandAction) -> Unit,
    onOpen: () -> Unit,
) {
    val enabled = gatewayOnline && !pending && group.deviceIds.isNotEmpty()

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${group.deviceIds.size} 台设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = { onCommand(CommandAction.TURN_ON) },
                enabled = enabled,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp),
            ) {
                Text("全开", style = MaterialTheme.typography.titleSmall)
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
                Text("全关", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
internal fun EmptyGroups(onCreate: () -> Unit) {
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
                    GroupsIcon(Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(text = "还没有分组", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "一个设备可以同时属于多个分组。向分组下发指令时，服务端会为每台设备单独发布 RF 帧。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onCreate,
                shape = CircleShape,
                modifier = Modifier.height(48.dp),
            ) {
                Text("创建分组")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditorSheet(
    group: Group?,
    devices: List<Device>,
    busy: Boolean,
    errorText: String?,
    onSave: (String, List<String>) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var selected by remember(group?.id) {
        mutableStateOf(group?.deviceIds?.toSet() ?: emptySet())
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val canSave = name.isNotBlank() && !busy

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        shape = AppShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (group == null) "创建分组" else "编辑分组",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "关闭" },
                ) {
                    CloseIcon(Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                shape = AppShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "设备",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            if (devices.isEmpty()) {
                Text(
                    text = "还没有设备，请先在“设备”页学习一个遥控器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (device.id in selected) {
                                    selected - device.id
                                } else {
                                    selected + device.id
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = device.id in selected,
                            onCheckedChange = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = device.location,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            errorText?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(name.trim(), selected.toList()) },
                enabled = canSave,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (busy) "保存中…" else "保存")
            }

            if (onRemove != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onRemove,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DeleteIcon(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除分组")
                }
            }
        }
    }
}
