package com.kxin.classtable.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 悬浮底部导航。
 *
 * 「悬浮」= 三件事同时成立,缺一件就只是一个贴在底部的横条:
 * 1) **四边不贴边** —— 宽度只包住标签、水平居中,下方留出与系统手势条的间距,
 *    由调用方用 [androidx.compose.foundation.layout.Box] + `align(BottomCenter)` 摆放;
 * 2) **真的浮在内容之上** —— 用投影画出「这是一层压在最上面的平面」([YohakuDimens.navElevation]);
 * 3) **内容从它下面穿过** —— 内容区预留 [YohakuDimens.navReservedHeight],最后一屏不被压住。
 *
 * 选中态:accent 文字 + 下方一个小圆点。文字颜色、小圆点、按压缩放都带缓动
 * (只动合成层),切换标签时不再硬跳。
 */
@Composable
fun YohakuBottomNav(
    current: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val items = listOf(
        "week" to "课表",
        "courses" to "课程",
        "agenda" to "日程",
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
                NavItem(
                    label = label,
                    selected = current == route,
                    onClick = { onNavigate(route) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    val labelColor by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.neutral7,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "navLabel",
    )
    // 小圆点:选中时才「长出来」
    val dot by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = YohakuMotion.snappySpring(),
        label = "navDot",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = YohakuMotion.snappySpring(),
        label = "navScale",
    )
    Column(
        modifier = Modifier
            .width(YohakuDimens.navItemWidth)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = YohakuType.copy13,
            color = labelColor,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .size(3.dp)
                .graphicsLayer {
                    scaleX = dot
                    scaleY = dot
                    alpha = dot
                }
                .clip(CircleShape)
                .background(colors.accent),
        )
    }
}
