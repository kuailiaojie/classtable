package com.kxin.classtable.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.CourseColorScheme

/**
 * 课程淡彩。
 *
 * Yohaku 的规矩是「一抹 accent + 三档中性」,accent 只属于「当前这一刻」;因此这里**不引入课程分类色**,
 * 而是让每门课在纸面上有一层极淡的色差,便于在一屏七天里快速扫读。颜色是确定性派生的
 * (同一门课永远同色),由文字承载语义,所以灰度/色盲下信息不丢。
 *
 * 色相池由 [CourseColorScheme] 决定(8 / 12 / 16 个和色色相):色相越多,不同课程越不容易撞色。
 */
object CoursePalette {
    /** 适合课程卡片的预设色；用户也可以在课程编辑页输入任意 #RRGGBB。 */
    val PRESETS = listOf(
        "梅" to "#C56473",
        "蘇芳" to "#A64953",
        "茜" to "#B25055",
        "桜" to "#D89AA6",
        "牡丹" to "#C07A9B",
        "藤" to "#9275B5",
        "桔梗" to "#6B6FA8",
        "群青" to "#4A6FA5",
        "縹" to "#5D83B2",
        "空色" to "#6A96C7",
        "浅葱" to "#5D9EA1",
        "若竹" to "#65A487",
        "常磐" to "#4E8C6A",
        "苗" to "#86A85B",
        "萌黄" to "#A8B84E",
        "山吹" to "#D9A94E",
        "朽葉" to "#C09455",
        "蜜柑" to "#D98A4E",
        "灰青" to "#788B9A",
        "銀鼠" to "#A3A8AC",
    )

    /** 各配色方案使用的和色色相(0–360°),在色相环上铺开。 */
    private val SOFT_HUES = listOf(345f, 210f, 155f, 35f, 5f, 275f, 190f, 95f)
    private val STANDARD_HUES =
        listOf(350f, 20f, 50f, 80f, 110f, 140f, 170f, 200f, 230f, 260f, 290f, 320f)
    private val RICH_HUES = listOf(
        350f, 5f, 25f, 42f, 72f, 95f, 130f, 155f, 170f, 190f, 210f, 225f, 245f, 275f, 310f, 330f,
    )

    private fun huesOf(scheme: CourseColorScheme): List<Float> = when (scheme) {
        CourseColorScheme.SOFT -> SOFT_HUES
        CourseColorScheme.STANDARD -> STANDARD_HUES
        CourseColorScheme.RICH -> RICH_HUES
    }

    /** 淡彩的彩度系数:柔和档更淡,丰富档稍显 —— 都仍压在极淡的档位内。 */
    private fun saturationOf(scheme: CourseColorScheme): Float = when (scheme) {
        CourseColorScheme.SOFT -> 0.85f
        CourseColorScheme.STANDARD -> 1f
        CourseColorScheme.RICH -> 1.1f
    }

    /** 以课程名取色:同名课(多天重复上)同色,便于识别。 */
    fun seedOf(course: Course): String = course.name.ifBlank { course.id }

    private fun hueOf(seed: String, scheme: CourseColorScheme): Float {
        val hues = huesOf(scheme)
        return hues[Math.floorMod(seed.hashCode(), hues.size)]
    }

    /** 课程块/卡片底色:浅色主题 = 高明度淡彩,深色主题 = 低明度淡彩。 */
    fun tint(
        seed: String,
        dark: Boolean,
        scheme: CourseColorScheme = CourseColorScheme.STANDARD,
    ): Color {
        val hue = hueOf(seed, scheme)
        val s = saturationOf(scheme)
        return if (dark) Color.hsl(hue, 0.16f * s, 0.22f) else Color.hsl(hue, 0.30f * s, 0.955f)
    }

    /** 列表色条 / 详情小色块:比 [tint] 明确一档,但仍压低彩度。 */
    fun mark(
        seed: String,
        dark: Boolean,
        scheme: CourseColorScheme = CourseColorScheme.STANDARD,
    ): Color {
        val hue = hueOf(seed, scheme)
        val s = saturationOf(scheme)
        return if (dark) Color.hsl(hue, 0.30f * s, 0.44f) else Color.hsl(hue, 0.40f * s, 0.62f)
    }

    /** 由当前主题的纸面色判断深浅(纸面够亮 = 浅色主题)。 */
    fun isDarkTheme(paper: Color): Boolean = paper.luminance() < 0.5f
}

/** 当前生效的课程配色方案:由 [YohakuTheme] 提供,改设置后所有课程色一起更新。 */
val LocalCourseColorScheme = compositionLocalOf { CourseColorScheme.STANDARD }

/** 课程淡彩(底色)。 */
@Composable
fun courseTint(course: Course): Color {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val seed = CoursePalette.seedOf(course)
    return remember(seed, dark, course.colorHex, scheme) {
        course.colorHex.toColorOrNull()?.let { base ->
            if (dark) base.copy(alpha = 0.28f) else base.copy(alpha = 0.12f)
        } ?: CoursePalette.tint(seed, dark, scheme)
    }
}

/** 课程色标(列表色条 / 详情色块)。 */
@Composable
fun courseMark(course: Course): Color {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val seed = CoursePalette.seedOf(course)
    return remember(seed, dark, course.colorHex, scheme) {
        course.colorHex.toColorOrNull()?.let { base ->
            if (dark) base.copy(alpha = 0.9f) else base.copy(alpha = 0.72f)
        } ?: CoursePalette.mark(seed, dark, scheme)
    }
}

private fun String.toColorOrNull(): Color? =
    runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrNull()
