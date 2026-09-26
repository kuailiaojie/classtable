package com.kxin.classtable.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 纸面底部面板(「新建日程」那种)。
 *
 * 与弹窗同一套语言:浮起面 + 细边框 + 上圆角,但不居中出现 —— 从底部**滑入**,
 * 关闭时滑出。点面板外的空白处关闭。
 *
 * [modifier] 用来给面板定高(如 `Modifier.fillMaxHeight(0.9f)`);内容自行滚动。
 */
@Composable
fun YohakuSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalYohakuColors.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 入场:从下方滑入 + 淡入(关闭由系统窗口退场,故只做入场)
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val progress by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeExpoOut),
            label = "sheetIn",
        )
        val scrimInteraction = remember { MutableInteractionSource() }
        val sheetInteraction = remember { MutableInteractionSource() }
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 点面板之外关闭(无涟漪)
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = progress
                        translationY = (1f - progress) * 56f
                    }
                    .clip(shape)
                    .background(colors.raised)
                    .border(1.dp, colors.line, shape)
                    // 吃掉落在面板上的点击,避免穿透到遮罩被当成「点外部」
                    .clickable(
                        interactionSource = sheetInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 16.dp),
                content = content,
            )
        }
    }
}
