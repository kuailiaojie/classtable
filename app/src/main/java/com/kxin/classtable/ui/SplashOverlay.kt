package com.kxin.classtable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
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
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.kxin.classtable.R
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首屏品牌过渡:轻量、可跳过,不阻塞主界面加载。
 *
 * 编排(而不是三段各自 delay):矢量动画 [R.raw.splash_mark] 先起 → 标题错峰 → 副标题再错峰,
 * 整块停留一小会儿后放大淡出。Lottie 负责图形自身的时间线,Compose 只负责与界面衔接的编排 ——
 * 全部只动 alpha / scale / translation。
 *
 * [onExitStart] 在开始退出的那一刻回调,供上层把「引导入场」与开屏退场接成一次连续编排。
 */
@Composable
fun SplashOverlay(onExitStart: () -> Unit = {}) {
    val colors = LocalYohakuColors.current
    var visible by remember { mutableStateOf(true) }
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.splash_mark))

    // Lottie 的进度用线性推进,让 JSON 里自己的缓动说了算;标题 / 副标题再各带延迟起跑
    val mark = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }

    // 图形时间线:等 composition 解码好再起,避免资源未就绪时动画一闪而过
    LaunchedEffect(composition) {
        if (composition == null) return@LaunchedEffect
        launch {
            mark.animateTo(
                targetValue = 1f,
                animationSpec = YohakuMotion.tween(1000, LinearEasing),
            )
        }
        launch {
            delay(360)
            title.animateTo(1f, YohakuMotion.tween(420, YohakuMotion.easeOut))
        }
        launch {
            delay(440)
            tagline.animateTo(1f, YohakuMotion.tween(420, YohakuMotion.easeOut))
        }
    }

    // 退场:独立于图形解码,保底也要把开屏收掉(资源异常时不让界面卡住)
    LaunchedEffect(Unit) {
        delay(1500)
        onExitStart()
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(YohakuMotion.tween(YohakuMotion.durBase)),
        exit = fadeOut(YohakuMotion.tween(YohakuMotion.durBase)) +
            scaleOut(YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeInOut), targetScale = 1.04f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.paper),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LottieAnimation(
                    composition = composition,
                    progress = { mark.value },
                    modifier = Modifier
                        .size(132.dp)
                        .graphicsLayer { alpha = mark.value },
                )
                Text(
                    text = "课表",
                    style = YohakuType.title24,
                    color = colors.neutral10,
                    modifier = Modifier.graphicsLayer {
                        alpha = title.value
                        translationY = (1f - title.value) * 14f
                    },
                )
                Text(
                    text = "把每一天，留一点余白",
                    fontSize = 13.sp,
                    color = colors.neutral6,
                    modifier = Modifier.graphicsLayer {
                        alpha = tagline.value
                        translationY = (1f - tagline.value) * 10f
                    },
                )
            }
        }
    }
}
