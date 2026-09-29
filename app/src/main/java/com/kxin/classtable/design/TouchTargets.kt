package com.kxin.classtable.design

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 命中区兜底:把可点范围补到至少 44dp 高,而**不改变视觉**。
 *
 * 顶栏「选择 / 取消」、详情页「编辑 / 删除」这类纯文字操作,可点范围一直就等于字形大小
 * (约 20–36dp);顶部与角落本来就离拇指最远,再把可点范围限在字形上,「情境先行」就没做到。
 *
 * 用法:把它放在 `.clickable { }` **之前**,可点区域才随最小尺寸一起放大:
 * ```
 * Modifier.yohakuTouchTarget().clickable { ... }.padding(...)
 * ```
 * 横向通常已有内边距,所以 [minWidth] 默认 0,只兜高度。
 */
fun Modifier.yohakuTouchTarget(minWidth: Dp = 0.dp, minHeight: Dp = 44.dp): Modifier =
    defaultMinSize(minWidth = minWidth, minHeight = minHeight)
