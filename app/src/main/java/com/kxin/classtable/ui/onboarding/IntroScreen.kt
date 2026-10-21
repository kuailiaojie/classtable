package com.kxin.classtable.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.motionTimeline
import com.kxin.classtable.ui.BrandMark
import kotlinx.coroutines.launch

/**
 * 首次启动介绍:品牌开场 + 功能亮点。
 *
 * 与「权限引导」([OnboardingScreen])分工不同 —— 这一页只讲「这个应用是什么、能做什么」,
 * 事毕再进权限引导。由 [com.kxin.classtable.MainActivity] 按版本门控拉起:新用户与
 * 升级到本主版本的老用户首次打开都会看到,之后同一主版本内不再重复(见 intro_seen_version)。
 *
 * 动效:横向翻页 + 每页按序入场(图形淡入放大 → 标题 → 说明),一条时间线编排,不触发布局。
 */
@Composable
fun IntroScreen(onFinish: () -> Unit) {
    val colors = LocalYohakuColors.current
    val slides = INTRO_SLIDES
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val lastIndex = slides.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(colors.paper),
    ) {
        Text(
            text = "跳过",
            style = YohakuType.copy14,
            color = colors.neutral7,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable(onClick = onFinish)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = YohakuDimens.maxFormWidth)
                .align(Alignment.Center),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                IntroSlideContent(
                    slide = slides[page],
                    active = pagerState.currentPage == page,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            PagerDots(count = slides.size, current = pagerState.currentPage)
            Spacer(modifier = Modifier.height(16.dp))

            YohakuButton(
                text = if (pagerState.currentPage == lastIndex) "开始使用" else "下一步",
                onClick = {
                    if (pagerState.currentPage == lastIndex) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.screenPadding),
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun IntroSlideContent(slide: IntroSlide, active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalYohakuColors.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(active) {
        appear.snapTo(0f)
        if (active) {
            appear.animateTo(
                1f,
                YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeExpoOut),
            )
        }
    }
    val p = appear.value

    Column(
        modifier = modifier.padding(horizontal = YohakuDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .graphicsLayer {
                    alpha = p
                    val scale = 0.9f + 0.1f * p
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            IntroArtwork(slide.art)
        }
        Spacer(modifier = Modifier.height(36.dp))
        Text(
            text = slide.title,
            style = YohakuType.title24,
            color = colors.neutral10,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                alpha = p
                translationY = (1f - p) * 20f
            },
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = slide.desc,
            style = YohakuType.copy15,
            color = colors.neutral7,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                alpha = p
                translationY = (1f - p) * 12f
            },
        )
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(
                targetValue = if (selected) 20.dp else 6.dp,
                animationSpec = YohakuMotion.tween(YohakuMotion.durBase, YohakuMotion.easeOut),
                label = "introDot",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = width, height = 6.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.accent else colors.neutral3),
            )
        }
    }
}

// ---------------------------------------------------------------- 图形

private enum class IntroArt { BRAND, WEEK, IMPORT, REMIND, SYNC }

private data class IntroSlide(val title: String, val desc: String, val art: IntroArt)

private val INTRO_SLIDES = listOf(
    IntroSlide("欢迎使用余课", "把每一天,留一点余白", IntroArt.BRAND),
    IntroSlide("看课表", "一周课表,一眼看完", IntroArt.WEEK),
    IntroSlide("快速导入", "教务 / 表格 / 截图都能导入", IntroArt.IMPORT),
    IntroSlide("上课提醒", "课前提醒,上课自动静音", IntroArt.REMIND),
    IntroSlide("多端同步", "换手机也不丢课表", IntroArt.SYNC),
)

/**
 * 每页的图形。品牌页复用 [BrandMark](条生长 + 扫光);其余四页用最朴素的几何形状画一个
 * 示意小图 —— 不引入图标库,也不出现跟主题无关的彩色插画。
 */
@Composable
private fun IntroArtwork(art: IntroArt) {
    if (art == IntroArt.BRAND) {
        val reveal = remember { Animatable(0f) }
        val sheen = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            motionTimeline {
                step(YohakuMotion.durXSlow, "0") { d ->
                    reveal.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeLinear))
                }
                step(YohakuMotion.durSlow, "+=180") { d ->
                    sheen.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeInOut))
                }
            }
        }
        BrandMark(reveal = reveal.value, sheen = sheen.value, modifier = Modifier.size(160.dp))
        return
    }

    val colors = LocalYohakuColors.current
    Canvas(modifier = Modifier.size(180.dp)) {
        val u = size.minDimension / 100f
        val ox = (size.width - 100f * u) / 2f
        val oy = (size.height - 100f * u) / 2f
        fun at(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)

        when (art) {
            IntroArt.WEEK -> {
                val cols = 3
                val rows = 4
                val gap = 6f
                val cellW = (100f - gap * (cols + 1)) / cols
                val cellH = (100f - gap * (rows + 1)) / rows
                val accentCells = setOf(1, 5, 6, 9)
                repeat(rows) { r ->
                    repeat(cols) { c ->
                        val filled = (r * cols + c) in accentCells
                        drawRoundRect(
                            color = if (filled) colors.accent else colors.neutral3,
                            topLeft = at(gap + c * (cellW + gap), gap + r * (cellH + gap)),
                            size = Size(cellW * u, cellH * u),
                            cornerRadius = CornerRadius(cellH * u / 2f),
                        )
                    }
                }
            }

            IntroArt.IMPORT -> {
                drawRoundRect(
                    color = colors.neutral5,
                    topLeft = at(34f, 8f),
                    size = Size(32f * u, 42f * u),
                    cornerRadius = CornerRadius(6f * u),
                    style = Stroke(width = 2f * u),
                )
                listOf(20f to 59f, 28f to 59f, 36f to 52f).forEach { (y, xEnd) ->
                    drawLine(
                        color = colors.neutral5,
                        start = at(41f, y),
                        end = at(xEnd, y),
                        strokeWidth = 2f * u,
                        cap = StrokeCap.Round,
                    )
                }
                drawLine(colors.accent, at(50f, 58f), at(50f, 82f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(50f, 82f), at(43f, 74f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(50f, 82f), at(57f, 74f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.neutral5, at(34f, 90f), at(66f, 90f), strokeWidth = 2f * u, cap = StrokeCap.Round)
            }

            IntroArt.REMIND -> {
                drawRoundRect(
                    color = colors.neutral3,
                    topLeft = at(16f, 26f),
                    size = Size(68f * u, 40f * u),
                    cornerRadius = CornerRadius(10f * u),
                )
                drawCircle(color = colors.accent, radius = 6f * u, center = at(32f, 46f))
                drawLine(colors.neutral6, at(45f, 41f), at(73f, 41f), strokeWidth = 2.5f * u, cap = StrokeCap.Round)
                drawLine(colors.neutral6, at(45f, 51f), at(64f, 51f), strokeWidth = 2.5f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(50f, 12f), at(50f, 18f), strokeWidth = 2.5f * u, cap = StrokeCap.Round)
                drawCircle(color = colors.accent, radius = 2.5f * u, center = at(50f, 22f))
            }

            IntroArt.SYNC -> {
                drawRoundRect(
                    color = colors.neutral3,
                    topLeft = at(16f, 30f),
                    size = Size(26f * u, 44f * u),
                    cornerRadius = CornerRadius(6f * u),
                )
                drawRoundRect(
                    color = colors.neutral3,
                    topLeft = at(58f, 30f),
                    size = Size(26f * u, 44f * u),
                    cornerRadius = CornerRadius(6f * u),
                )
                drawLine(colors.accent, at(24f, 16f), at(76f, 16f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(76f, 16f), at(68f, 9f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(76f, 16f), at(68f, 23f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(76f, 84f), at(24f, 84f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(24f, 84f), at(32f, 77f), strokeWidth = 3f * u, cap = StrokeCap.Round)
                drawLine(colors.accent, at(24f, 84f), at(32f, 91f), strokeWidth = 3f * u, cap = StrokeCap.Round)
            }

            IntroArt.BRAND -> Unit
        }
    }
}
