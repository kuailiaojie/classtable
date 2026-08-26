package com.kxin.classtable.ui.permissions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuType

/**
 * 权限/可靠性行:标题 + 说明,右侧状态(已开启/未开启/建议开启)。
 * [ok] = true 已开启(accent)/ false 未开启 / null 无法自动检测(建议开启)。
 * [onClick] 非空时整行可点,跳转对应系统设置页。
 */
@Composable
fun PermissionRow(
    title: String,
    ok: Boolean?,
    hint: String,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 0.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = YohakuType.copy15, color = colors.neutral9)
            Text(text = hint, style = YohakuType.label12, color = colors.neutral6)
        }
        Text(
            text = when (ok) {
                true -> "已开启"
                false -> "未开启"
                null -> "建议开启"
            },
            style = YohakuType.label12,
            color = if (ok == true) colors.accent else colors.neutral7,
        )
        if (onClick != null) {
            Text(
                text = "›",
                style = YohakuType.copy15,
                color = colors.neutral6,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
