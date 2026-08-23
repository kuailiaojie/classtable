package com.kxin.classtable.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 纸面卡片:n-2 面 + n-5 细边框(同档表面必须分隔,禁硬阴影)。
 * accentBar=true 时左侧 4px accent 条——仅用于「当前课程/当前时段」。
 */
@Composable
fun YohakuCard(
    modifier: Modifier = Modifier,
    accentBar: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalYohakuColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(YohakuDimens.radiusCard),
        color = colors.neutral2,
        border = BorderStroke(1.dp, colors.neutral5),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (accentBar) {
                Box(
                    modifier = Modifier
                        .width(YohakuDimens.accentBarWidth)
                        .fillMaxHeight()
                        .background(colors.accent),
                )
            }
            Column(modifier = Modifier.padding(YohakuDimens.cardPadding), content = content)
        }
    }
}
