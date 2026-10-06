package app.kino.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val KinoRed = Color(0xFFE50914)
val KinoBlack = Color(0xFF0E0E0E)
val KinoSurface = Color(0xFF181818)
val KinoSurfaceHigh = Color(0xFF242424)
val KinoTextPrimary = Color(0xFFF5F5F5)
val KinoTextSecondary = Color(0xFFB3B3B3)

private val KinoColorScheme = darkColorScheme(
    primary = KinoRed,
    onPrimary = Color.White,
    secondary = KinoRed,
    background = KinoBlack,
    onBackground = KinoTextPrimary,
    surface = KinoSurface,
    onSurface = KinoTextPrimary,
    surfaceVariant = KinoSurfaceHigh,
    onSurfaceVariant = KinoTextSecondary,
    outline = Color(0xFF3A3A3A),
)

private val KinoTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

/** Kino is always dark, on every device. */
@Composable
fun KinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KinoColorScheme,
        typography = KinoTypography,
        content = content,
    )
}
