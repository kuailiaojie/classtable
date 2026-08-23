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

    val accentBarWidth = 4.dp

    /** 周视图单节行高(固定等高,整整齐齐;自定义时间课程跨多行) */
    val gridRowHeight = 88.dp
    /** 周视图左侧节次标签宽度(第 N 节 + 时间区间) */
    val gridPeriodLabelWidth = 76.dp
}
