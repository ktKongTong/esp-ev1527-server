package com.esp32.control.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esp32.control.gui.api.Esp32Api
import com.esp32.control.gui.model.Device
import com.esp32.control.gui.model.HealthResponse
import com.esp32.control.gui.theme.Esp32Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    Esp32Theme {
        val api = remember { Esp32Api() }
        val scope = rememberCoroutineScope()

        var health by remember { mutableStateOf<HealthResponse?>(null) }
        var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var pendingDeviceId by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        suspend fun loadAll() {
            loading = true
            try {
                health = api.health()
                devices = api.devices()
                error = null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error = t.readableMessage()
            } finally {
                loading = false
            }
        }

        fun toggleDevice(device: Device) {
            if (pendingDeviceId != null) return
            pendingDeviceId = device.id
            scope.launch {
                try {
                    val updated = api.toggle(device.id)
                    devices = devices.map { if (it.id == updated.id) updated else it }
                    error = null
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    error = t.readableMessage()
                } finally {
                    pendingDeviceId = null
                }
            }
        }

        LaunchedEffect(Unit) {
            loadAll()
        }

        DisposableEffect(Unit) {
            onDispose { api.close() }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    title = {
                        Column {
                            Text(
                                text = "ESP32 · 智能控制台",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Material You · ${platformLabel()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 20.dp)
                                    .size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    scope.launch { loadAll() }
                                },
                            ) {
                                Text("刷新")
                            }
                        }
                    },
                )
            },
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1100.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    error?.let { message ->
                        ErrorBanner(
                            message = message,
                            onDismiss = { error = null },
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    HeroPanel(health = health)

                    Spacer(Modifier.height(16.dp))

                    ServerPanel(
                        health = health,
                        baseUrl = api.baseUrl,
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "增氧机",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (devices.isEmpty()) {
                                    "等待服务端返回设备"
                                } else {
                                    "${devices.size} 台设备 · 点击开关下发控制"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (devices.isNotEmpty()) {
                            StatusPill(
                                text = "${devices.count { it.on }} 台运行",
                                highlighted = devices.any { it.on },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    when {
                        devices.isEmpty() && loading -> LoadingDevices()
                        devices.isEmpty() -> EmptyDevices()
                        else -> DeviceGrid(
                            devices = devices,
                            pendingDeviceId = pendingDeviceId,
                            onToggle = ::toggleDevice,
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    FooterPanel(apiBaseUrl = api.baseUrl)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun HeroPanel(health: HealthResponse?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "315",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "鱼塘增氧控制",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Android 与 Web 共用一套 Compose UI",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = if (health == null) "服务端未连接" else "服务端在线 · ${health.status}",
                    highlighted = health != null,
                )
                StatusPill(
                    text = if (health?.gatewayConnected == true) "MQTT 已连接" else "MQTT 演示模式",
                    highlighted = false,
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "服务端运行在 Ktor + Kotlin/Native；界面由 Compose Multiplatform 在 Android 与浏览器 Wasm 上原生渲染。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun ServerPanel(health: HealthResponse?, baseUrl: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "服务端状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = if (health == null) "离线" else "已连接",
                    highlighted = health != null,
                )
            }

            Spacer(Modifier.height(14.dp))

            HealthRow("API 地址", baseUrl, monospace = true)
            HealthRow("运行时", health?.runtime ?: "—")
            HealthRow("版本", health?.version ?: "—")
            HealthRow("运行时长", health?.uptimeSeconds?.let(::formatUptime) ?: "—")
            HealthRow("数据来源", health?.dataSource ?: "—")

            Spacer(Modifier.height(12.dp))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "当前为内存演示数据，尚未接入 MQTT。下一步可把开关操作映射到 mock/ 的 MQTTto315 指令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceGrid(
    devices: List<Device>,
    pendingDeviceId: String?,
    onToggle: (Device) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 760.dp
        if (twoColumns) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                devices.chunked(2).forEach { rowDevices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        rowDevices.forEach { device ->
                            DeviceCard(
                                device = device,
                                pending = pendingDeviceId == device.id,
                                onToggle = { onToggle(device) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowDevices.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                devices.forEach { device ->
                    DeviceCard(
                        device = device,
                        pending = pendingDeviceId == device.id,
                        onToggle = { onToggle(device) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    pending: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (device.on) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (device.on) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (device.on) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (device.on) "ON" else "OFF",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (device.on) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = device.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.72f),
                    )
                }

                StatusPill(
                    text = if (device.on) "运行中" else "已停止",
                    highlighted = device.on,
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "最近操作",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.68f),
                    )
                    Text(
                        text = device.lastAction,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                } else {
                    Switch(
                        checked = device.on,
                        onCheckedChange = { onToggle() },
                        enabled = device.online,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            HorizontalDivider(color = contentColor.copy(alpha = 0.12f))

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // Keep the CJK label in the bundled app font and only the numeric
                // code in monospace: Skiko has no CJK fallback inside Monospace.
                CodeLabel(label = "开码", code = device.codeOn)
                CodeLabel(label = "关码", code = device.codeOff)
            }
        }
    }
}

@Composable
private fun CodeLabel(label: String, code: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = code.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatusPill(text: String, highlighted: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        },
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LoadingDevices() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("正在读取设备…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyDevices() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "没有设备数据",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "请先启动 server，然后点击右上角“刷新”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "请求失败",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
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

@Composable
private fun FooterPanel(apiBaseUrl: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("MQTT 接入将在下一步完成")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Material You：Android 12+ 使用系统动态取色；Web 使用内置品牌配色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "API $apiBaseUrl",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

private fun formatUptime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun Throwable.readableMessage(): String {
    val raw = message?.takeIf { it.isNotBlank() } ?: toString()
    return raw.lineSequence().firstOrNull()?.take(240) ?: "未知错误"
}
