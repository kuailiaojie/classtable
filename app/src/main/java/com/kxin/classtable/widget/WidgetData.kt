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

/** 小组件数据源:同步读取 Room + 设置(当前周/作息/学期)。 */
object WidgetData {

    private data class WidgetSettings(
        val week: Int,
        val periods: List<Schedule.Period>,
        val semesterStart: Long,
        val semesterWeeks: Int,
    )

    private fun settingsOf(context: Context): WidgetSettings = runBlocking {
        val p = context.settingsDataStore.data.first()
        WidgetSettings(
            week = p[intPreferencesKey("current_week")] ?: 1,
            periods = Schedule.parsePeriods(
                p[stringPreferencesKey("period_times")] ?: Schedule.DEFAULT_PERIODS,
            ),
            semesterStart = p[longPreferencesKey("semester_start_day")] ?: 0L,
            semesterWeeks = p[intPreferencesKey("semester_week_count")] ?: 20,
        )
    }

    fun periods(context: Context): List<Schedule.Period> = settingsOf(context).periods

    /** 1×1:下节课 = 今天最早一节「还没开始/正在上」的课。 */
    fun nextCourse(context: Context): Course? {
        val s = settingsOf(context)
        val today = Schedule.todayWeekday()
        val nextStart = 2 * Schedule.currentBigPeriodIndex(s.periods) - 1
        return runBlocking {
            AppDatabase.get(context).courseDao().getAll()
                .map { it.toDomain() }
                .filter {
                    it.isOnWeekday(today) && it.isActiveOnWeek(s.week) && it.startPeriod >= nextStart
                }
                .minByOrNull { it.startPeriod }
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

    /** 当前大节内正在上的课(小组件 accent 用)。 */
    fun currentCourseToday(context: Context): Course? {
        val s = settingsOf(context)
        val today = Schedule.todayWeekday()
        val big = Schedule.bigPeriods(s.periods.size)
            .getOrNull(Schedule.currentBigPeriodIndex(s.periods) - 1) ?: return null
        return runBlocking {
            AppDatabase.get(context).courseDao().getAll()
                .map { it.toDomain() }
                .filter {
                    it.isOnWeekday(today) && it.isActiveOnWeek(s.week) &&
                        Schedule.courseOverlapsBigPeriod(it, big.first, big.second, s.periods)
                }
                .firstOrNull()
        }
    }
}
