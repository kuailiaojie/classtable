package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType

/** 设置列表行:标题 + 右侧值 + ›。设置页各子页共用。 */
@Composable
internal fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = YohakuDimens.screenPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = YohakuType.copy15,
            color = colors.neutral9,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = YohakuType.copy13, color = colors.neutral7)
        Text(
            text = "›",
            style = YohakuType.copy15,
            color = colors.neutral6,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 设置列表分隔线(与 screenPadding 对齐)。 */
@Composable
internal fun DividerLine() {
    val colors = LocalYohakuColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.screenPadding)
            .height(1.dp)
            .background(colors.neutral3),
    )
}
