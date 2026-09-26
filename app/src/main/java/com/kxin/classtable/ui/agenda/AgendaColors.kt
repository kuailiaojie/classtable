package com.kxin.classtable.ui.agenda

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.kxin.classtable.design.CoursePalette
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.domain.model.AgendaCategory

/**
 * 日程分类的点缀色。
 *
 * 直接复用课程淡彩那一套(以分类名为 seed 派生色相):分类色**不承载语义**,只做扫读辅助,
 * 与课表里的课程色是同一套语言;accent 仍然只表示「此刻」。
 */
@Composable
internal fun categoryTint(category: AgendaCategory): Color {
    val colors = LocalYohakuColors.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    return remember(category, dark) { CoursePalette.tint(category.label, dark) }
}

/** 分类色标(列表小圆点 / 色条)。 */
@Composable
internal fun categoryMark(category: AgendaCategory): Color {
    val colors = LocalYohakuColors.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    return remember(category, dark) { CoursePalette.mark(category.label, dark) }
}
