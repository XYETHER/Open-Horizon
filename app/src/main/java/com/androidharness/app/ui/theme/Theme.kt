package com.androidharness.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.androidharness.app.data.ThemeMode

/**
 * Neutral light/dark surfaces with one optional wallpaper-derived accent.
 *
 * Motion uses the standard (non-expressive) scheme: the app ships its own subtle
 * spring/tween specs via [defaultSpatialSpec] & friends, and the standard scheme
 * keeps M3 components' internal transitions (button shape morphs, indicator
 * behavior) calm instead of playful.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HarnessTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val context = LocalContext.current
    val neutral = if (dark) DarkColors else LightColors
    val base = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val accent = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        neutral.copy(primary = accent.primary, onPrimary = accent.onPrimary,
            inversePrimary = accent.inversePrimary)
    } else neutral
    // AMOLED blacks out the surface roles but keeps the accent roles, so a
    // wallpaper-derived primary survives the switch to true black.
    val colorScheme = if (themeMode == ThemeMode.AMOLED) base.amoledSurfaces() else base

    CompositionLocalProvider(
        LocalStatusColors provides (if (dark) StatusDark else StatusLight),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HarnessTypography,
            shapes = HarnessShapes,
            motionScheme = MotionScheme.standard(),
            content = content,
        )
    }
}
