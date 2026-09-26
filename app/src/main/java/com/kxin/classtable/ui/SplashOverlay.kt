package com.kxin.classtable.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxin.classtable.R
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首屏品牌过渡:轻量、可跳过,不阻塞主界面加载。
 *
 * 编排(而不是三段各自 delay):logo 先起 → 标题错峰 80ms → 副标题再 80ms,
 * 整块停留一小会儿后放大淡出。全部只动 alpha / scale / translation。
 */
@Composable
fun SplashOverlay() {
    val colors = LocalYohakuColors.current
    var visible by remember { mutableStateOf(true) }

    val logo = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 时间线:同一时刻并行起跑,各自带不同的延迟与缓动(GSAP timeline + stagger 的写法)
        launch { logo.animateTo(1f, YohakuMotion.tween(520, YohakuMotion.easeExpoOut)) }
        launch {
            delay(80)
            title.animateTo(1f, YohakuMotion.tween(420, YohakuMotion.easeOut))
        }
        launch {
            delay(160)
            tagline.animateTo(1f, YohakuMotion.tween(420, YohakuMotion.easeOut))
        }
        delay(1080)
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.app_icon_02),
                    contentDescription = null,
                    modifier = Modifier
                        .size(104.dp)
                        .graphicsLayer {
                            alpha = logo.value
                            val s = 0.88f + 0.12f * logo.value
                            scaleX = s
                            scaleY = s
                        }
                        .clip(CircleShape),
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
