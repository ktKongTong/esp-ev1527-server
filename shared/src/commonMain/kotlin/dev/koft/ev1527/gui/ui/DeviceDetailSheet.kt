package dev.koft.ev1527.gui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.koft.ev1527.contract.CommandAction
import dev.koft.ev1527.contract.Device
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceDetailSheet(
    device: Device,
    pending: Boolean,
    gatewayOnline: Boolean,
    onCommand: (CommandAction) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val enabled = gatewayOnline && !pending

    // Buttons must wait for the hide animation before the caller removes the
    // sheet from composition; gesture dismissal arrives through onDismissRequest.
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onClose()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        sheetMaxWidth = 720.dp,
        shape = AppShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = device.location,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onEdit, enabled = !pending) {
                    Text("编辑")
                }
                FilledTonalIconButton(
                    onClick = ::dismiss,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(44.dp)
                        .semantics { contentDescription = "关闭" },
                ) {
                    CloseIcon(Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionSurface {
                CodeRow(label = "开码", code = device.codeOn)
                CodeRow(label = "关码", code = device.codeOff)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "协议 ${device.rf.protocol} · ${device.rf.length} bit · repeat ${device.rf.repeat}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onCommand(CommandAction.TURN_ON) },
                    enabled = enabled,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text("开", style = MaterialTheme.typography.titleSmall)
                }
                FilledTonalButton(
                    onClick = { onCommand(CommandAction.TURN_OFF) },
                    enabled = enabled,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text("关", style = MaterialTheme.typography.titleSmall)
                }
            }

            val statusText = when {
                pending -> "正在发送…"
                !gatewayOnline -> "网关离线，无法发送"
                else -> null
            }
            if (statusText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onRemove,
                enabled = !pending,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                DeleteIcon(Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("移除遥控器")
            }
        }
    }
}

@Composable
internal fun DeviceEditDialog(
    device: Device,
    onSave: (String, String) -> Deferred<String?>,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(device.id) { mutableStateOf(device.name) }
    var location by rememberSaveable(device.id) { mutableStateOf(device.location) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        busy = true
        errorText = null
        scope.launch {
            val message = try {
                onSave(name.trim(), location.trim()).await()
            } finally {
                busy = false
            }
            if (message == null) {
                onDismiss()
            } else {
                errorText = message
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = AppShapes.Dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("编辑遥控器") },
        text = {
            Column {
                Text(
                    text = "开码与关码保持不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = AppShapes.Field,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it.take(64) },
                    label = { Text("位置") },
                    singleLine = true,
                    shape = AppShapes.Field,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorText?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = !busy && name.isNotBlank() && location.isNotBlank(),
                shape = CircleShape,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun CodeRow(label: String, code: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = code.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
