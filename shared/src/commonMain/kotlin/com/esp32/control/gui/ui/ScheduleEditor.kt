@file:OptIn(ExperimentalMaterial3Api::class)

package com.esp32.control.gui.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esp32.control.contract.CommandAction
import com.esp32.control.contract.Device
import com.esp32.control.contract.Group
import com.esp32.control.contract.Schedule
import com.esp32.control.contract.ScheduleTarget
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * All schedules use one time zone. The client neither reads the device zone
 * nor lets the user pick one.
 */
internal val scheduleTimeZone: TimeZone = TimeZone.of("Asia/Shanghai")

private enum class RepeatPreset { Once, Daily, Weekly }

private val weekdayOrder = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)

private val weeklyByDayRule = Regex(
    """FREQ=WEEKLY;BYDAY=((?:MO|TU|WE|TH|FR|SA|SU)(?:,(?:MO|TU|WE|TH|FR|SA|SU))*)""",
)

private fun presetOf(rrule: String): RepeatPreset {
    val rule = rrule.trim().uppercase()
    return when {
        rule == "FREQ=DAILY;COUNT=1" -> RepeatPreset.Once
        rule == "FREQ=DAILY" -> RepeatPreset.Daily
        rule == "FREQ=WEEKLY" || weeklyByDayRule.matches(rule) -> RepeatPreset.Weekly
        // Monthly and custom rules are no longer offered; an old schedule is
        // edited as a one-off instead of being shown as an unsupported preset.
        else -> RepeatPreset.Once
    }
}

private fun weeklyDaysOf(rrule: String): Set<DayOfWeek> {
    val match = weeklyByDayRule.matchEntire(rrule.trim().uppercase()) ?: return emptySet()
    return match.groupValues[1]
        .split(',')
        .mapNotNull { code -> weekdayOrder.firstOrNull { it.rruleCode() == code } }
        .toSet()
}

private fun DayOfWeek.rruleCode(): String = name.take(2)

private fun DayOfWeek.chipLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

private fun RepeatPreset.label(): String = when (this) {
    RepeatPreset.Once -> "一次"
    RepeatPreset.Daily -> "每天"
    RepeatPreset.Weekly -> "每周"
}

private fun buildRrule(preset: RepeatPreset, weekdays: Set<DayOfWeek>): String =
    when (preset) {
        RepeatPreset.Once -> "FREQ=DAILY;COUNT=1"
        RepeatPreset.Daily -> "FREQ=DAILY"
        RepeatPreset.Weekly -> "FREQ=WEEKLY;BYDAY=" +
            weekdays.sortedBy { it.ordinal }.joinToString(",") { it.rruleCode() }
    }

/**
 * Resolve the next occurrence at the chosen clock time so the server always
 * receives a future DTSTART. Weekly keeps the next selected weekday.
 */
private fun nextStart(
    preset: RepeatPreset,
    weekdays: Set<DayOfWeek>,
    hour: Int,
    minute: Int,
    now: Instant,
): Long {
    val today = now.toLocalDateTime(scheduleTimeZone).date
    val days = if (preset == RepeatPreset.Weekly) 7 else 1
    for (offset in 0..days) {
        val date = today.plus(offset, DateTimeUnit.DAY)
        if (preset == RepeatPreset.Weekly && date.dayOfWeek !in weekdays) continue
        val candidate = LocalDateTime(date, LocalTime(hour, minute))
            .toInstant(scheduleTimeZone)
        if (candidate > now) return candidate.toEpochMilliseconds()
    }
    return LocalDateTime(today.plus(days, DateTimeUnit.DAY), LocalTime(hour, minute))
        .toInstant(scheduleTimeZone)
        .toEpochMilliseconds()
}

@Composable
internal fun ScheduleEditorSheet(
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
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val initialTime = remember(schedule?.id) {
        schedule?.let {
            Instant.fromEpochMilliseconds(it.startsAtEpochMs)
                .toLocalDateTime(scheduleTimeZone)
        } ?: (Clock.System.now() + 5.minutes).toLocalDateTime(scheduleTimeZone)
    }
    val initialRule = schedule?.rrule.orEmpty()
    val initialPreset = if (schedule == null) RepeatPreset.Once else presetOf(initialRule)
    val initialTargetType = schedule?.targetType
        ?: if (devices.isNotEmpty() || groups.isEmpty()) ScheduleTarget.DEVICE else ScheduleTarget.GROUP
    val initialTargetId = schedule?.targetId
        ?: if (initialTargetType == ScheduleTarget.DEVICE) {
            devices.firstOrNull()?.id.orEmpty()
        } else {
            groups.firstOrNull()?.id.orEmpty()
        }

    var name by remember(schedule?.id) { mutableStateOf(schedule?.name.orEmpty()) }
    var targetType by remember(schedule?.id) { mutableStateOf(initialTargetType) }
    var targetId by remember(schedule?.id) { mutableStateOf(initialTargetId) }
    var action by remember(schedule?.id) {
        mutableStateOf(schedule?.action ?: CommandAction.TURN_ON)
    }
    var preset by remember(schedule?.id) { mutableStateOf(initialPreset) }
    var weekdays by remember(schedule?.id) {
        mutableStateOf(
            if (initialPreset == RepeatPreset.Weekly) {
                weeklyDaysOf(initialRule).ifEmpty { setOf(initialTime.date.dayOfWeek) }
            } else {
                emptySet()
            },
        )
    }
    var startHour by remember(schedule?.id) { mutableStateOf(initialTime.hour) }
    var startMinute by remember(schedule?.id) { mutableStateOf(initialTime.minute) }
    var enabled by remember(schedule?.id) { mutableStateOf(schedule?.enabled ?: true) }
    var localError by remember(schedule?.id) { mutableStateOf<String?>(null) }
    var targetMenuExpanded by remember(schedule?.id) { mutableStateOf(false) }
    var showTimePicker by remember(schedule?.id) { mutableStateOf(false) }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val selectedTargetLabel = when (targetType) {
        ScheduleTarget.DEVICE -> devices.firstOrNull { it.id == targetId }
            ?.let { "${it.name} · ${it.location}" }

        ScheduleTarget.GROUP -> groups.firstOrNull { it.id == targetId }
            ?.let { "${it.name} · ${it.deviceIds.size} 台设备" }
    }.orEmpty()
    val targetExists = when (targetType) {
        ScheduleTarget.DEVICE -> devices.any { it.id == targetId }
        ScheduleTarget.GROUP -> groups.any { it.id == targetId }
    }

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
                    text = if (schedule == null) "创建定时" else "编辑定时",
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
            SectionLabel("目标")
            ExposedDropdownMenuBox(
                expanded = targetMenuExpanded,
                onExpandedChange = { targetMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedTargetLabel.ifBlank { "选择设备或分组" },
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    shape = AppShapes.Field,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = targetMenuExpanded,
                    onDismissRequest = { targetMenuExpanded = false },
                ) {
                    if (devices.isEmpty() && groups.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("还没有设备或分组") },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    if (devices.isNotEmpty()) {
                        MenuSectionLabel("设备")
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { TargetOption(device.name, device.location) },
                                onClick = {
                                    targetType = ScheduleTarget.DEVICE
                                    targetId = device.id
                                    targetMenuExpanded = false
                                },
                            )
                        }
                    }
                    if (groups.isNotEmpty()) {
                        MenuSectionLabel("分组")
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = {
                                    TargetOption(group.name, "${group.deviceIds.size} 台设备")
                                },
                                onClick = {
                                    targetType = ScheduleTarget.GROUP
                                    targetId = group.id
                                    targetMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("动作")
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

            Spacer(Modifier.height(16.dp))
            PickerField(
                label = "时间",
                value = LocalTime(startHour, startMinute).toString(),
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("重复")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatPreset.entries.forEach { option ->
                    FilterChip(
                        selected = preset == option,
                        onClick = {
                            preset = option
                            if (option == RepeatPreset.Weekly && weekdays.isEmpty()) {
                                weekdays = setOf(
                                    Clock.System.now()
                                        .toLocalDateTime(scheduleTimeZone)
                                        .date
                                        .dayOfWeek,
                                )
                            }
                        },
                        label = { Text(option.label()) },
                    )
                }
            }
            if (preset == RepeatPreset.Weekly) {
                Spacer(Modifier.height(8.dp))
                weekdayOrder.chunked(4).forEach { rowDays ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowDays.forEach { day ->
                            FilterChip(
                                selected = day in weekdays,
                                onClick = {
                                    weekdays = if (day in weekdays) {
                                        weekdays - day
                                    } else {
                                        weekdays + day
                                    }
                                },
                                label = { Text(day.chipLabel()) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "启用",
                    style = MaterialTheme.typography.bodyLarge,
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

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    localError = when {
                        !targetExists -> "请选择目标"
                        preset == RepeatPreset.Weekly && weekdays.isEmpty() ->
                            "请选择每周重复的日子"

                        else -> null
                    }
                    if (localError == null) {
                        onSave(
                            name.trim(),
                            targetType,
                            targetId,
                            action,
                            buildRrule(preset, weekdays),
                            scheduleTimeZone.id,
                            nextStart(
                                preset = preset,
                                weekdays = weekdays,
                                hour = startHour,
                                minute = startMinute,
                                now = Clock.System.now(),
                            ),
                            enabled,
                        )
                    }
                },
                enabled = !busy,
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
                    Text("删除定时")
                }
            }
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = startHour,
            initialMinute = startMinute,
            is24Hour = true,
        )
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            confirmButton = {
                TextButton(
                    onClick = {
                        startHour = timePickerState.hour
                        startMinute = timePickerState.minute
                        showTimePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("取消")
                }
            },
        ) {
            TimePicker(state = timePickerState)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun TargetOption(name: String, detail: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = AppShapes.Field,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(64.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
