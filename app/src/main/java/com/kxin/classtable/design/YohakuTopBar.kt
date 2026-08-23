package com.kxin.classtable.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 顶部栏:serif 屏标题 + 可选返回 ‹ + 右侧操作。 */
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
        if (onBack != null) {
            Text(
                text = "‹",
                style = YohakuType.title28,
                color = colors.neutral9,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 12.dp),
            )
        }
        Text(
            text = title,
            style = YohakuType.title24,
            color = colors.neutral10,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
