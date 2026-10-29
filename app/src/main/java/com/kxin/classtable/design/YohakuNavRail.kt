package com.kxin.classtable.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 悬浮竖向导航栏 —— 大屏(折叠屏展开 / 平板)时的导航形态。
 *
 * 与 [YohakuBottomNav] 是同一枚胶囊的两种朝向:共用 [YohakuRootTabs]、[YohakuNavItem]
 * 的选中态(accent 文字 + 小圆点)、触感与缓动。区别只有两点:
 *
 * 1) **贴在起始侧竖着排** —— 宽屏上屏幕底部离拇指太远,导航应该落在更容易够到的那条边上;
 * 2) **内容从它右侧起始** —— 调用方在根标签页内容区预留 [YohakuDimens.navRailReservedWidth],
 *    内容整体右移,而不是被栏压住(窄屏底栏则允许内容从栏下穿过,这是两者的分工)。
 */
@Composable
fun YohakuNavRail(
    current: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = colors.raised,
        border = BorderStroke(1.dp, colors.line),
        shadowElevation = YohakuDimens.navElevation,
    ) {
        Column(
            // 与底栏镜像:底栏靠「上下收薄」显得像一条胶囊,rail 则收左右
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            YohakuRootTabs.forEach { (route, label) ->
                YohakuNavItem(
                    label = label,
                    selected = current == route,
                    onClick = { onNavigate(route) },
                    width = YohakuDimens.navRailItemWidth,
                )
            }
        }
    }
}
