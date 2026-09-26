package com.kxin.classtable.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 单选 chip。选中态 = accent 实底 + 白字(清晰可辨)。
 *
 * 自绘而非 Material `Surface(onClick)` 包装:那样会自带 M3 涟漪与默认表面语义;
 * 现在用 `selectable` 表达「已选中」状态,读屏能报出选中与否。
 *
 * 底色 / 描边 / 文字三者的切换都带缓动 —— 选中时不再是一帧之内换掉。
 *
 * chip 默认按文字**自适应宽度**,文字居中(等宽时无差别);需要成列对齐时由调用方
 * 传 `Modifier.widthIn(min = ...)` 统一宽度(见课程编辑页的逐周勾选)。
 */
@Composable
fun YohakuChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusChip)
    val spec = YohakuMotion.tween<Color>(YohakuMotion.durBase)
    val background by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.raised,
        animationSpec = spec,
        label = "chipBackground",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.line,
        animationSpec = spec,
        label = "chipOutline",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else colors.neutral7,
        animationSpec = spec,
        label = "chipContent",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            style = YohakuType.label12,
            color = contentColor,
        )
    }
}
