package com.kxin.classtable.design

import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

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
        // decorFitsSystemWindows = false:把导航栏/键盘内边距交给内容自己处理,
        // 否则面板会一直铺到屏幕最下,底部的按钮被导航栏或键盘压住、够不到。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 入场:从下方滑入 + 淡入(关闭由系统窗口退场,故只做入场)
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }

        // 关键:Dialog 窗口默认按 WRAP_CONTENT 测量,内容拿不到「一屏」的硬约束 ——
        // 于是 fillMaxSize / 面板的 fillMaxHeight(0.9f) / 子项的 weight 会一起失效,
        // 面板被内容撑到屏幕外、底部按钮掉到屏幕下方。显式把窗口钉成整屏
        // (新版 Compose 内部也是这么兜的),约束重新成立。
        val dialogView = LocalView.current
        LaunchedEffect(Unit) {
            dialogView.findDialogWindow()?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
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
                .fillMaxWidth()
                // Dialog 在部分 Compose 版本下按 WRAP_CONTENT 测量,内容拿不到封顶高度,
                // 于是 fillMaxSize / 面板的 fillMaxHeight(0.9f) / 子项的 weight 全部失效 ——
                // 面板被内容撑到屏幕外,底部按钮掉到屏幕下方、还滚不动。
                // 这里把容器显式钉到「最多一屏」再填满,约束重新成立,面板才受控。
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp)
                .fillMaxHeight()
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
                    // 底部让开「导航栏」或「键盘」中更高的那个:两者叠加会多顶一截
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 16.dp),
                content = content,
            )
        }
    }
}

/** 沿 parent 链找到承载本面板的 Dialog 窗口(DialogLayout 实现了 [DialogWindowProvider])。 */
private fun View.findDialogWindow(): Window? {
    if (this is DialogWindowProvider) return window
    var node: ViewParent? = this.parent
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = node.parent
    }
    return null
}
