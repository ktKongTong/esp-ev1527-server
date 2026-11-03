package com.esp32.control.gui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.esp32.control.gui.resources.Res
import com.esp32.control.gui.resources.noto_sans_sc_regular
import org.jetbrains.compose.resources.Font

@Composable
actual fun platformFontFamily(): FontFamily =
    FontFamily(Font(Res.font.noto_sans_sc_regular))

@Composable
actual fun platformColorScheme(darkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) Esp32DarkScheme else Esp32LightScheme
    }
}
