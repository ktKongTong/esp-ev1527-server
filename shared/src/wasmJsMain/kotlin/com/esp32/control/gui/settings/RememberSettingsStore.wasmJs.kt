package com.esp32.control.gui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath

@Composable
actual fun rememberSettingsStore(): SettingsStore = remember {
    SettingsStore(
        // The web DataStore implementation uses the path's file name as the
        // storage key; the directory is irrelevant.
        PreferenceDataStoreFactory.createWithPath(
            produceFile = { SETTINGS_FILE_NAME.toPath() },
        )
    )
}
