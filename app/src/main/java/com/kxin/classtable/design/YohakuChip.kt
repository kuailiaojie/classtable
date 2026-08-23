package com.kxin.classtable.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 单选 chip。选中态 = accent 实底 + 白字(清晰可辨)。
 */
@Composable
fun YohakuChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(YohakuDimens.radiusChip),
        color = if (selected) colors.accent else colors.neutral2,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.neutral5),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = YohakuType.label12,
            color = if (selected) Color.White else colors.neutral7,
        )
    }
}
