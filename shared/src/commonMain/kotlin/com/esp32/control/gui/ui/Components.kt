package com.esp32.control.gui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.esp32.control.gui.resources.Res
import com.esp32.control.gui.resources.ic_add
import com.esp32.control.gui.resources.ic_check_circle
import com.esp32.control.gui.resources.ic_close
import com.esp32.control.gui.resources.ic_delete
import com.esp32.control.gui.resources.ic_error
import com.esp32.control.gui.resources.ic_groups
import com.esp32.control.gui.resources.ic_schedule
import com.esp32.control.gui.resources.ic_settings
import com.esp32.control.gui.resources.ic_settings_remote
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Shape tokens for the expressive layout. MaterialTheme.shapes carries the
 * five standard slots; these are the component-specific radii that the
 * design uses repeatedly (cards, sheets, dialogs, fields, FAB).
 */
internal object AppShapes {
    val Card = RoundedCornerShape(28.dp)
    val Sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val Dialog = RoundedCornerShape(32.dp)
    val Field = RoundedCornerShape(20.dp)
    val Inset = RoundedCornerShape(18.dp)
    val Fab = RoundedCornerShape(24.dp)
}

internal enum class StatusTone { Active, Warning, Neutral }

@Composable
private fun AppIcon(resource: DrawableResource, modifier: Modifier) {
    Icon(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier,
    )
}

@Composable
internal fun DeviceIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_settings_remote, modifier)

@Composable
internal fun AddIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_add, modifier)

@Composable
internal fun CloseIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_close, modifier)

@Composable
internal fun CheckCircleIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_check_circle, modifier)

@Composable
internal fun ErrorIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_error, modifier)

@Composable
internal fun DeleteIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_delete, modifier)

@Composable
internal fun SettingsIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_settings, modifier)

@Composable
internal fun GroupsIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_groups, modifier)

@Composable
internal fun ScheduleIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_schedule, modifier)

@Composable
internal fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

@Composable
internal fun StatusChip(text: String, tone: StatusTone) {
    val container = when (tone) {
        StatusTone.Active -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.errorContainer
        StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (tone) {
        StatusTone.Active -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val dot = when (tone) {
        StatusTone.Active -> MaterialTheme.colorScheme.primary
        StatusTone.Warning -> MaterialTheme.colorScheme.error
        StatusTone.Neutral -> MaterialTheme.colorScheme.outline
    }

    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun SectionSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
internal fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text("关闭")
            }
        }
    }
}

@Composable
internal fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ErrorIcon(Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "请求失败",
                    style = MaterialTheme.typography.titleSmall,
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
