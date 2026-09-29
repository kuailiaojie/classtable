package com.kxin.classtable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.motionTimeline

/**
 * 首屏品牌过渡:轻量、可跳过,不阻塞主界面加载。
 *
 * 一条编排好的时间线,而不是三段各自 delay:
 * 三根课程条逐条生长(各带错峰)→ 一道柔光自左向右扫过 → 标题错峰上浮 → 副标题再跟上 →
 * 整块停留一小会儿后向上擦除淡出。全部只动 alpha / translation / (条内)绘制,不触发布局。
 *
 * [onExitStart] 在开始退出的那一刻回调,供上层把「引导入场」与开屏退场接成一次连续编排。
 */
@Composable
fun SplashOverlay(onExitStart: () -> Unit = {}) {
    val colors = LocalYohakuColors.current
    var visible by remember { mutableStateOf(true) }

    val reveal = remember { Animatable(0f) }
    val sheen = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }

    // 一条时间线管到底:三根条生长 → 柔光扫过 → 标题上浮 → 副标题跟上 → 停留后收场。
    // 起点用绝对毫秒(GSAP 的 position 参数),各自重叠多少一目了然,不再散落一串 delay()。
    // 顺带修掉一个老问题:原来的 delay() 不随系统「移除动画」缩放,整块仍要卡满 1.9s;
    // 现在 motionTimeline 会按 MotionDurationScale 压缩起点偏移,关掉动画即瞬时收场。
    LaunchedEffect(Unit) {
        motionTimeline {
            step(YohakuMotion.durXSlow, "0") { d ->
                reveal.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeLinear))
            }
            step(YohakuMotion.durSlow, "620") { d ->
                sheen.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeInOut))
            }
            step(YohakuMotion.durSlow, "880") { d ->
                title.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeExpoOut))
            }
            step(YohakuMotion.durSlow, "1040") { d ->
                tagline.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeExpoOut))
            }
            // 收场:通知上层把「引导入场」接上,并把整块交给 AnimatedVisibility 擦除
            step(0, "1900") {
                onExitStart()
                visible = false
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(YohakuMotion.tween(YohakuMotion.durBase)),
        // 向上擦除:整块上移 + 淡出,把视线交给紧接着入场的引导页
        exit = fadeOut(YohakuMotion.tween(YohakuMotion.durBase)) +
            slideOutVertically(
                animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeInOut),
            ) { height -> -height / 3 },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.paper),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BrandMark(
                    reveal = reveal.value,
                    sheen = sheen.value,
                    modifier = Modifier.size(132.dp),
                )
                Text(
                    text = "课表",
                    style = YohakuType.title24,
                    color = colors.neutral10,
                    modifier = Modifier.graphicsLayer {
                        alpha = title.value
                        translationY = (1f - title.value) * 24f
                    },
                )
                Text(
                    text = "把每一天，留一点余白",
                    fontSize = 13.sp,
                    color = colors.neutral6,
                    modifier = Modifier.graphicsLayer {
                        alpha = tagline.value
                        translationY = (1f - tagline.value) * 16f
                    },
                )
            }
        }
    }
}
