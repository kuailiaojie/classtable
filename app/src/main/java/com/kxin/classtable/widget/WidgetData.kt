package com.kxin.classtable.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.data.settingsDataStore
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.Course
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalTime

/**
 * 小组件数据源:同步读取 Room + 设置(作息 / 学期 / 调休)。
 *
 * 「某一天上哪些课」一律走 [Adjustments.teachingDay] —— 与周视图、今日课表同一条规则。
 * 于是调休之后:停课日小组件显示「今天停课」,补课日照**原课程日期**那天的课显示。
 */
object WidgetData {

    private data class WidgetSettings(
        val periods: List<Schedule.Period>,
        val semesterStart: Long,
        val semesterWeeks: Int,
        val adjustments: List<ScheduleAdjustment>,
    )

    private fun settingsOf(context: Context): WidgetSettings = runBlocking {
        val p = context.settingsDataStore.data.first()
        WidgetSettings(
            periods = Schedule.parsePeriods(
                p[stringPreferencesKey("period_times")] ?: Schedule.DEFAULT_PERIODS,
            ),
            semesterStart = p[longPreferencesKey("semester_start_day")] ?: 0L,
            semesterWeeks = p[intPreferencesKey("semester_week_count")] ?: 20,
            adjustments = Adjustments.decode(p[stringPreferencesKey("schedule_adjustments")] ?: ""),
        )
    }

    private fun WidgetSettings.teachingOn(date: LocalDate): Pair<Int, Int>? =
        Adjustments.teachingDay(adjustments, date, semesterStart, semesterWeeks)

    private fun coursesOf(context: Context, settings: WidgetSettings, date: LocalDate): List<Course> {
        val teaching = settings.teachingOn(date) ?: return emptyList()
        return runBlocking {
            AppDatabase.get(context).courseDao().getAll()
                .map { it.toDomain() }
                .filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
                .sortedBy { it.customStartMinute ?: settings.periods.getOrNull(it.startPeriod - 1)?.start ?: 0 }
        }
    }

    fun periods(context: Context): List<Schedule.Period> = settingsOf(context).periods

    /** 今天要上的课(已考虑调休;停课返回空表)。 */
    fun todayCourses(context: Context): List<Course> {
        val s = settingsOf(context)
        return coursesOf(context, s, LocalDate.now())
    }

    /** 明天要上的课。 */
    fun tomorrowCourses(context: Context): List<Course> {
        val s = settingsOf(context)
        return coursesOf(context, s, LocalDate.now().plusDays(1))
    }

    /** 今天是否因调休停课(界面要区分「停课」与「本来就没课」)。 */
    fun todayIsRest(context: Context): Boolean = isRestOn(context, LocalDate.now())

    /** 明天是否因调休停课。 */
    fun tomorrowIsRest(context: Context): Boolean = isRestOn(context, LocalDate.now().plusDays(1))

    private fun isRestOn(context: Context, date: LocalDate): Boolean =
        Adjustments.resolve(settingsOf(context).adjustments, date).rest

    /** 这天的「周几 · 第几周」,小组件表头用。 */
    fun header(context: Context, date: LocalDate): String {
        val s = settingsOf(context)
        val week = Schedule.weekOf(date.toEpochDay(), s.semesterStart, s.semesterWeeks)
        val weekday = "一二三四五六日"[date.dayOfWeek.value - 1]
        val rest = Adjustments.resolve(s.adjustments, date).rest
        return "周$weekday · 第 $week 周" + if (rest) " · 停课" else ""
    }

    /** 今天是否上课中(accent 用)。 */
    fun currentCourseToday(context: Context): Course? {
        val s = settingsOf(context)
        return coursesOf(context, s, LocalDate.now())
            .firstOrNull { Schedule.isCourseOngoing(it, s.periods) }
    }

    /**
     * 1×1:下节课 = 今天最早一节「还没上完」的课。
     *
     * 按课程自己的时间算:曾经用 `2 × 当前大节 - 1` 反推节次,那是「2 小节 = 1 大节」的假设,
     * 遇到作息本身就是大节的学校会把已经上过的课当成「下节课」。
     */
    fun nextCourse(context: Context): Course? {
        val s = settingsOf(context)
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return coursesOf(context, s, LocalDate.now())
            .mapNotNull { c ->
                val start = c.customStartMinute ?: s.periods.getOrNull(c.startPeriod - 1)?.start
                val end = c.customEndMinute ?: s.periods.getOrNull(c.endPeriod - 1)?.end
                if (start == null || end == null || end <= now) null else c to start
            }
            .minByOrNull { it.second }
            ?.first
    }
}
