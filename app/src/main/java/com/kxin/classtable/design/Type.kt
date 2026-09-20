package com.kxin.classtable.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kxin.classtable.R

object YohakuFonts {
    // 内置字体(已放入 res/font):思源宋体 Medium + JetBrains Mono Regular
    val Serif: FontFamily = FontFamily(Font(R.font.noto_serif_sc_medium))
    val Sans: FontFamily = FontFamily.Default
    val Mono: FontFamily = FontFamily(Font(R.font.jetbrains_mono_regular))
}

/** 角色 + px 字阶。CJK 标题一律 Medium(500),禁合成粗体。 */
object YohakuType {
    val label12 = TextStyle(fontFamily = YohakuFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)
    val copy13 = TextStyle(fontFamily = YohakuFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp)
    val copy14 = TextStyle(fontFamily = YohakuFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp)
    val copy15 = TextStyle(fontFamily = YohakuFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp)
    val copy16 = TextStyle(fontFamily = YohakuFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
    val title20 = TextStyle(fontFamily = YohakuFonts.Serif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp)
    val title24 = TextStyle(fontFamily = YohakuFonts.Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp)
    val title28 = TextStyle(fontFamily = YohakuFonts.Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp)

    /** 课程名:衬线,纸感 */
    val courseName = TextStyle(fontFamily = YohakuFonts.Serif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)

    /** 周视图单元格内课程名(小号) */
    val cellName = TextStyle(fontFamily = YohakuFonts.Serif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)

    /** 周视图单元格内时间/教室(小号等宽) */
    val cellTime = TextStyle(fontFamily = YohakuFonts.Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp)

    /** 时间/节次:等宽,表格数字对齐 */
    val timeMono = TextStyle(fontFamily = YohakuFonts.Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)

    // 周视图七天同屏后格子很窄,以下字号比 cellName/cellTime 再小一档。
    /** 周视图网格内课程名 */
    val gridName = TextStyle(fontFamily = YohakuFonts.Serif, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp)
    /** 周视图网格内教室 */
    val gridMeta = TextStyle(fontFamily = YohakuFonts.Mono, fontWeight = FontWeight.Normal, fontSize = 8.sp, lineHeight = 11.sp)
    /** 周视图左侧节次留白列:节号 */
    val gridGutter = TextStyle(fontFamily = YohakuFonts.Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 12.sp)
    /** 周视图左侧节次留白列:起始时间 */
    val gridGutterTime = TextStyle(fontFamily = YohakuFonts.Mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 13.sp)
    /** 周视图表头星期 */
    val gridWeekday = TextStyle(fontFamily = YohakuFonts.Serif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    /** 周视图表头日期 */
    val gridDate = TextStyle(fontFamily = YohakuFonts.Mono, fontWeight = FontWeight.Normal, fontSize = 9.sp, lineHeight = 12.sp)
}

/**
 * 交给 Material3 的字阶映射:让**仍在使用 Material 组件的槽位**也落在 Yohaku 字阶上。
 * 之前这里是空的 `Typography()`,于是对话框标题是 24sp Roboto、正文是 14sp Roboto ——
 * 明明每个 `Text` 都传了 YohakuType,容器内部槽位却是 Material 的。
 */
val YohakuMaterialTypography = Typography(
    headlineSmall = YohakuType.title24,
    headlineMedium = YohakuType.title28,
    titleLarge = YohakuType.title20,
    titleMedium = YohakuType.copy16,
    titleSmall = YohakuType.copy14,
    bodyLarge = YohakuType.copy16,
    bodyMedium = YohakuType.copy14,
    bodySmall = YohakuType.label12,
    labelLarge = YohakuType.copy13,
    labelMedium = YohakuType.label12,
    labelSmall = YohakuType.label12,
)
