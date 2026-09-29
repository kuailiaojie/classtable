package com.kxin.classtable.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 顶部栏:serif 屏标题 + 可选返回 ‹ + 右侧操作。
 *
 * 返回走独立的 [BackButton]:字形仍贴左(标题的起点只往里让开一点点),但可点范围补到
 * 44dp 高、40dp 宽 —— 顶部左侧本来就离拇指最远,再把可点范围限在一个字形上,「情境先行」
 * 就没做到。
 */
@Composable
fun YohakuTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) BackButton(onBack)
        Text(
            text = title,
            style = YohakuType.title24,
            color = colors.neutral10,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * 返回 ‹:字形靠左不动,触达区向右、向上向下补足。
 *
 * 按下时与全应用其它可点元素一致地回缩一点([YohakuMotion.snappySpring]);回缩以左边为
 * 原点,形状只往右侧收,不会拱到左边那道系统返回手势的边上。
 */
@Composable
private fun BackButton(onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = YohakuMotion.snappySpring(),
        label = "backScale",
    )
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 44.dp)
            .clip(RoundedCornerShape(YohakuDimens.radiusControl))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = "‹", style = YohakuType.title28, color = colors.neutral9)
    }
}
