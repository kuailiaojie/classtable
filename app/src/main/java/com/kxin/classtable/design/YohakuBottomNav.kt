package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 底部胶囊导航栏:选中项 accent 实底胶囊。 */
@Composable
fun YohakuBottomNav(
    current: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val items = listOf(
        "week" to "周视图",
        "day" to "日视图",
        "courses" to "课程",
        "import" to "导入",
        "settings" to "设置",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.neutral2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { (route, label) ->
            val selected = current == route
            Text(
                text = label,
                style = YohakuType.label12,
                color = if (selected) Color.White else colors.neutral7,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable { onNavigate(route) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
