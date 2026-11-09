package dev.koft.ev1527.gui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import dev.koft.ev1527.gui.resources.Res
import dev.koft.ev1527.gui.resources.noto_sans_sc_web_v2
import org.jetbrains.compose.resources.Font

@Composable
actual fun platformFontFamily(): FontFamily =
    FontFamily(Font(Res.font.noto_sans_sc_web_v2))

@Composable
actual fun platformColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) Esp32DarkScheme else Esp32LightScheme
