package com.kxin.classtable.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType

/** 卡片内左右内边距:与卡片外的小标题对齐。 */
private val rowHPadding = 14.dp

/**
 * 设置分组:小标题 + 一张纸面卡片。
 * 设置项按语义分组后,同组同卡、组间距大、组内行间距小 —— 层级靠留白表达,
 * 不靠底色块或分割线堆叠。
 */
@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalYohakuColors.current
    Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
        Text(
            text = title,
            style = YohakuType.label12,
            color = colors.neutral7,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(YohakuDimens.radiusSheet),
            color = colors.neutral2,
            border = BorderStroke(1.dp, colors.neutral5),
        ) {
            Column(modifier = Modifier.padding(vertical = 2.dp), content = content)
        }
        Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
    }
}

/** 卡片内的设置行:标题 + 当前值 + ›。 */
@Composable
internal fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = rowHPadding, vertical = 13.dp),
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

/** 卡片内的「标签 + 控件」块:主题、强调色这类需要放 chips / 色板的分组用。 */
@Composable
internal fun SettingBlock(title: String, content: @Composable () -> Unit) {
    val colors = LocalYohakuColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rowHPadding, vertical = 12.dp),
    ) {
        Text(text = title, style = YohakuType.label12, color = colors.neutral7)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

/** 卡片内的分隔线:与卡片内边距对齐,比卡片边框浅一档。 */
@Composable
internal fun DividerLine() {
    val colors = LocalYohakuColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rowHPadding)
            .height(1.dp)
            .background(colors.neutral3),
    )
}
