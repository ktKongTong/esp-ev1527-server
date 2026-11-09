package dev.koft.ev1527.gui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Android actual returns wallpaper-based dynamic colors on API 31+.
 * Wasm actual always returns the bundled Material 3 palette.
 */
@Composable
expect fun platformColorScheme(darkTheme: Boolean): ColorScheme

/**
 * Android uses the full CJK font; Wasm uses a GB2312 subset to keep the
 * first load within a mobile network budget.
 */
@Composable
expect fun platformFontFamily(): FontFamily

/**
 * Expressive shape scale: visibly rounder than baseline M3, while keeping
 * the five Material slots so components that follow the theme stay coherent.
 */
private val Esp32Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Material 3 Expressive uses heavier display/title styles and tighter
 * letter spacing. Sizes stay on the standard M3 scale so platform font
 * scaling and CJK line breaking keep working.
 */
private fun esp32Typography(fontFamily: FontFamily): Typography = Typography(
    fontFamily = fontFamily,
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

@Composable
fun Esp32Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Skiko on Wasm does not fall back to browser CJK fonts, so both targets
    // ship Noto Sans SC explicitly. The Wasm resource is a GB2312 subset.
    val appFontFamily = platformFontFamily()

    MaterialExpressiveTheme(
        colorScheme = platformColorScheme(darkTheme),
        typography = esp32Typography(appFontFamily),
        shapes = Esp32Shapes,
        content = content,
    )
}
