package com.kxin.classtable.domain.model

/**
 * 自动课程配色方案:色相环上一共铺开几个色相,以及淡彩的彩度强弱。
 *
 * 色相数就是「容量」——能有多少门课各自拿到一个不同的颜色。分配是**互不重复**的
 * (见 `CourseHuePlanner`),所以只有课程数超过色相数时才会真的两门同色。
 * 色相越少,相邻两色分得越开;色相越多,能排下的课程越多、彩度也更显。
 *
 * 颜色不承载语义(课程名始终写在课程块上),它只用来在一屏七天里快速扫读。
 */
enum class CourseColorScheme(
    val label: String,
    /** 色相环上的色相个数,也是这套配色能排下的不同颜色的上限。 */
    val hueCount: Int,
    val description: String,
) {
    SOFT("柔和", 8, "8 色 · 最淡,相邻两色分得最开"),
    STANDARD("标准", 12, "12 色 · 日常够用"),
    RICH("丰富", 16, "16 色 · 最显,能排下最多课程"),
    ;

    companion object {
        fun of(name: String?): CourseColorScheme =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
