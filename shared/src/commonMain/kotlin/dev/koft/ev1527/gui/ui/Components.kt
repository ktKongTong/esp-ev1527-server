package dev.koft.ev1527.gui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.koft.ev1527.gui.resources.Res
import dev.koft.ev1527.gui.resources.ic_add
import dev.koft.ev1527.gui.resources.ic_check_circle
import dev.koft.ev1527.gui.resources.ic_close
import dev.koft.ev1527.gui.resources.ic_delete
import dev.koft.ev1527.gui.resources.ic_groups
import dev.koft.ev1527.gui.resources.ic_schedule
import dev.koft.ev1527.gui.resources.ic_settings_remote
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
internal fun DeleteIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_delete, modifier)

@Composable
internal fun GroupsIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_groups, modifier)

@Composable
internal fun ScheduleIcon(modifier: Modifier = Modifier) =
    AppIcon(Res.drawable.ic_schedule, modifier)

@Composable
internal fun DeleteDialogIcon() {
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
internal fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

@Composable
internal fun CacheErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("无法加载数据", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, shape = CircleShape) {
            Text("重试")
        }
    }
}
