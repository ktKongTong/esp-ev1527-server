package dev.koft.ev1527.gui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toPath

// Shared with the ViewModel, which outlives individual compositions.
private var sharedSettingsStore: SettingsStore? = null

@Composable
actual fun rememberSettingsStore(): SettingsStore = remember {
    sharedSettingsStore ?: SettingsStore(
        // The web DataStore implementation uses the path's file name as the
        // storage key; the directory is irrelevant.
        PreferenceDataStoreFactory.createWithPath(
            produceFile = { SETTINGS_FILE_NAME.toPath() },
        )
    ).also { sharedSettingsStore = it }
}
