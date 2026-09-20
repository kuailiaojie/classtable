package com.kxin.classtable.design

import androidx.compose.ui.graphics.Color

/**
 * Yohaku 三档中性 + 一抹 accent。
 * 浅色 = 暖纸面;深色 = 纯灰反转(暖意只保留在 paper)。
 */
data class YohakuColors(
    val paper: Color,
    /** 浮起面(卡片 / 弹窗 / 设置分组 / 悬浮导航):浅色下比纸面更白,深色下比纸面更亮。 */
    val raised: Color,
    /** 同档表面的**细边框**:只做「这里有一块内容」的暗示,不参与层级对比。 */
    val line: Color,
    val neutral1: Color,
    val neutral2: Color,
    val neutral3: Color,
    val neutral5: Color,
    val neutral6: Color,
    val neutral7: Color,
    val neutral8: Color,
    val neutral9: Color,
    val neutral10: Color,
    val accent: Color,
    val info: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

val YohakuLightColors = YohakuColors(
    paper = Color(0xFFFEFEFB),
    // 比纸面更白一档:卡片/弹窗/导航靠「更亮 + 细边框」浮起,而不是靠灰底压深
    // —— 之前用 neutral2(#F0EFEB)当容器,在近白纸面上就是一个个发暗的灰框。
    raised = Color(0xFFFFFFFF),
    line = Color(0xFFE7E5DF),
    neutral1 = Color(0xFFF9F8F5),
    neutral2 = Color(0xFFF0EFEB),
    neutral3 = Color(0xFFE3E1DB),
    neutral5 = Color(0xFFA8A69F),
    neutral6 = Color(0xFF787670),
    neutral7 = Color(0xFF5C5A55),
    neutral8 = Color(0xFF403F3A),
    neutral9 = Color(0xFF24231F),
    neutral10 = Color(0xFF141312),
    accent = Color(0xFFC56473), // 梅 ume
    info = Color(0xFF3D6896),   // 縹 hanada
    success = Color(0xFF5E9F7E),// 若竹 wakatake
    warning = Color(0xFFA87A3D),// 朽葉 kuchiba
    error = Color(0xFFA64953),  // 蘇芳 suoh
)

val YohakuDarkColors = YohakuColors(
    paper = Color(0xFF141414),
    // 深色下「更亮 = 更靠前」,与浅色同一套直觉;line 只比 raised 亮一点点
    raised = Color(0xFF262626),
    line = Color(0xFF3A3A3A),
    neutral1 = Color(0xFF141414),
    neutral2 = Color(0xFF242424),
    neutral3 = Color(0xFF404040),
    neutral5 = Color(0xFF787878),
    neutral6 = Color(0xFFA8A8A8),
    neutral7 = Color(0xFFD0D0D0),
    neutral8 = Color(0xFFE3E3E3),
    neutral9 = Color(0xFFF0F0F0),
    neutral10 = Color(0xFFF8F8F8),
    accent = Color(0xFFE095A4), // 桃(深色下梅的提亮)
    info = Color(0xFF6E93C0),
    success = Color(0xFF7FB79A),
    warning = Color(0xFFC59B62),
    error = Color(0xFFC06A74),
)

/** 设置页可选的 5 个和色 accent(克制,不开放自由取色)。 */
val AccentOptions = listOf(
    "梅" to "#C56473",
    "縹" to "#3D6896",
    "若竹" to "#5E9F7E",
    "朽葉" to "#A87A3D",
    "蘇芳" to "#A64953",
)

fun accentColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }
        .getOrDefault(Color(0xFFC56473))
