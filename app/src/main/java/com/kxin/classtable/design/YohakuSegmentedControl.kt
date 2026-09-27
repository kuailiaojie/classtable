package com.kxin.classtable.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 分段控件:一条底槽 + 一段会滑动的实底指示块。
 *
 * 与 [YohakuChip] 的区别:chip 是「若干可多选的标签」,分段控件表达的是「同一处的几个互斥视图」——
 * 用它来切「日程 / 倒计时」,选中指示块滑动过去,而不是两个各自变色的 chip。
 */
@Composable
fun YohakuSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusControl)
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase, YohakuMotion.easeOut),
        label = "segmentedPosition",
    )
    BoxWithConstraints(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(colors.neutral2)
            .border(1.dp, colors.line, shape)
            .padding(2.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val segmentWidth = maxWidth / count
        val segmentShape = RoundedCornerShape(YohakuDimens.radiusChip)
        Box(
            modifier = Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = position * segmentWidth.toPx() }
                .clip(segmentShape)
                .background(colors.accent),
        )
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val contentColor by animateColorAsState(
                    targetValue = if (selected) Color.White else colors.neutral7,
                    animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
                    label = "segmentedContent",
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clip(segmentShape)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = label, style = YohakuType.label12, color = contentColor)
                }
            }
        }
    }
}
