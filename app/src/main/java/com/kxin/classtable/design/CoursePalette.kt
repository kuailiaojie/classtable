package com.kxin.classtable.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kxin.classtable.domain.CourseHuePlanner
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.CourseColorScheme

/**
 * 课程淡彩。
 *
 * Yohaku 的规矩是「一抹 accent + 三档中性」,accent 只属于「当前这一刻」;因此这里**不引入课程分类色**,
 * 而是让每门课在纸面上有一层淡彩,便于在一屏七天里快速扫读。颜色是确定性派生的(同一门课永远同色),
 * 由文字承载语义,所以灰度/色盲下信息不丢。
 *
 * 取色走 OKLCh(见 [Oklch]):**明度固定、彩度固定,只由色相区分**,于是色相环上相邻两色的感知距离
 * 处处相等 —— 「更容易区分」靠的是这个,不是单纯把颜色调浓。色相由 [CourseHuePlanner] 分配并钉在
 * 课程上(新建 / 导入时定下来,之后增删课程都不会变),所以同一张课表里也不会出现两门课同色。
 */
object CoursePalette {
    /** 适合课程卡片的预设和色；用户也可以在课程编辑页输入任意 #RRGGBB。 */
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

    /**
     * 自动淡彩的目标明度与彩度(OKLCh)。
     *
     * 彩度取在「整圈色相都还落在 sRGB 内」的上限之下,于是**没有任何色相会被色域裁掉**;
     * 一旦某个色相被裁,它与左右邻居的距离就会缩水,色相环上就出现了「有的分得开、有的几乎一样」。
     * 上限随明度变化(最窄的一段在偏蓝的色相上),所以浅色与深色各量了自己的那一对值:
     * 0.87 / 0.058 与 0.42 / 0.066 都是各自明度上「刚好不出界」的位置。
     *
     * 明度被两端夹住:浅色主题要留得住课程块上的 10sp 小字(元信息对比度 ≥ 4.5:1,实测 4.53),
     * 再浓一点就开始吃可读性;深色主题要压得住纸面,又不至于黑成一团。
     */
    private const val TintLightL = 0.87f
    private const val TintLightC = 0.058f
    private const val TintDarkL = 0.42f
    private const val TintDarkC = 0.066f

    /** 色标(列表色条 / 详情色块 / 日程圆点):比淡彩明确一档,做小面积提示用。 */
    private const val MarkLightL = 0.62f
    private const val MarkLightC = 0.095f
    private const val MarkDarkL = 0.74f
    private const val MarkDarkC = 0.110f

    /** 档位只调彩度强弱:柔和更淡、丰富更显 —— 都仍在各自的上限之内,不会出界。 */
    private fun chromaScale(scheme: CourseColorScheme): Float = when (scheme) {
        CourseColorScheme.SOFT -> 0.92f
        CourseColorScheme.STANDARD -> 1f
        CourseColorScheme.RICH -> 1.08f
    }

    /** 课程块 / 卡片底色:浅色主题 = 高明度淡彩,深色主题 = 低明度淡彩。 */
    fun tint(hue: Int, dark: Boolean, scheme: CourseColorScheme = CourseColorScheme.STANDARD): Color =
        Oklch.toColor(
            if (dark) TintDarkL else TintLightL,
            (if (dark) TintDarkC else TintLightC) * chromaScale(scheme),
            hue.toFloat(),
        )

    /** 色标:小面积使用,彩度更高一档。 */
    fun mark(hue: Int, dark: Boolean, scheme: CourseColorScheme = CourseColorScheme.STANDARD): Color =
        Oklch.toColor(
            if (dark) MarkDarkL else MarkLightL,
            (if (dark) MarkDarkC else MarkLightC) * chromaScale(scheme),
            hue.toFloat(),
        )

    /** 还没钉色相的种子(日程分类):按名字现算,同一个名字永远同色。 */
    fun tint(seed: String, dark: Boolean, scheme: CourseColorScheme = CourseColorScheme.STANDARD): Color =
        tint(CourseHuePlanner.preferred(seed, scheme), dark, scheme)

    fun mark(seed: String, dark: Boolean, scheme: CourseColorScheme = CourseColorScheme.STANDARD): Color =
        mark(CourseHuePlanner.preferred(seed, scheme), dark, scheme)

    /** 该方案铺开的整圈淡彩,按色相顺序 —— 设置页用来直观对比三档。 */
    fun swatches(scheme: CourseColorScheme, dark: Boolean): List<Color> =
        CourseHuePlanner.wheel(scheme).map { tint(it, dark, scheme) }

    /** 课程最终使用的色相:钉过的用钉住的,还没钉过的老记录退回按课名现算。 */
    fun hueOf(course: Course, scheme: CourseColorScheme): Int =
        course.colorHue ?: CourseHuePlanner.preferred(CourseHuePlanner.seedOf(course), scheme)

    /** 由当前主题的纸面色判断深浅(纸面够亮 = 浅色主题)。 */
    fun isDarkTheme(paper: Color): Boolean = paper.luminance() < 0.5f
}

/** 当前生效的课程配色方案:由 [YohakuTheme] 提供,改设置后所有课程色一起更新。 */
val LocalCourseColorScheme = compositionLocalOf { CourseColorScheme.STANDARD }

/** 课程淡彩(底色)。用户指定过颜色时以它为准。 */
@Composable
fun courseTint(course: Course): Color {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val hue = CoursePalette.hueOf(course, scheme)
    return remember(hue, dark, course.colorHex, scheme) {
        course.colorHex.toColorOrNull()?.let { base ->
            if (dark) base.copy(alpha = 0.28f) else base.copy(alpha = 0.12f)
        } ?: CoursePalette.tint(hue, dark, scheme)
    }
}

/** 课程色标(列表色条 / 详情色块)。 */
@Composable
fun courseMark(course: Course): Color {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val hue = CoursePalette.hueOf(course, scheme)
    return remember(hue, dark, course.colorHex, scheme) {
        course.colorHex.toColorOrNull()?.let { base ->
            if (dark) base.copy(alpha = 0.9f) else base.copy(alpha = 0.72f)
        } ?: CoursePalette.mark(hue, dark, scheme)
    }
}

private fun String.toColorOrNull(): Color? =
    runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrNull()
