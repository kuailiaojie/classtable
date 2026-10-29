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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 四个根标签页 —— 底栏与 rail(大屏)共用同一份,顺序即排布顺序。
 */
internal val YohakuRootTabs = listOf(
    "week" to "课表",
    "courses" to "课程",
    "agenda" to "日程",
    "settings" to "设置",
)

/** 是否宽屏(折叠屏展开 / 平板竖横屏):达到 [YohakuDimens.navWideThresholdDp] 就走 rail。 */
@Composable
fun yohakuWideScreen(): Boolean =
    LocalConfiguration.current.screenWidthDp >= YohakuDimens.navWideThresholdDp

/**
 * 根标签页内容区需要为导航预留的高度。
 *
 * 窄屏是悬浮底栏的预留量;宽屏导航换成起始侧的 rail 后底部不再有栏,预留归零。
 * 各根标签页从这里取值,而不是把 [YohakuDimens.navReservedHeight] 写死 —— 否则宽屏上
 * 最后一屏会凭空多出一截空白。
 */
@Composable
fun yohakuNavReservedHeight(): Dp =
    if (yohakuWideScreen()) 0.dp else YohakuDimens.navReservedHeight

/**
 * 悬浮底部导航。
 *
 * 「悬浮」= 三件事同时成立,缺一件就只是一个贴在底部的横条:
 * 1) **四边不贴边** —— 宽度只包住标签、水平居中,下方留出与系统手势条的间距,
 *    由调用方用 [androidx.compose.foundation.layout.Box] + `align(BottomCenter)` 摆放;
 * 2) **真的浮在内容之上** —— 用投影画出「这是一层压在最上面的平面」([YohakuDimens.navElevation]);
 * 3) **内容从它下面穿过** —— 内容区预留 [yohakuNavReservedHeight],最后一屏不被压住。
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
            YohakuRootTabs.forEach { (route, label) ->
                YohakuNavItem(
                    label = label,
                    selected = current == route,
                    onClick = { onNavigate(route) },
                )
            }
        }
    }
}

/**
 * 单个导航项:底栏与 [YohakuNavRail] 共用。
 *
 * 不绑定朝向 —— 文字 + 小圆点的竖排结构在两种栏里都一样,差别只在 [width]:
 * 底栏用 [YohakuDimens.navItemWidth],rail 用 [YohakuDimens.navRailItemWidth]。
 */
@Composable
internal fun YohakuNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    width: Dp = YohakuDimens.navItemWidth,
) {
    val colors = LocalYohakuColors.current
    // 切页签给一下轻触感 —— 这是全应用最常用的控件,此前一处触感都没有
    val haptics = rememberYohakuHaptics()
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
            .width(width)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = {
                    // 已经在这一页就不再给触感(点了没变化还震一下是骗人)
                    if (!selected) haptics.tick()
                    onClick()
                },
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 竖向留到 9dp:让整项高度够到 44dp 的触达下限
            .padding(vertical = 9.dp),
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
