package com.kxin.classtable.data.yuketang

import com.kxin.classtable.domain.model.Course

/**
 * App 课程 ↔ 雨课堂班级的匹配。
 *
 * 两侧的「名字」含义并不对称:App 课程的 `name` 是**课程名**(「计算机网络」),
 * 而雨课堂返回的 `name` 是**班级名**(「21计算机类地方本科班」)、`courseName` 才是课程名。
 * 所以课程名优先比对 `courseName`,班级名只作次要依据;教师名相同时加权消歧。
 *
 * 得分最高的候选**只有唯一一个**时才自动绑定;并列时返回候选列表交给用户确认
 * (参考文档要求:同名课程命中多个 classroom_id 必须向用户展示候选并确认)。
 */
object YuketangMatcher {

    /**
     * @param best 唯一最优候选;并列或全无命中时为 null(需要人工选)。
     * @param candidates 命中正分的候选,按得分降序(供选择列表排序用)。
     */
    data class CourseMatch(
        val courseId: String,
        val best: YuketangCourse?,
        val candidates: List<YuketangCourse>,
    )

    fun match(courses: List<Course>, classrooms: List<YuketangCourse>): List<CourseMatch> =
        courses.map { course ->
            val scored = classrooms
                .map { it to score(course, it) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            val top = scored.firstOrNull()?.second ?: 0
            val topCandidates = scored.filter { it.second == top }.map { it.first }
            CourseMatch(
                courseId = course.id,
                best = topCandidates.singleOrNull(),
                candidates = scored.map { it.first },
            )
        }

    /** 0 分表示不相关;分数只用于排序与并列判断,没有绝对意义。 */
    fun score(course: Course, classroom: YuketangCourse): Int {
        val appName = normalize(course.name)
        if (appName.isEmpty()) return 0
        var score = maxOf(
            nameScore(appName, normalize(classroom.courseName), exact = 100, partial = 70),
            nameScore(appName, normalize(classroom.name), exact = 90, partial = 40),
        )
        if (score > 0 &&
            course.teacher.isNotBlank() &&
            classroom.teacherName.isNotBlank() &&
            normalize(course.teacher) == normalize(classroom.teacherName)
        ) {
            score += 15
        }
        return score
    }

    private fun nameScore(appName: String, target: String, exact: Int, partial: Int): Int = when {
        target.isEmpty() -> 0
        appName == target -> exact
        target.contains(appName) || appName.contains(target) -> partial
        else -> 0
    }

    /** 去掉空白与中英文标点,只留实义字符。 */
    private fun normalize(value: String): String =
        value.lowercase().replace(NORMALIZE_REGEX, "")

    private val NORMALIZE_REGEX = Regex(
        "[\\s\\p{Punct}·—–－、，。：；！？（）()【】\\[\\]《》<>\"'’‘“”…]+",
    )
}
