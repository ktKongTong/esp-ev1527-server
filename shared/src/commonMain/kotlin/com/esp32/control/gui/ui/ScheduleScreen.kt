package com.esp32.control.gui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.Device
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.Group
import com.esp32.control.contract.HealthResponse
import com.esp32.control.contract.Schedule
import com.esp32.control.contract.ScheduleResult
import com.esp32.control.contract.ScheduleTarget
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val scheduleDisplayFormat = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    day()
    char(' ')
    hour()
    char(':')
    minute()
}

// Accept unpadded fields so that "2026-1-5 9:05" stays valid input.
private val scheduleInputFormat = LocalDateTime.Format {
    year(Padding.NONE)
    char('-')
    monthNumber(Padding.NONE)
    char('-')
    day(Padding.NONE)
    char(' ')
    hour(Padding.NONE)
    char(':')
    minute(Padding.NONE)
}

private fun formatScheduleTime(epochMs: Long): String =
    Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .format(scheduleDisplayFormat)

private fun parseScheduleTime(text: String): Long? = runCatching {
    LocalDateTime.parse(text.trim(), scheduleInputFormat)
        .toInstant(TimeZone.currentSystemDefault())
        .toEpochMilliseconds()
}.getOrNull()

@Composable
internal fun ScheduleHeader(health: HealthResponse?) {
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
            text = "定时",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = gatewayChipLabel(gateway?.state), tone = gatewayTone)
    }
}

@Composable
internal fun ScheduleCard(
    schedule: Schedule,
    devices: List<Device>,
    groups: List<Group>,
    pending: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 14.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = schedule.name.ifBlank { "未命名定时" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${targetLabel(schedule, devices, groups)} · ${actionLabel(schedule.action)}",
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
                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = onToggle,
                    enabled = !pending,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = schedule.rrule,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = schedule.nextRunAtEpochMs?.let { "下次 ${formatScheduleTime(it)}" }
                    ?: "没有后续触发",
                style = MaterialTheme.typography.bodySmall,
                color = if (schedule.nextRunAtEpochMs == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            schedule.lastRunAtEpochMs?.let { lastRun ->
                Text(
                    text = buildString {
                        append("上次 ")
                        append(formatScheduleTime(lastRun))
                        schedule.lastResult?.let { result ->
                            append(" · ")
                            append(resultLabel(result))
                        }
                        schedule.lastError?.let { error ->
                            append(" · ")
                            append(error)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (schedule.lastError == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) { Text("编辑") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    DeleteIcon(Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
internal fun EmptySchedules(onCreate: () -> Unit) {
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
                    ScheduleIcon(Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(text = "还没有定时任务", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "定时像闹钟：一次、每天、每周只是 RRULE 的常用写法，也可以直接填写任意 RFC 5545 规则。",
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
                Text("创建定时")
            }
        }
    }
}

private enum class RepeatPreset { Once, Daily, Weekly, Custom }

private fun presetOf(rrule: String): RepeatPreset = when (rrule.trim().uppercase()) {
    "FREQ=DAILY;COUNT=1" -> RepeatPreset.Once
    "FREQ=DAILY" -> RepeatPreset.Daily
    "FREQ=WEEKLY" -> RepeatPreset.Weekly
    else -> RepeatPreset.Custom
}

@Composable
internal fun ScheduleEditorDialog(
    schedule: Schedule?,
    devices: List<Device>,
    groups: List<Group>,
    busy: Boolean,
    errorText: String?,
    onSave: (
        name: String,
        targetType: ScheduleTarget,
        targetId: String,
        action: CommandAction,
        rrule: String,
        timeZone: String,
        startsAtEpochMs: Long,
        enabled: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialTargetType = schedule?.targetType
        ?: if (devices.isNotEmpty() || groups.isEmpty()) ScheduleTarget.DEVICE else ScheduleTarget.GROUP
    val initialTargetId = schedule?.targetId
        ?: if (initialTargetType == ScheduleTarget.DEVICE) {
            devices.firstOrNull()?.id.orEmpty()
        } else {
            groups.firstOrNull()?.id.orEmpty()
        }
    val initialRule = schedule?.rrule.orEmpty()
    val initialPreset = if (schedule == null) RepeatPreset.Once else presetOf(initialRule)

    var name by remember(schedule?.id) { mutableStateOf(schedule?.name.orEmpty()) }
    var targetType by remember(schedule?.id) { mutableStateOf(initialTargetType) }
    var targetId by remember(schedule?.id) { mutableStateOf(initialTargetId) }
    var action by remember(schedule?.id) {
        mutableStateOf(schedule?.action ?: CommandAction.TURN_ON)
    }
    var preset by remember(schedule?.id) { mutableStateOf(initialPreset) }
    var customRule by remember(schedule?.id) {
        mutableStateOf(if (initialPreset == RepeatPreset.Custom) initialRule else "")
    }
    var startText by remember(schedule?.id) {
        mutableStateOf(
            schedule?.let { formatScheduleTime(it.startsAtEpochMs) }
                ?: (Clock.System.now() + 5.minutes)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .format(scheduleDisplayFormat),
        )
    }
    var enabled by remember(schedule?.id) { mutableStateOf(schedule?.enabled ?: true) }
    var localError by remember(schedule?.id) { mutableStateOf<String?>(null) }
    val timeZone = remember { TimeZone.currentSystemDefault() }

    val candidates = if (targetType == ScheduleTarget.DEVICE) {
        devices.map { it.id to (it.name to it.location) }
    } else {
        groups.map { it.id to (it.name to "${it.deviceIds.size} 台设备") }
    }
    val canSave = !busy && targetId.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = AppShapes.Dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(if (schedule == null) "创建定时" else "编辑定时") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                    shape = AppShapes.Field,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("目标", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = targetType == ScheduleTarget.DEVICE,
                        onClick = {
                            targetType = ScheduleTarget.DEVICE
                            targetId = devices.firstOrNull()?.id.orEmpty()
                        },
                        label = { Text("设备") },
                    )
                    FilterChip(
                        selected = targetType == ScheduleTarget.GROUP,
                        onClick = {
                            targetType = ScheduleTarget.GROUP
                            targetId = groups.firstOrNull()?.id.orEmpty()
                        },
                        label = { Text("分组") },
                    )
                }
                if (candidates.isEmpty()) {
                    Text(
                        text = if (targetType == ScheduleTarget.DEVICE) {
                            "还没有设备"
                        } else {
                            "还没有分组"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // The dialog body already scrolls; an inner bounded list
                    // would clip entries that never become reachable.
                    Column {
                        candidates.forEach { (id, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { targetId = id }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = targetId == id,
                                    onClick = { targetId = id },
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(label.first, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = label.second,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("动作", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = action == CommandAction.TURN_ON,
                        onClick = { action = CommandAction.TURN_ON },
                        label = { Text("开") },
                    )
                    FilterChip(
                        selected = action == CommandAction.TURN_OFF,
                        onClick = { action = CommandAction.TURN_OFF },
                        label = { Text("关") },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("重复", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = preset == RepeatPreset.Once,
                        onClick = { preset = RepeatPreset.Once },
                        label = { Text("一次") },
                    )
                    FilterChip(
                        selected = preset == RepeatPreset.Daily,
                        onClick = { preset = RepeatPreset.Daily },
                        label = { Text("每天") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = preset == RepeatPreset.Weekly,
                        onClick = { preset = RepeatPreset.Weekly },
                        label = { Text("每周") },
                    )
                    FilterChip(
                        selected = preset == RepeatPreset.Custom,
                        onClick = { preset = RepeatPreset.Custom },
                        label = { Text("自定义") },
                    )
                }
                if (preset == RepeatPreset.Custom) {
                    OutlinedTextField(
                        value = customRule,
                        onValueChange = { customRule = it },
                        label = { Text("RRULE") },
                        placeholder = { Text("FREQ=MONTHLY;BYMONTHDAY=1") },
                        singleLine = true,
                        shape = AppShapes.Field,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("开始时间（本机）") },
                    placeholder = { Text("2026-11-09 08:00") },
                    singleLine = true,
                    shape = AppShapes.Field,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "时区：${timeZone.id}。重复规则从开始时间起算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "启用",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                val error = localError ?: errorText
                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val startsAt = parseScheduleTime(startText)
                    val rule = when (preset) {
                        RepeatPreset.Once -> "FREQ=DAILY;COUNT=1"
                        RepeatPreset.Daily -> "FREQ=DAILY"
                        RepeatPreset.Weekly -> "FREQ=WEEKLY"
                        RepeatPreset.Custom -> customRule.trim()
                    }
                    localError = when {
                        targetId.isBlank() -> "请选择目标"
                        startsAt == null -> "开始时间格式应为 YYYY-MM-DD HH:MM"
                        rule.isBlank() -> "请填写 RRULE"
                        else -> null
                    }
                    if (localError == null && startsAt != null) {
                        onSave(
                            name.trim(),
                            targetType,
                            targetId,
                            action,
                            rule,
                            timeZone.id,
                            startsAt,
                            enabled,
                        )
                    }
                },
                enabled = canSave,
                shape = CircleShape,
            ) {
                Text(if (busy) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消")
            }
        },
    )
}

private fun targetLabel(
    schedule: Schedule,
    devices: List<Device>,
    groups: List<Group>,
): String = when (schedule.targetType) {
    ScheduleTarget.DEVICE -> {
        val name = devices.firstOrNull { it.id == schedule.targetId }?.name
        "设备 · ${name ?: schedule.targetId}"
    }

    ScheduleTarget.GROUP -> {
        val name = groups.firstOrNull { it.id == schedule.targetId }?.name
        "分组 · ${name ?: schedule.targetId}"
    }
}

private fun actionLabel(action: CommandAction): String = when (action) {
    CommandAction.TURN_ON -> "开"
    CommandAction.TURN_OFF -> "关"
}

private fun resultLabel(result: ScheduleResult): String = when (result) {
    ScheduleResult.DISPATCHED -> "已下发"
    ScheduleResult.PARTIAL -> "部分下发"
    ScheduleResult.SKIPPED -> "已跳过"
    ScheduleResult.FAILED -> "失败"
}
