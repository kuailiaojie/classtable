package com.kxin.classtable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuMotion

/** 一根课程条:在 240×240 逻辑坐标里的宽度与纵向中心。 */
private data class BrandBar(val width: Float, val centerY: Float)

private val brandBars = listOf(
    BrandBar(width = 128f, centerY = 92f),
    BrandBar(width = 92f, centerY = 120f),
    BrandBar(width = 110f, centerY = 148f),
)

private const val CANVAS_UNITS = 240f
private const val BAR_HEIGHT = 18f
private const val CENTER_X = 120f

/** 入场总进度里每根条所占的跨度与相邻错峰量(把 [reveal] 0→1 分摊到三根条上)。 */
private const val BAR_SPAN = 0.55f
private const val BAR_STAGGER = 0.22f

/**
 * 品牌图形:纸面上一组「课程条」。
 *
 * 只受两个外部进度驱动,时序由调用方编排(见 [SplashOverlay]):
 * - [reveal] 0→1:三根条各自带错峰、由左向右「生长」,缓动取 [YohakuMotion.easeExpoOut];
 * - [sheen] 0→1:一道柔光在条体上自左向右扫过(裁剪在条内,只落在条上)。
 *
 * 颜色取主题 token(accent + 两档中性),因此深浅色都成立 —— 早期手写 Lottie 里硬编码的
 * 浅色在深色模式下会偏色。
 */
@Composable
fun BrandMark(
    reveal: Float,
    sheen: Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val barColors = listOf(colors.accent, colors.neutral3, colors.neutral5)

    Canvas(modifier = modifier) {
        val unit = size.width / CANVAS_UNITS
        val height = size.height
        val topOffset = (height - CANVAS_UNITS * unit) / 2f
        val barH = BAR_HEIGHT * unit
        val radius = CornerRadius(barH / 2f)

        brandBars.forEachIndexed { index, bar ->
            val local = ((reveal - index * BAR_STAGGER) / BAR_SPAN).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            val eased = YohakuMotion.easeExpoOut.transform(local)
            val left = (CENTER_X - bar.width / 2f) * unit
            drawRoundRect(
                color = barColors[index],
                topLeft = Offset(left, topOffset + bar.centerY * unit - barH / 2f),
                size = Size(bar.width * eased * unit, barH),
                cornerRadius = radius,
            )
        }

        if (sheen <= 0f) return@Canvas

        // 三根条合并成一条裁剪路径:柔光只落在条上,不需要离屏层或混合模式。
        val clip = Path().apply {
            brandBars.forEach { bar ->
                val left = (CENTER_X - bar.width / 2f) * unit
                addRoundRect(
                    RoundRect(
                        rect = Rect(
                            offset = Offset(left, topOffset + bar.centerY * unit - barH / 2f),
                            size = Size(bar.width * unit, barH),
                        ),
                        topLeft = radius,
                        topRight = radius,
                        bottomLeft = radius,
                        bottomRight = radius,
                    ),
                )
            }
        }
        clipPath(clip) {
            val bandHalf = 46f * unit
            val centerX = -bandHalf + sheen * (size.width + bandHalf * 2f)
            val brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.6f),
                1f to Color.Transparent,
                startX = centerX - bandHalf,
                endX = centerX + bandHalf,
            )
            drawRect(
                brush = brush,
                topLeft = Offset(0f, topOffset),
                size = Size(size.width, CANVAS_UNITS * unit),
            )
        }
    }
}
