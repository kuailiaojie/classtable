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
}

/** 供 Material3 底层组件使用;可见外观全部由 YohakuType 控制。 */
val YohakuMaterialTypography = Typography()
