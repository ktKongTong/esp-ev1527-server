package com.esp32.control.gui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.esp32.control.gui.settings.normalizeServerUrl

@Composable
internal fun SettingsScreen(
    serverUrl: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember(serverUrl) { mutableStateOf(serverUrl) }
    val normalized = normalizeServerUrl(input)
    val valid = normalized.startsWith("http://") || normalized.startsWith("https://")
    val changed = normalized.isNotEmpty() && normalized != serverUrl

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        SectionSurface {
            Text("服务端地址", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                shape = AppShapes.Field,
                placeholder = { Text("http://192.168.1.10:8080") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!valid) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "地址需要以 http:// 或 https:// 开头。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(normalized) },
                enabled = valid && changed,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("保存并重新连接")
            }
        }
    }
}
