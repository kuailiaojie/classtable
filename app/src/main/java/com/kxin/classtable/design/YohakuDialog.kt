package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 纸面弹窗:与卡片同一套纪律(neutral2 面 + neutral5 细边框 + radiusSheet 圆角),**不继承任何
 * Material 默认值** —— 那一套是 surfaceContainerHigh 紫调底、28dp 圆角、24sp 标题、6dp tonal 提升。
 *
 * 按钮沿用项目风格:右下角一排纯文字动作(见 [YohakuDialogAction])。
 */
@Composable
fun YohakuDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalYohakuColors.current
    Dialog(onDismissRequest = onDismissRequest) {
        val shape = RoundedCornerShape(YohakuDimens.radiusSheet)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.neutral2)
                .border(1.dp, colors.neutral5, shape)
                .padding(horizontal = YohakuDimens.screenPadding, vertical = 16.dp),
        ) {
            Column {
                if (!title.isNullOrBlank()) {
                    Text(text = title, style = YohakuType.title20, color = colors.neutral10)
                    Spacer(modifier = Modifier.height(10.dp))
                }
                content()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/** 弹窗动作:纯文字按钮。[accent] = 主操作(全屏唯一 accent 纪律)。 */
@Composable
fun YohakuDialogAction(
    text: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalYohakuColors.current
    Text(
        text = text,
        style = YohakuType.copy14,
        color = when {
            !enabled -> colors.neutral5
            accent -> colors.accent
            else -> colors.neutral7
        },
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
}
