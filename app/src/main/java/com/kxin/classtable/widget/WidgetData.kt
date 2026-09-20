package com.kxin.classtable.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.data.settingsDataStore
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalTime

/** 小组件数据源:同步读取 Room + 设置(当前周/作息/学期)。 */
object WidgetData {

    private data class WidgetSettings(
        val periods: List<Schedule.Period>,
        val semesterStart: Long,
        val semesterWeeks: Int,
    ) {
        /** 当前周实时推算(与周视图同源),避免小组件停留在持久化旧值。 */
        val week: Int get() = Schedule.currentWeek(semesterStart, semesterWeeks)
    }

    private fun settingsOf(context: Context): WidgetSettings = runBlocking {
        val p = context.settingsDataStore.data.first()
        WidgetSettings(
            periods = Schedule.parsePeriods(
                p[stringPreferencesKey("period_times")] ?: Schedule.DEFAULT_PERIODS,
            ),
            semesterStart = p[longPreferencesKey("semester_start_day")] ?: 0L,
            semesterWeeks = p[intPreferencesKey("semester_week_count")] ?: 20,
        )
    }

    fun periods(context: Context): List<Schedule.Period> = settingsOf(context).periods

    /**
     * 1×1:下节课 = 今天最早一节「还没上完」的课。
     *
     * 按课程自己的时间算,不再用 `2 × 当前大节 - 1` 反推节次 —— 那是「2 小节 = 1 大节」的
     * 假设,遇到作息本身就是大节的学校(如长江大学 8 个 95 分钟的节)会把已经上过的课
     * 当成「下节课」。
     */
    fun nextCourse(context: Context): Course? {
        val s = settingsOf(context)
        val today = Schedule.todayWeekday()
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return runBlocking {
            AppDatabase.get(context).courseDao().getAll()
                .map { it.toDomain() }
                .filter { it.isOnWeekday(today) && it.isActiveOnWeek(s.week) }
                .mapNotNull { c ->
                    val start = c.customStartMinute ?: s.periods.getOrNull(c.startPeriod - 1)?.start
                    val end = c.customEndMinute ?: s.periods.getOrNull(c.endPeriod - 1)?.end
                    if (start == null || end == null || end <= now) null else c to start
                }
                .minByOrNull { it.second }
                ?.first
        }
    }

    /** 4×2:今日课表(按实际开始时间排序,自定义时间课程同样参与)。 */
    fun todayCourses(context: Context): List<Course> {
        val s = settingsOf(context)
        val today = Schedule.todayWeekday()
        return runBlocking {
            AppDatabase.get(context).courseDao().getAll()
                .map { it.toDomain() }
                .filter { it.isOnWeekday(today) && it.isActiveOnWeek(s.week) }
                .sortedBy { it.customStartMinute ?: s.periods.getOrNull(it.startPeriod - 1)?.start ?: 0 }
        }
    }

    /** 当前正在上的课(小组件 accent 用):同样按课程自己的时间判断,不依赖大节配对。 */
    fun currentCourseToday(context: Context): Course? {
        val s = settingsOf(context)
        val today = Schedule.todayWeekday()
        return runBlocking {
            AppDatabase.get(context).courseDao().getAll()
                .map { it.toDomain() }
                .filter {
                    it.isOnWeekday(today) && it.isActiveOnWeek(s.week) &&
                        Schedule.isCourseOngoing(it, s.periods)
                }
                .firstOrNull()
        }
    }
}
