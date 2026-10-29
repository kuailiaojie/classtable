package com.kxin.classtable.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.data.settingsDataStore
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Dates
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.AgendaEvent
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
 *
 * 每个小组件只需一次 [snapshot]:设置、全部课程、日程各读一遍,再从中派生自己那几项,
 * 而不是像以前那样每个字段各读一遍 DataStore / Room(一个小组件一帧能读 4 次设置 + 3 次库)。
 */
object WidgetData {

    internal data class WidgetSettings(
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

    /** 一次读取设置 + 全部课程 + 日程,供单个小组件在一帧内派生全部需要的数据。 */
    fun snapshot(context: Context): WidgetSnapshot {
        val settings = settingsOf(context)
        val db = AppDatabase.get(context)
        val courses = runBlocking { db.courseDao().getAll().map { it.toDomain() } }
        val agenda = runBlocking { db.agendaDao().getAll().map { it.toDomain() } }
        return WidgetSnapshot(settings, courses, agenda)
    }

    /** 小组件的一帧数据快照。 */
    class WidgetSnapshot internal constructor(
        private val settings: WidgetSettings,
        private val allCourses: List<Course>,
        private val allAgenda: List<AgendaEvent>,
    ) {
        val periods: List<Schedule.Period> get() = settings.periods

        private fun teachingOn(date: LocalDate): Pair<Int, Int>? =
            Adjustments.teachingDay(settings.adjustments, date, settings.semesterStart, settings.semesterWeeks)

        /** 某天要上的课(已考虑调休;停课返回空表),按开始时间排序。 */
        fun coursesOn(date: LocalDate): List<Course> {
            val teaching = teachingOn(date) ?: return emptyList()
            return allCourses
                .filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
                .sortedBy { it.customStartMinute ?: settings.periods.getOrNull(it.startPeriod - 1)?.start ?: 0 }
        }

        /** 某天是否因调休停课(界面要区分「停课」与「本来就没课」)。 */
        fun isRestOn(date: LocalDate): Boolean =
            Adjustments.resolve(settings.adjustments, date).rest

        /** 这天的「周几 · 第几周」,小组件表头用。 */
        fun header(date: LocalDate): String {
            val week = Schedule.weekOf(date.toEpochDay(), settings.semesterStart, settings.semesterWeeks)
            val weekday = Dates.weekdayChar(date.dayOfWeek.value)
            val rest = isRestOn(date)
            return "周$weekday · 第 $week 周" + if (rest) " · 停课" else ""
        }

        /** 今天是否上课中(accent 用)。 */
        fun currentCourseId(date: LocalDate): String? =
            coursesOn(date).firstOrNull { Schedule.isCourseOngoing(it, settings.periods) }?.id

        /**
         * 下节课 = 该日最早一节「还没上完」的课。
         *
         * 按课程自己的时间算:曾经用 `2 × 当前大节 - 1` 反推节次,那是「2 小节 = 1 大节」的假设,
         * 遇到作息本身就是大节的学校会把已经上过的课当成「下节课」。
         */
        fun nextCourse(date: LocalDate): Course? {
            val now = Schedule.nowMinute()
            return coursesOn(date)
                .mapNotNull { c ->
                    val start = c.customStartMinute ?: settings.periods.getOrNull(c.startPeriod - 1)?.start
                    val end = c.customEndMinute ?: settings.periods.getOrNull(c.endPeriod - 1)?.end
                    if (start == null || end == null || end <= now) null else c to start
                }
                .minByOrNull { it.second }
                ?.first
        }

        /**
         * 4×2「日程」:还没结束的日程,按开始时间升序取前 [limit] 条。
         *
         * 不分来源(日程 / 倒计时)一律按时间排 —— 小组件的一张卡里,今天的定时条目与
         * 未来几天的倒数目标自然交错,哪件最近哪件就在最上面。
         */
        fun upcomingAgenda(limit: Int): List<AgendaEvent> {
            val now = System.currentTimeMillis()
            return allAgenda
                .filter { it.endAt >= now && !it.completed }
                .sortedBy { it.startAt }
                .take(limit)
        }
    }
}
