package com.esp32.control.gui.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esp32.control.contract.Device
import com.esp32.control.contract.GatewayState
import com.esp32.control.contract.Group
import com.esp32.control.contract.HealthResponse
import com.esp32.control.contract.Schedule
import com.esp32.control.contract.ScheduleTarget
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime

private val weeklyByDayRule = Regex(
    """FREQ=WEEKLY;BYDAY=((?:MO|TU|WE|TH|FR|SA|SU)(?:,(?:MO|TU|WE|TH|FR|SA|SU))*)""",
)
private val clockTimeFormat = LocalTime.Format {
    hour()
    char(':')
    minute()
}

private fun formatClockTime(epochMs: Long): String =
    clockTimeFormat.format(
        Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(scheduleTimeZone)
            .time,
    )

private fun describeRepeat(schedule: Schedule): String {
    val rule = schedule.rrule.trim().uppercase()
    weeklyByDayRule.matchEntire(rule)?.let { match ->
        val days = match.groupValues[1].split(',').mapNotNull(::weekdayLabel)
        return if (days.isEmpty()) "每周" else "每周${days.joinToString("、")}"
    }
    return when (rule) {
        "FREQ=DAILY;COUNT=1" -> "一次"
        "FREQ=DAILY" -> "每天"
        "FREQ=WEEKLY" -> "每周"
        else -> "自定义"
    }
}

private fun weekdayLabel(code: String): String? = when (code) {
    "MO" -> "一"
    "TU" -> "二"
    "WE" -> "三"
    "TH" -> "四"
    "FR" -> "五"
    "SA" -> "六"
    "SU" -> "日"
    else -> null
}

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
    onOpen: () -> Unit,
) {
    val timeColor = if (schedule.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (schedule.enabled) 1f else 0.6f,
    )

    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatClockTime(schedule.startsAtEpochMs),
                    style = MaterialTheme.typography.displayMedium,
                    color = timeColor,
                    maxLines = 1,
                )
                Text(
                    text = "${targetLabel(schedule, devices, groups)} · ${describeRepeat(schedule)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

private fun targetLabel(
    schedule: Schedule,
    devices: List<Device>,
    groups: List<Group>,
): String = when (schedule.targetType) {
    ScheduleTarget.DEVICE -> devices.firstOrNull { it.id == schedule.targetId }?.name ?: "未知设备"
    ScheduleTarget.GROUP -> groups.firstOrNull { it.id == schedule.targetId }?.name ?: "未知分组"
}
