package com.kxin.classtable.domain.model

/**
 * 自动课程配色方案:决定按课名派生色相时使用多少个和色色相。
 *
 * 色相越多,不同课程撞到同一个颜色的概率越低。每个色相仍只做一层**极淡的纸面色差**,
 * 颜色不承载语义(课程名始终写在上面),与 Yohaku「课程淡彩」的取向一致。
 */
enum class CourseColorScheme(val label: String, val description: String) {
    SOFT("柔和", "8 色 · 最克制"),
    STANDARD("标准", "12 色 · 日常够用"),
    RICH("丰富", "16 色 · 最不易撞色"),
    ;

    companion object {
        fun of(name: String?): CourseColorScheme =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
