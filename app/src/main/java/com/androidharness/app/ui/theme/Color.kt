package com.androidharness.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Neutral surfaces and a single graphite accent keep conversation and settings calm. */

internal val LightColors = lightColorScheme(
    primary = Color(0xFF242424),
    onPrimary = Color(0xFFFFFEFC),
    primaryContainer = Color(0xFFEEEEEE),
    onPrimaryContainer = Color(0xFF242424),
    secondary = Color(0xFF606060),
    onSecondary = Color(0xFFFFFEFC),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF303030),
    tertiary = Color(0xFF606060),
    onTertiary = Color(0xFFFFFEFC),
    tertiaryContainer = Color(0xFFF0F0F0),
    onTertiaryContainer = Color(0xFF303030),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFEFC),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFEFC),
    onBackground = Color(0xFF242424),
    surface = Color(0xFFFFFEFC),
    onSurface = Color(0xFF242424),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFF929292),
    outlineVariant = Color(0xFFE8E6E0),
    surfaceContainerLowest = Color(0xFFFFFEFC),
    surfaceContainerLow = Color(0xFFF7F6F2),
    surfaceContainer = Color(0xFFF4F3EF),
    surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFEAEAEA),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF4F3EF),
    inversePrimary = Color(0xFFECECEC),
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFECECEC),
    onPrimary = Color(0xFF202020),
    primaryContainer = Color(0xFF303030),
    onPrimaryContainer = Color(0xFFF0F0F0),
    secondary = Color(0xFFB8B8B8),
    onSecondary = Color(0xFF202020),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFB8B8B8),
    onTertiary = Color(0xFF202020),
    tertiaryContainer = Color(0xFF2C2C2C),
    onTertiaryContainer = Color(0xFFE8E8E8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF171717),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF171717),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF909090),
    outlineVariant = Color(0xFF3B3B3B),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF1F1F1F),
    surfaceContainer = Color(0xFF232323),
    surfaceContainerHigh = Color(0xFF2B2B2B),
    surfaceContainerHighest = Color(0xFF343434),
    inverseSurface = Color(0xFFF0F0F0),
    inverseOnSurface = Color(0xFF303030),
    inversePrimary = Color(0xFF242424),
    scrim = Color(0xFF000000),
)

/**
 * AMOLED surfaces: the dark scheme with every background role at (or near)
 * true black, where OLED pixels are fully off. Accent and content roles come
 * from the base scheme untouched, so this works over both the static palette
 * and a wallpaper-derived dynamic one.
 */
internal fun ColorScheme.amoledSurfaces(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1B1B1B),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1D1D1D),
    outlineVariant = Color(0xFF2C2C2C),
)

/**
 * The only hardcoded hues in the app: status colors.
 *
 * M3 has no success/warning roles, and status must read the same regardless of the
 * (dynamic, wallpaper-derived) accent, a green "done" and a red "failed" are
 * universal. Everything else in the UI uses theme roles only.
 */
class StatusColors(val success: Color, val warning: Color)

internal val StatusLight = StatusColors(
    success = Color(0xFF157A3E),
    warning = Color(0xFF9A6A00),
)

internal val StatusDark = StatusColors(
    success = Color(0xFF83D69F),
    warning = Color(0xFFF0C97A),
)

val LocalStatusColors = staticCompositionLocalOf { StatusLight }
