package com.esp32.control.gui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath

@Composable
actual fun rememberSettingsStore(): SettingsStore {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        SettingsStore(
            PreferenceDataStoreFactory.createWithPath(
                produceFile = {
                    context.filesDir.resolve(SETTINGS_FILE_NAME).absolutePath.toPath()
                },
            )
        )
    }
}
