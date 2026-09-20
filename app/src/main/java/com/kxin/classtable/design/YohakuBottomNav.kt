package com.kxin.classtable.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 悬浮底部导航。
 *
 * 「悬浮」= 三件事同时成立,缺一件就只是一个贴在底部的横条:
 * 1) **四边不贴边** —— 宽度只包住四个标签、水平居中,下方留出与系统手势条的间距,
 *    由调用方用 [androidx.compose.foundation.layout.Box] + `align(BottomCenter)` 摆放;
 * 2) **真的浮在内容之上** —— 用投影把「这是一层压在最上面的平面」画出来([YohakuDimens.navElevation])。
 *    这一点以前是缺的:栏体只有一圈中灰描边,视觉上等于把一块灰板钉在纸面上,而不是浮起来;
 * 3) **内容从它下面穿过** —— 内容区预留 [YohakuDimens.navReservedHeight],最后一屏不被压住。
 *
 * 面用浮起色(浅色下比纸面更白、深色下更亮)+ 一道细边框收边,和卡片/弹窗同一套语言:
 * 层级靠「更亮 + 投影」表达,不靠灰色块。
 *
 * 选中态仍是「accent 文字 + 下方一个小圆点」——沿用「色不承载语义」的约定,不铺实底胶囊。
 */
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
        "settings" to "设置",
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = colors.raised,
        border = BorderStroke(1.dp, colors.line),
        shadowElevation = YohakuDimens.navElevation,
    ) {
        Row(
            // 栏体已经够宽,竖向收薄:内边距越小,「悬浮的一条」越像一枚胶囊,而不是一块板
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (route, label) ->
                val selected = current == route
                Column(
                    modifier = Modifier
                        .width(YohakuDimens.navItemWidth)
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { onNavigate(route) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style = YohakuType.copy13,
                        color = if (selected) colors.accent else colors.neutral7,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
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
}
