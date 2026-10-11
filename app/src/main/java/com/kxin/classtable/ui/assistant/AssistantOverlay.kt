package com.kxin.classtable.ui.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion

/**
 * AI 助手覆盖层:从课表页头的「✦ 助手」按钮打开,不是独立路由页。
 *
 * 结构:压暗底层的遮罩 + 从顶部滑入的面板。面板占屏高 [PANEL_HEIGHT_FRACTION],底部圆角。
 *
 * 关闭有三条路:点遮罩 / 系统返回键 / 面板左上角返回箭头。三条都先跑退场动画,
 * 动画结束才回调 [onDismiss] 让宿主卸载,避免面板"啪"地消失。
 */
@Composable
fun AssistantOverlay(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    // 命中即入场:初始 targetState = true,首帧就跑滑入动画
    val panel = remember { MutableTransitionState(false).apply { targetState = true } }
    var closing by remember { mutableStateOf(false) }
    var openSettingsAfterClose by remember { mutableStateOf(false) }

    fun close() {
        if (closing) return
        closing = true
        panel.targetState = false
    }

    // 退场跑完再通知宿主:去设置 / 单纯关闭二选一
    LaunchedEffect(panel) {
        snapshotFlow { panel.isIdle && !panel.currentState }
            .collect { closed -> if (closed) if (openSettingsAfterClose) onOpenSettings() else onDismiss() }
    }
    BackHandler(onBack = { close() })

    val scrimAlpha by animateFloatAsState(
        targetValue = if (panel.targetState) 1f else 0f,
        animationSpec = YohakuMotion.tween(
            if (panel.targetState) YohakuMotion.durBase else YohakuMotion.durFast,
        ),
        label = "assistantScrim",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { close() },
                ),
        )
        AnimatedVisibility(
            visibleState = panel,
            enter = slideInVertically(
                animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeOut),
            ) { -it } + fadeIn(animationSpec = YohakuMotion.tween(YohakuMotion.durBase)),
            exit = slideOutVertically(
                animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeInOut),
            ) { -it } + fadeOut(animationSpec = YohakuMotion.tween(YohakuMotion.durFast)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(PANEL_HEIGHT_FRACTION),
                shape = RoundedCornerShape(
                    bottomStart = YohakuDimens.radiusSheet,
                    bottomEnd = YohakuDimens.radiusSheet,
                ),
                // 浮起面 + 投影:面板要读成「浮在课表之上的一层」,而不是盖上去的一张白纸
                // (与悬浮底部导航同一套浮起语言:更亮的表面 + 一道投影,不用硬边框)。
                color = colors.raised,
                shadowElevation = YohakuDimens.navElevation,
            ) {
                AssistantPanel(
                    onClose = { close() },
                    onOpenSettings = {
                        openSettingsAfterClose = true
                        close()
                    },
                    viewModel = viewModel,
                )
            }
        }
    }
}

/** 面板占屏高比例:贴顶留一条缝,底下仍能看到课表,读得出"它从上面滑下来"。 */
private const val PANEL_HEIGHT_FRACTION = 0.92f
