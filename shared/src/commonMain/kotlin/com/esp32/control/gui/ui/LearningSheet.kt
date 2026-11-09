package com.esp32.control.gui.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.esp32.control.contract.LearningCandidate
import com.esp32.control.contract.LearningSession
import com.esp32.control.contract.LearningSlot
import com.esp32.control.contract.LearningState
import kotlinx.coroutines.launch

/**
 * Opening this sheet creates a learning session; dismissing it cancels the
 * session. There is no separate "start" step: the only precondition is an
 * online gateway, which the caller checks before showing the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LearningSheet(
    learning: LearningSession?,
    busy: Boolean,
    gatewayOnline: Boolean,
    errorText: String?,
    testMessage: String?,
    completedDeviceName: String?,
    deviceNameForId: (String) -> String?,
    onStart: () -> Unit,
    onAccept: (LearningCandidate) -> Unit,
    onRescan: () -> Unit,
    onTest: (LearningSlot) -> Unit,
    onConfirm: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()

    // Buttons must wait for the hide animation before the caller removes the
    // sheet from composition; gesture dismissal arrives through onDismissRequest.
    fun close() {
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
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "学习新设备",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = ::close,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(44.dp)
                        .semantics { contentDescription = "关闭学习" },
                ) {
                    CloseIcon(Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            errorText?.let {
                ErrorText(it)
                Spacer(Modifier.height(16.dp))
            }

            if (learning == null) {
                StartingPanel(
                    busy = busy,
                    gatewayOnline = gatewayOnline,
                    onRetry = onStart,
                )
                return@Column
            }

            when (learning.state) {
                LearningState.COLLECTING_ON, LearningState.COLLECTING_OFF ->
                    CollectingPanel(learning = learning, busy = busy, onRescan = onRescan)

                LearningState.CANDIDATE_ON_READY, LearningState.CANDIDATE_OFF_READY ->
                    CandidatePanel(
                        learning = learning,
                        busy = busy,
                        deviceNameForId = deviceNameForId,
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
                    )

                LearningState.COMPLETED ->
                    CompletedPanel(
                        deviceName = completedDeviceName ?: "新设备",
                        onClose = ::close,
                    )

                LearningState.EXPIRED, LearningState.FAILED, LearningState.CANCELLED ->
                    TerminalPanel(
                        learning = learning,
                        onRetry = onStart,
                        onClose = ::close,
                    )
            }
        }
    }
}

@Composable
private fun StartingPanel(busy: Boolean, gatewayOnline: Boolean, onRetry: () -> Unit) {
    SectionSurface {
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text("正在开始学习…", style = MaterialTheme.typography.bodyMedium)
            }

            !gatewayOnline -> Column {
                Text("等待网关在线", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "网关恢复在线后会自动开始采集。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column {
                Text("未能开始学习", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun CollectingPanel(
    learning: LearningSession,
    busy: Boolean,
    onRescan: () -> Unit,
) {
    val slot = if (learning.state == LearningState.COLLECTING_ON) {
        LearningSlot.TURN_ON
    } else {
        LearningSlot.TURN_OFF
    }
    val hits = learning.candidates
        .filter { it.slot == slot }
        .maxOfOrNull { it.hitCount } ?: 0
    val title = if (slot == LearningSlot.TURN_ON) {
        "请连续按遥控器开键 3 次"
    } else {
        "请连续按遥控器关键 3 次"
    }
    val step = if (slot == LearningSlot.TURN_ON) {
        "第 1 步 / 共 2 步 · 学习开键"
    } else {
        "第 2 步 / 共 2 步 · 学习关键"
    }

    SectionSurface {
        Text(
            text = step,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "$hits / ${learning.requiredHits} 次",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = {
                (hits.toFloat() / learning.requiredHits.coerceAtLeast(1)).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "同一个键连续按，每次间隔约 1 秒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onRescan,
            enabled = !busy,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("重新采集")
        }
    }
}

@Composable
private fun CandidatePanel(
    learning: LearningSession,
    busy: Boolean,
    deviceNameForId: (String) -> String?,
    onAccept: (LearningCandidate) -> Unit,
    onRescan: () -> Unit,
) {
    val slot = if (learning.state == LearningState.CANDIDATE_ON_READY) {
        LearningSlot.TURN_ON
    } else {
        LearningSlot.TURN_OFF
    }
    val candidates = learning.candidates.filter { it.slot == slot }
    val step = if (slot == LearningSlot.TURN_ON) {
        "第 1 步 / 共 2 步 · 学习开键"
    } else {
        "第 2 步 / 共 2 步 · 学习关键"
    }

    SectionSurface {
        Text(
            text = step,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (slot == LearningSlot.TURN_ON) {
                "选择开码候选"
            } else {
                "选择关码候选"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        candidates.sortedWith(
            compareByDescending<LearningCandidate> { it.qualified }.thenByDescending { it.hitCount },
        ).forEach { candidate ->
            CandidateOption(
                candidate = candidate,
                requiredHits = learning.requiredHits,
                slot = slot,
                busy = busy,
                conflictDeviceName = candidate.conflictDeviceId?.let(deviceNameForId),
                onAccept = { onAccept(candidate) },
            )
            Spacer(Modifier.height(10.dp))
        }
        if (candidates.none { it.qualified }) {
            Text(
                text = "没有达标的候选，请重新采集。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))
        }
        FilledTonalButton(
            onClick = onRescan,
            enabled = !busy,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("重新采集")
        }
    }
}

@Composable
private fun CandidateOption(
    candidate: LearningCandidate,
    requiredHits: Int,
    slot: LearningSlot,
    busy: Boolean,
    conflictDeviceName: String?,
    onAccept: () -> Unit,
) {
    val conflict = candidate.conflictDeviceId != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Inset,
        color = if (candidate.qualified) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (candidate.qualified) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "码值 ${candidate.value}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                StatusChip(
                    text = if (candidate.qualified) "已达标" else "未达标",
                    tone = if (candidate.qualified) StatusTone.Active else StatusTone.Neutral,
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
                    text = if (conflictDeviceName != null) {
                        "该码已被「$conflictDeviceName」使用，不能接受。"
                    } else {
                        "该码已被其他遥控器使用，不能接受。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAccept,
                enabled = candidate.qualified && !candidate.accepted && !conflict && !busy,
                shape = CircleShape,
                modifier = Modifier.height(44.dp),
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
) {
    val on = learning.candidates.firstOrNull { it.id == learning.acceptedOnCandidateId }
    val off = learning.candidates.firstOrNull { it.id == learning.acceptedOffCandidateId }
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }

    SectionSurface {
        Text(
            text = "命名并保存",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "开码与关码已确认，可以编辑设备信息。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        CodeRow(
            label = "开码",
            candidate = on,
            busy = busy,
            onTest = { onTest(LearningSlot.TURN_ON) },
        )
        Spacer(Modifier.height(8.dp))
        CodeRow(
            label = "关码",
            candidate = off,
            busy = busy,
            onTest = { onTest(LearningSlot.TURN_OFF) },
        )

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
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onConfirm(name.trim(), location.trim()) },
            enabled = !busy && name.isNotBlank() && location.isNotBlank(),
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
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
    }
}

@Composable
private fun CodeRow(
    label: String,
    candidate: LearningCandidate?,
    busy: Boolean,
    onTest: () -> Unit,
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
        FilledTonalButton(
            onClick = onTest,
            enabled = candidate != null && !busy,
            shape = CircleShape,
            modifier = Modifier.height(44.dp),
        ) {
            Text("试发")
        }
    }
}

@Composable
private fun CompletedPanel(deviceName: String, onClose: () -> Unit) {
    SectionSurface {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(56.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CheckCircleIcon(Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(text = "已保存", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "设备“$deviceName”已创建。后续控制将分别回放开码/关码。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onClose,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("完成")
        }
    }
}

@Composable
private fun TerminalPanel(
    learning: LearningSession,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    SectionSurface {
        Text(
            text = learningStateLabel(learning.state),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = failureHelp(learning),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        if (learning.state == LearningState.EXPIRED || learning.state == LearningState.FAILED) {
            Button(
                onClick = onRetry,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("重新开始学习")
            }
            Spacer(Modifier.height(8.dp))
        }
        FilledTonalButton(
            onClick = onClose,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("关闭")
        }
    }
}

internal fun learningStateLabel(state: LearningState): String = when (state) {
    LearningState.COLLECTING_ON -> "请连续按遥控器开键 3 次"
    LearningState.CANDIDATE_ON_READY -> "选择开码候选"
    LearningState.COLLECTING_OFF -> "请连续按遥控器关键 3 次"
    LearningState.CANDIDATE_OFF_READY -> "选择关码候选"
    LearningState.READY -> "命名并保存"
    LearningState.COMPLETED -> "学习完成"
    LearningState.CANCELLED -> "学习已取消"
    LearningState.EXPIRED -> "学习超时"
    LearningState.FAILED -> "学习失败"
}

internal fun failureHelp(session: LearningSession): String = when (session.failureReason) {
    "no_signal" -> "没有收到 RF 信号。请检查接收器天线、距离和遥控器电池。"
    "insufficient_hits" -> "没有同一个码被按满 3 次。请连续按同一个键 3 次。"
    "candidate_timeout" -> "候选已达标，但没有确认。可以重新开始学习。"
    "ready_timeout" -> "保存阶段超时。"
    "session_timeout" -> "学习会话超时。"
    "gateway_offline" -> "采集阶段网关离线。请恢复网关后重试。"
    "receiver_unavailable" -> "网关报告 315MHz 接收器不可用。"
    "internal_error" -> "服务端内部错误。"
    else -> when (session.state) {
        LearningState.CANCELLED -> "本次学习已取消，未创建任何设备。"
        LearningState.COMPLETED -> "学习已完成。"
        else -> "学习没有完成，可以重新开始。"
    }
}
