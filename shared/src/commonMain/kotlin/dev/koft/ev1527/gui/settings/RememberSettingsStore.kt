package dev.koft.ev1527.gui.settings

import androidx.compose.runtime.Composable

/**
 * Creates the process-wide settings store. Each platform supplies the file
 * location; DataStore itself is shared.
 */
@Composable
expect fun rememberSettingsStore(): SettingsStore
