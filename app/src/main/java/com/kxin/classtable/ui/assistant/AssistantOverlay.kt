package com.kxin.classtable.ui.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * AI 助手覆盖层:从课表页头的「✦ 助手」按钮打开的**底部面板**,不是独立路由页。
 *
 * 结构:压暗底层的遮罩 + 从底部滑入的面板。面板占屏高 [PANEL_HEIGHT_FRACTION],顶部圆角,
 * 顶端一道拖拽把手 —— 抓住它往下拽(或甩)即可收起,是底部面板该有的手感。
 *
 * 关闭共三条路:下滑把手 / 点遮罩 / 系统返回键。都先跑退场动画,动画结束才回调
 * [onDismiss] 让宿主卸载,避免面板"啪"地消失。
 *
 * 「打开某个页面」(面板内的导航动作)走同一条收尾路径:先关面板、动画跑完再 [onNavigate] ——
 * 于是不会出现「面板还在、底下的页已经换了」的错位。
 */
@Composable
fun AssistantOverlay(
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    // 命中即入场:初始 targetState = true,首帧就跑滑入动画
    val panel = remember { MutableTransitionState(false).apply { targetState = true } }
    var closing by remember { mutableStateOf(false) }
    // 关面板后要去哪:null = 只是关掉
    var routeAfterClose by remember { mutableStateOf<String?>(null) }

    fun close(then: String? = null) {
        if (closing) return
        closing = true
        routeAfterClose = then
        panel.targetState = false
    }

    // 退场跑完再通知宿主:导航 / 单纯关闭二选一
    LaunchedEffect(panel) {
        snapshotFlow { panel.isIdle && !panel.currentState }
            .collect { closed -> if (closed) routeAfterClose?.let(onNavigate) ?: onDismiss() }
    }
    BackHandler(onBack = { close() })

    // 下滑收起:只在顶端把手上识别纵向拖拽 —— 消息列表的滚动、输入框的手势都不受影响。
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { DISMISS_THRESHOLD.toPx() }
    val dragState = rememberDraggableState { delta ->
        scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
    }

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
            ) { it } + fadeIn(animationSpec = YohakuMotion.tween(YohakuMotion.durBase)),
            exit = slideOutVertically(
                animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeInOut),
            ) { it } + fadeOut(animationSpec = YohakuMotion.tween(YohakuMotion.durFast)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(PANEL_HEIGHT_FRACTION)
                    // 跟手位移:拖拽时整块面板随手指下移,松手后回位或继续滑出
                    .offset { IntOffset(0, dragOffset.value.roundToInt()) },
                shape = RoundedCornerShape(
                    topStart = YohakuDimens.radiusSheet,
                    topEnd = YohakuDimens.radiusSheet,
                ),
                // 浮起面 + 投影:面板要读成「浮在课表之上的一层」,而不是盖上去的一张白纸
                // (与悬浮底部导航同一套浮起语言:更亮的表面 + 一道投影,不用硬边框)。
                color = colors.raised,
                shadowElevation = YohakuDimens.navElevation,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    DragHandle(
                        modifier = Modifier.draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                if (dragOffset.value > dismissThresholdPx || velocity > FLING_VELOCITY) {
                                    close()
                                } else {
                                    dragOffset.animateTo(0f, YohakuMotion.gentleSpring())
                                }
                            },
                        ),
                    )
                    AssistantPanel(
                        onOpenSettings = { close("ai_key") },
                        onNavigate = { route -> close(route) },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

/** 顶端拖拽把手:一道居中的短横条,是全屏唯一「可以往下拽」的指示。 */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    val colors = LocalYohakuColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.neutral3),
        )
    }
}

/** 面板占屏高比例:底下仍能看到被压暗的课表,读得出"它从下面升上来"。 */
private const val PANEL_HEIGHT_FRACTION = 0.85f

/** 下滑超过这段距离就判定收起(px 阈值在 [AssistantOverlay] 内按密度换算)。 */
private val DISMISS_THRESHOLD = 110.dp

/** 快速甩下的速度阈值(px/s):距离不够、但甩得够快也收起。 */
private const val FLING_VELOCITY = 900f
