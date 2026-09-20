package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 悬浮底部导航:一块**不贴边**的纸面控件浮在内容之上,宽度只包住四个标签,
 * 由调用方用 [androidx.compose.foundation.layout.Box] + `align(BottomCenter)` 摆放,
 * 并给内容预留 [YohakuDimens.navReservedHeight],避免遮住最后一屏内容。
 *
 * 选中态用「accent 文字 + 下方一个小圆点」表示此刻在这里 —— 沿用「色不承载语义」的约定:
 * 不加实底胶囊、不加阴影,同档表面之间靠 n-5 细边框分隔(和卡片同一套语言)。
 */
@Composable
fun YohakuBottomNav(
    current: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusNav)
    val items = listOf(
        "week" to "周视图",
        "day" to "日视图",
        "courses" to "课程",
        "settings" to "设置",
    )
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.neutral2)
            .border(1.dp, colors.neutral5, shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (route, label) ->
            val selected = current == route
            Column(
                modifier = Modifier
                    .width(YohakuDimens.navItemWidth)
                    .clip(RoundedCornerShape(YohakuDimens.radiusControl))
                    .clickable { onNavigate(route) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = YohakuType.copy13,
                    color = if (selected) colors.accent else colors.neutral7,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.accent else Color.Transparent),
                )
            }
        }
    }
}
