package com.esp32.control.gui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.esp32.control.gui.api.apiBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal const val SETTINGS_FILE_NAME = "esp32-settings.preferences_pb"

private val SERVER_URL = stringPreferencesKey("server_url")

/**
 * Persistent client configuration stored with DataStore Preferences. The
 * server URL falls back to the platform default until the user overrides it.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {
    val serverUrl: Flow<String> = dataStore.data
        .catch { t ->
            // A corrupt or unreadable file must not prevent the app from
            // starting; the defaults remain usable.
            if (t is CancellationException) throw t
            emit(emptyPreferences())
        }
        .map { prefs -> prefs[SERVER_URL]?.takeIf { it.isNotBlank() } ?: apiBaseUrl() }

    suspend fun setServerUrl(value: String) {
        val normalized = normalizeServerUrl(value)
        dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(SERVER_URL)
            } else {
                prefs[SERVER_URL] = normalized
            }
        }
    }
}

/** Trims whitespace and trailing slashes so `$baseUrl/api/v1` stays well-formed. */
fun normalizeServerUrl(raw: String): String = raw.trim().trimEnd('/')
