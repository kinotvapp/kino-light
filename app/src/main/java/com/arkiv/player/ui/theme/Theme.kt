package com.arkiv.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ArkivRed = Color(0xFFE50914)
val ArkivBlack = Color(0xFF0E0E0E)
val ArkivSurface = Color(0xFF181818)
val ArkivSurfaceHigh = Color(0xFF242424)
val ArkivTextPrimary = Color(0xFFF5F5F5)
val ArkivTextSecondary = Color(0xFFB3B3B3)

/**
 * Green already used elsewhere in the app for "good state" (LATINO language in
 * [com.arkiv.player.ui.catalog.PlaySources], "series" category in
 * [com.arkiv.player.ui.search.SearchScreen]) -- reused to mark "already saved on the device" so as
 * not to invent a new color or step on the brand red (reserved for CTAs). The name is a holdover
 * from when this marked a NUC download; that server-side download path was removed in this
 * branch's pruning, and the color now marks a plain local download instead. Lives here, not in a
 * screen, because it's shared between [com.arkiv.player.ui.components.DownloadControl] and the
 * "Mi biblioteca" detail ([com.arkiv.player.ui.detail.DetailScreen]): it's the SAME indicator and
 * has to look the same in both.
 */
val NucDownloadedGreen = Color(0xFF4CAF50)

private val ArkivColorScheme = darkColorScheme(
    primary = ArkivRed,
    onPrimary = Color.White,
    secondary = ArkivRed,
    background = ArkivBlack,
    onBackground = ArkivTextPrimary,
    surface = ArkivSurface,
    onSurface = ArkivTextPrimary,
    surfaceVariant = ArkivSurfaceHigh,
    onSurfaceVariant = ArkivTextSecondary,
    outline = Color(0xFF3A3A3A),
)

private val ArkivTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

@Composable
fun ArkivTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // Arkiv is always dark
    MaterialTheme(
        colorScheme = ArkivColorScheme,
        typography = ArkivTypography,
        content = content,
    )
}
