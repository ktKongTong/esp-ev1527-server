package com.esp32.control.gui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun platformColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) Esp32DarkScheme else Esp32LightScheme
