package com.kxin.classtable.design

import androidx.compose.ui.unit.dp

/** 4dp 基。留白是信息本身——空堂就是纸面,不画占位。 */
object YohakuDimens {
    val screenPadding = 20.dp
    val cardPadding = 12.dp
    val gapTight = 8.dp
    val gapCard = 12.dp
    val gapSection = 32.dp

    val radiusChip = 4.dp
    val radiusControl = 6.dp
    val radiusCard = 8.dp
    val radiusSheet = 12.dp
    /** 悬浮底部导航:比卡片更圆,但不到胶囊(胶囊会显得像按钮组) */
    val radiusNav = 18.dp

    /** 悬浮底部导航:单项宽度(四项目定宽,间距自然均匀) */
    val navItemWidth = 64.dp
    /** 悬浮底部导航给内容预留的高度(栏高 + 上下浮动余量) */
    val navReservedHeight = 64.dp

    val accentBarWidth = 4.dp

    /** 周视图网格:行高下限(实际行高 = 可用高度 ÷ 节数,放不下时整格纵向滚动) */
    val gridMinRowHeight = 38.dp
    /** 周视图网格水平内边距(七天同屏,比 screenPadding 窄,把宽度让给列) */
    val gridPadding = 12.dp
    /** 周视图左侧节次留白列宽(只放节号与起始时间) */
    val gridGutterWidth = 28.dp
    /** 周视图课程块之间的缝隙 */
    val gridCellGap = 2.dp
    /** 周视图课程块内边距 */
    val gridBlockPadding = 3.dp
    /** 周视图课程块左侧「当前正在上」的 accent 条宽 */
    val gridAccentBarWidth = 2.dp
}
