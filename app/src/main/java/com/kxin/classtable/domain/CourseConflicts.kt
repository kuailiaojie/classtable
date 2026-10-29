package com.kxin.classtable.domain

import com.kxin.classtable.domain.model.Course

/**
 * 课程冲突信息:与哪门课冲突、冲突的星期、重叠的周次集合与说明文案。
 */
data class CourseConflict(
    val otherCourse: Course,
    val overlappingWeekdays: List<Int>,
    val overlappingWeeks: List<Int>,
    val description: String,
)

object CourseConflicts {

    /**
     * 检测 [target] 课程是否与 [existingCourses] 中已有的其它课程发生时间与周次重合冲突。
     */
    fun findConflicts(
        target: Course,
        existingCourses: List<Course>,
        periods: List<Schedule.Period>,
        totalWeeks: Int = 20,
    ): List<CourseConflict> {
        val others = existingCourses.filter { it.id != target.id }
        val conflicts = mutableListOf<CourseConflict>()
        val targetStart = Schedule.courseStartMinute(target, periods) ?: return emptyList()
        val targetEnd = Schedule.courseEndMinute(target, periods) ?: return emptyList()
        val targetWeekdays = target.weekdaysList()

        for (other in others) {
            val commonDays = targetWeekdays.filter { other.isOnWeekday(it) }
            if (commonDays.isEmpty()) continue

            val otherStart = Schedule.courseStartMinute(other, periods) ?: continue
            val otherEnd = Schedule.courseEndMinute(other, periods) ?: continue
            val timeOverlaps = targetStart < otherEnd && targetEnd > otherStart
            if (!timeOverlaps) continue

            val commonWeeks = (1..totalWeeks.coerceAtLeast(1)).filter { w ->
                target.isActiveOnWeek(w) && other.isActiveOnWeek(w)
            }
            if (commonWeeks.isEmpty()) continue

            val daysText = commonDays.joinToString("、") { Course.weekdayName(it) }
            val weeksText = formatWeeksCompact(commonWeeks)
            val desc = "$daysText · $weeksText 与《${other.name}》时间重合"
            conflicts.add(
                CourseConflict(
                    otherCourse = other,
                    overlappingWeekdays = commonDays,
                    overlappingWeeks = commonWeeks,
                    description = desc,
                )
            )
        }
        return conflicts
    }

    private fun formatWeeksCompact(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var i = 0
        while (i < weeks.size) {
            var j = i
            while (j + 1 < weeks.size && weeks[j + 1] == weeks[j] + 1) j++
            parts += if (j > i) "${weeks[i]}-${weeks[j]}" else "${weeks[i]}"
            i = j + 1
        }
        return "第" + parts.joinToString(",") + "周"
    }
}
