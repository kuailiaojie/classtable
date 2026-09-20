package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 */
@Composable
fun YohakuChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusChip)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.accent else colors.raised)
            .border(1.dp, if (selected) colors.accent else colors.line, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = YohakuType.label12,
            color = if (selected) Color.White else colors.neutral7,
        )
    }
}
