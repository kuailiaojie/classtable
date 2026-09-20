package com.kxin.classtable.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalYohakuColors = staticCompositionLocalOf { YohakuLightColors }

/** 把 Material 形状槽收敛到 Yohaku 圆角 token(M3 默认最大 28dp)。 */
private val YohakuShapes = Shapes(
    extraSmall = RoundedCornerShape(YohakuDimens.radiusChip),
    small = RoundedCornerShape(YohakuDimens.radiusControl),
    medium = RoundedCornerShape(YohakuDimens.radiusCard),
    large = RoundedCornerShape(YohakuDimens.radiusSheet),
    extraLarge = RoundedCornerShape(YohakuDimens.radiusSheet),
)

@Composable
fun YohakuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) YohakuDarkColors else YohakuLightColors
    val colors = accent?.let { base.copy(accent = it) } ?: base

    // 凡是 Material 组件会消费的槽位都显式落到 Yohaku 中性色上:
    // 之前只填了 8 个槽,其余回落到 M3 baseline,于是弹窗底色是淡淡的紫。
    val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    val colorScheme = scheme.copy(
        primary = colors.accent,
        onPrimary = Color.White,
        primaryContainer = colors.neutral3,
        onPrimaryContainer = colors.neutral10,
        secondary = colors.info,
        onSecondary = Color.White,
        background = colors.paper,
        onBackground = colors.neutral9,
        surface = colors.neutral2,
        onSurface = colors.neutral9,
        surfaceVariant = colors.neutral3,
        onSurfaceVariant = colors.neutral7,
        surfaceContainerLowest = colors.paper,
        surfaceContainerLow = colors.neutral1,
        surfaceContainer = colors.neutral1,
        surfaceContainerHigh = colors.neutral2,
        surfaceContainerHighest = colors.neutral3,
        surfaceTint = colors.neutral2,
        outline = colors.neutral5,
        outlineVariant = colors.neutral3,
        error = colors.error,
        onError = Color.White,
        inverseSurface = colors.neutral9,
        inverseOnSurface = colors.neutral1,
    )

    CompositionLocalProvider(LocalYohakuColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YohakuMaterialTypography,
            shapes = YohakuShapes,
        ) {
            // MaterialTheme 会把 LocalIndication 设成 M3 涟漪;这里换成纸面按压反馈。
            CompositionLocalProvider(
                LocalIndication provides YohakuIndication(colors.neutral8),
                content = content,
            )
        }
    }
}
