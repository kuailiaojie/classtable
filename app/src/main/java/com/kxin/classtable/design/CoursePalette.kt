package com.kxin.classtable.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.domain.model.Course

/**
 * 课程淡彩。
 *
 * Yohaku 的规矩是「一抹 accent + 三档中性」,accent 只属于「当前这一刻」;因此这里**不引入课程分类色**,
 * 而是让每门课在纸面上有一层极淡的色差,便于在一屏七天里快速扫读。颜色是确定性派生的
 * (同一门课永远同色),由文字承载语义,所以灰度/色盲下信息不丢。
 */
object CoursePalette {
    /** 适合课程卡片的预设色；用户也可以在课程编辑页输入任意 #RRGGBB。 */
    val PRESETS = listOf(
        "梅" to "#C56473",
        "縹" to "#5D83B2",
        "若竹" to "#65A487",
        "朽葉" to "#C09455",
        "藤" to "#9275B5",
        "浅葱" to "#5D9EA1",
        "苗" to "#86A85B",
        "灰青" to "#788B9A",
    )
    /** 8 个和色色相:梅 / 縹 / 若竹 / 朽葉 / 蘇芳 / 藤 / 浅葱 / 苗 */
    private val HUES = listOf(345f, 210f, 155f, 35f, 5f, 275f, 190f, 95f)

    /** 以课程名取色:同名课(多天重复上)同色,便于识别。 */
    fun seedOf(course: Course): String = course.name.ifBlank { course.id }

    private fun hueOf(seed: String): Float = HUES[Math.floorMod(seed.hashCode(), HUES.size)]

    /** 课程块/卡片底色:浅色主题 = 高明度淡彩,深色主题 = 低明度淡彩。 */
    fun tint(seed: String, dark: Boolean): Color =
        if (dark) Color.hsl(hueOf(seed), 0.16f, 0.22f) else Color.hsl(hueOf(seed), 0.30f, 0.955f)

    /** 列表色条 / 详情小色块:比 [tint] 明确一档,但仍压低彩度。 */
    fun mark(seed: String, dark: Boolean): Color =
        if (dark) Color.hsl(hueOf(seed), 0.30f, 0.44f) else Color.hsl(hueOf(seed), 0.40f, 0.62f)

    /** 由当前主题的纸面色判断深浅(纸面够亮 = 浅色主题)。 */
    fun isDarkTheme(paper: Color): Boolean = paper.luminance() < 0.5f
}

/** 课程淡彩(底色)。 */
@Composable
fun courseTint(course: Course): Color {
    val colors = LocalYohakuColors.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val seed = CoursePalette.seedOf(course)
    return remember(seed, dark, course.colorHex) {
        course.colorHex.toColorOrNull()?.let { base ->
            if (dark) base.copy(alpha = 0.28f) else base.copy(alpha = 0.12f)
        } ?: CoursePalette.tint(seed, dark)
    }
}

/** 课程色标(列表色条 / 详情色块)。 */
@Composable
fun courseMark(course: Course): Color {
    val colors = LocalYohakuColors.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val seed = CoursePalette.seedOf(course)
    return remember(seed, dark, course.colorHex) {
        course.colorHex.toColorOrNull()?.let { base ->
            if (dark) base.copy(alpha = 0.9f) else base.copy(alpha = 0.72f)
        } ?: CoursePalette.mark(seed, dark)
    }
}

private fun String.toColorOrNull(): Color? =
    runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrNull()
