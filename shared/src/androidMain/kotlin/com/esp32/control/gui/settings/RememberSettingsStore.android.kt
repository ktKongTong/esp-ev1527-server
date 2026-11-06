package com.esp32.control.gui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath

// The ViewModel outlives the composition, and DataStore allows only one
// active instance per file. Keep the store process-wide so activity
// recreation reuses it instead of opening the same file twice.
private var sharedSettingsStore: SettingsStore? = null

@Composable
actual fun rememberSettingsStore(): SettingsStore {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        sharedSettingsStore ?: SettingsStore(
            PreferenceDataStoreFactory.createWithPath(
                produceFile = {
                    context.filesDir.resolve(SETTINGS_FILE_NAME).absolutePath.toPath()
                },
            )
        ).also { sharedSettingsStore = it }
    }
}
