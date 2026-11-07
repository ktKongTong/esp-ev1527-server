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
private val API_TOKEN = stringPreferencesKey("api_token")

/**
 * Persistent client configuration stored with DataStore Preferences. The
 * server URL falls back to the platform default until the user overrides it;
 * the access token has no default because the server rejects untokened calls.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {
    private val preferences: Flow<Preferences> = dataStore.data
        .catch { t ->
            // A corrupt or unreadable file must not prevent the app from
            // starting; the defaults remain usable.
            if (t is CancellationException) throw t
            emit(emptyPreferences())
        }

    val serverUrl: Flow<String> = preferences
        .map { prefs -> prefs[SERVER_URL]?.takeIf { it.isNotBlank() } ?: apiBaseUrl() }

    val apiToken: Flow<String> = preferences
        .map { prefs -> prefs[API_TOKEN]?.trim().orEmpty() }

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

    suspend fun setApiToken(value: String) {
        val normalized = value.trim()
        dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(API_TOKEN)
            } else {
                prefs[API_TOKEN] = normalized
            }
        }
    }
}

/** Trims whitespace and trailing slashes so `$baseUrl/api/v1` stays well-formed. */
fun normalizeServerUrl(raw: String): String = raw.trim().trimEnd('/')
