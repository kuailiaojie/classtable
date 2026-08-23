package com.kxin.classtable.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalYohakuColors = staticCompositionLocalOf { YohakuLightColors }

@Composable
fun YohakuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) YohakuDarkColors else YohakuLightColors
    val colors = accent?.let { base.copy(accent = it) } ?: base

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            background = colors.paper,
            surface = colors.neutral2,
            onBackground = colors.neutral9,
            onSurface = colors.neutral9,
            onPrimary = Color.White,
            outline = colors.neutral5,
            error = colors.error,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            background = colors.paper,
            surface = colors.neutral2,
            onBackground = colors.neutral9,
            onSurface = colors.neutral9,
            onPrimary = Color.White,
            outline = colors.neutral5,
            error = colors.error,
        )
    }

    CompositionLocalProvider(LocalYohakuColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YohakuMaterialTypography,
            shapes = Shapes(),
            content = content,
        )
    }
}
