package com.kxin.classtable.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.kxin.classtable.data.AiAction
import com.kxin.classtable.data.AiPlanner
import com.kxin.classtable.data.AgendaRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 把余课的课表能力以 [AppFunctions](https://developer.android.google.cn/ai/appfunctions) 的形式
 * 暴露给**设备端智能体**(系统级 MCP,Android 16+)。系统代理发现并调用这些函数来完成用户意图,
 * 例如「帮我看看今天有什么课」「周三下午加一门高数」。
 *
 * 设计约定:
 * - 只暴露**稳定的领域能力**(查询课表 / 作息 / 学期、加课、加日程、删课),不暴露 UI 细节;
 * - 复用与站内助手同一套领域逻辑([AiPlanner] / 仓库),两条入口行为一致;
 * - 参数与返回类型都被 [AppFunctionSerializable] 描述清楚,好让代理知道怎么填。
 *
 * 注:这是 Android 16 起才有的**实验性**平台能力,且需要调用方持有 EXECUTE_APP_FUNCTIONS 权限;
 * 在更低版本或未授权时,系统不会调用本服务,不影响应用其余部分。
 */
@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "ClasstableAppFunctionService",
    appFunctionXmlFileName = "classtable_app_function_service",
)
abstract class BaseClasstableAppFunctionService : AppFunctionService() {

    @Inject internal lateinit var courseRepository: CourseRepository

    @Inject internal lateinit var agendaRepository: AgendaRepository

    @Inject internal lateinit var settingsRepository: SettingsRepository

    /**
     * 列出今天要上的课。
     *
     * @return 今天(按设备当前日期与教学周、含调休补课)要上的课程,按开始时间排序。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getTodayCourses(): List<CourseSummary> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.currentSettings()
        val today = LocalDate.now()
        val week = Schedule.weekOf(today.toEpochDay(), settings.semesterStartDay, settings.semesterWeekCount)
        courseRepository.observeAll().first()
            .filter { it.isOnWeekday(today.dayOfWeek.value) && it.isActiveOnWeek(week) }
            .sortedBy { it.startPeriod }
            .map { it.toSummary(settings.periodTimes, settings.semesterWeekCount) }
    }

    /**
     * 按课程名 / 教师 / 地点关键词或星期查找课程。
     *
     * @param query 关键词,匹配课程名、教师或地点;留空表示不按关键词过滤。
     * @param weekday 限定星期,1 = 周一 … 7 = 周日;不传表示不限。
     * @return 匹配的课程;没有匹配时返回空列表。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun searchCourses(
        query: String? = null,
        weekday: Int? = null,
    ): List<CourseSummary> = withContext(Dispatchers.IO) {
        if (weekday != null && weekday !in 1..7) {
            throw AppFunctionInvalidArgumentException("weekday 必须是 1..7,收到 $weekday")
        }
        val settings = settingsRepository.currentSettings()
        val keyword = query?.trim().orEmpty()
        courseRepository.observeAll().first()
            .filter { course ->
                val hitKeyword = keyword.isEmpty() ||
                    course.name.contains(keyword, true) ||
                    course.teacher.contains(keyword, true) ||
                    course.location.contains(keyword, true)
                val hitWeekday = weekday == null || course.isOnWeekday(weekday)
                hitKeyword && hitWeekday
            }
            .sortedWith(compareBy({ it.weekday }, { it.startPeriod }))
            .map { it.toSummary(settings.periodTimes, settings.semesterWeekCount) }
    }

    /**
     * 查看某一教学周的全部课程。
     *
     * @param week 第几教学周;不传表示当前周。
     * @return 该周每天的课程,按星期与开始时间排序。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getWeekSchedule(week: Int? = null): List<CourseSummary> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.currentSettings()
        val total = settings.semesterWeekCount.coerceAtLeast(1)
        val target = week ?: Schedule.weekOf(
            LocalDate.now().toEpochDay(),
            settings.semesterStartDay,
            total,
        )
        if (target !in 1..total) {
            throw AppFunctionInvalidArgumentException("week 必须在 1..$total,收到 $target")
        }
        courseRepository.observeAll().first()
            .filter { it.isActiveOnWeek(target) }
            .sortedWith(compareBy({ it.weekday }, { it.startPeriod }))
            .map { it.toSummary(settings.periodTimes, total) }
    }

    /**
     * 查看当前作息:每一节的起止时间。
     *
     * @return 每节的序号与起止时刻,按节次升序。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getPeriods(): List<PeriodSummary> = withContext(Dispatchers.IO) {
        Schedule.parsePeriods(settingsRepository.currentSettings().periodTimes)
            .mapIndexed { index, period ->
                PeriodSummary(
                    index = index + 1,
                    start = Schedule.clockText(period.start),
                    end = Schedule.clockText(period.end),
                )
            }
    }

    /**
     * 查看学期信息:当前教学周、总周数与开学日期。
     *
     * @return 学期概况;未设置开学日期时 [SemesterSummary.startDate] 为空串。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getSemesterInfo(): SemesterSummary = withContext(Dispatchers.IO) {
        val settings = settingsRepository.currentSettings()
        val total = settings.semesterWeekCount.coerceAtLeast(1)
        SemesterSummary(
            currentWeek = Schedule.weekOf(LocalDate.now().toEpochDay(), settings.semesterStartDay, total),
            weekCount = total,
            startDate = if (settings.semesterStartDay > 0L) {
                LocalDate.ofEpochDay(settings.semesterStartDay).toString()
            } else {
                ""
            },
        )
    }

    /**
     * 添加一门课程。
     *
     * @param name 课程名,不能为空。
     * @param weekday 星期,1 = 周一 … 7 = 周日。
     * @param startSection 开始节次(从 1 开始,需在作息范围内)。
     * @param endSection 结束节次,不小于 startSection。
     * @param weeks 上课周次(字符串数组,如 ["1","3","5"]);省略表示每周。
     * @param location 上课地点,可空。
     * @param teacher 教师,可空。
     * @return 新建的课程。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun addCourse(
        name: String,
        weekday: Int,
        startSection: Int,
        endSection: Int,
        weeks: List<String>? = null,
        location: String? = null,
        teacher: String? = null,
    ): CourseSummary = withContext(Dispatchers.IO) {
        if (name.isBlank()) throw AppFunctionInvalidArgumentException("name 不能为空")
        if (weekday !in 1..7) throw AppFunctionInvalidArgumentException("weekday 必须是 1..7,收到 $weekday")
        val settings = settingsRepository.currentSettings()
        val sectionCount = Schedule.parsePeriods(settings.periodTimes).size
        if (startSection < 1 || endSection < startSection) {
            throw AppFunctionInvalidArgumentException("节次区间不合法:$startSection-$endSection")
        }
        if (sectionCount > 0 && endSection > sectionCount) {
            throw AppFunctionInvalidArgumentException("节次超出作息范围(共 $sectionCount 节)")
        }
        // AppFunctions 的参数类型里没有 List<Int>,周次以字符串数组传入,这里解析。
        val parsedWeeks = weeks.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: throw AppFunctionInvalidArgumentException("weeks 里「$it」不是整数周次") }
        val created = AiPlanner.courseDraft(
            AiAction.CreateCourse(
                name = name.trim(),
                teacher = teacher.orEmpty().trim(),
                location = location.orEmpty().trim(),
                day = weekday,
                startSection = startSection,
                endSection = endSection,
                weeks = parsedWeeks,
            ),
            settings,
        )
        courseRepository.save(created)
        created.toSummary(settings.periodTimes, settings.semesterWeekCount)
    }

    /**
     * 添加一条日程 / 倒计时。
     *
     * @param title 日程标题,不能为空。
     * @param date 日期,格式 yyyy-MM-dd。
     * @param startTime 开始时间,格式 HH:MM;与 endTime 一起省略表示全天。
     * @param endTime 结束时间,格式 HH:MM。
     * @param category 分类:TODO / ACTIVITY / EXAM / HOMEWORK / OTHER;默认 TODO。
     * @param location 地点,可空。
     * @return 新建的日程。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun addAgendaItem(
        title: String,
        date: String,
        startTime: String? = null,
        endTime: String? = null,
        category: String? = null,
        location: String? = null,
    ): AgendaSummary = withContext(Dispatchers.IO) {
        if (title.isBlank()) throw AppFunctionInvalidArgumentException("title 不能为空")
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
            ?: throw AppFunctionInvalidArgumentException("date 必须是 yyyy-MM-dd,收到「$date」")
        if (startTime != null && Schedule.parseClock(startTime) == null) {
            throw AppFunctionInvalidArgumentException("startTime 必须是 HH:MM,收到「$startTime」")
        }
        if (endTime != null && Schedule.parseClock(endTime) == null) {
            throw AppFunctionInvalidArgumentException("endTime 必须是 HH:MM,收到「$endTime」")
        }
        val draft = AiPlanner.agendaDraft(
            AiAction.CreateAgenda(
                title = title.trim(),
                category = category.orEmpty().ifBlank { AgendaCategory.TODO.name },
                date = parsed.toString(),
                startTime = startTime,
                endTime = endTime,
                location = location.orEmpty().trim(),
            ),
        ) ?: throw AppFunctionInvalidArgumentException("日程时间不合法:结束时间必须晚于开始时间")

        agendaRepository.save(draft)
        draft.toSummary()
    }
    /**
     * 删除一门课程。
     *
     * @param courseId 课程 ID,来自 searchCourses / getWeekSchedule 的返回。
     * @return 被删除课程的 ID。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun deleteCourse(courseId: String): String = withContext(Dispatchers.IO) {
        if (courseId.isBlank()) throw AppFunctionInvalidArgumentException("courseId 不能为空")
        courseRepository.get(courseId)
            ?: throw AppFunctionElementNotFoundException("找不到 id=$courseId 的课程")
        courseRepository.delete(courseId)
        courseId
    }

    private fun Course.toSummary(periodTimes: String, weekCount: Int): CourseSummary {
        val periods = Schedule.parsePeriods(periodTimes)
        return CourseSummary(
            id = id,
            name = name,
            weekday = weekday,
            startSection = startPeriod,
            endSection = endPeriod,
            time = Schedule.courseTimeText(this, periods),
            location = location,
            teacher = teacher,
            weeks = weeksText(weekCount),
        )
    }

    private fun AgendaEvent.toSummary(zone: ZoneId = ZoneId.systemDefault()): AgendaSummary {
        val start = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDateTime()
        val time = if (allDay) {
            ""
        } else {
            val end = Instant.ofEpochMilli(endAt).atZone(zone).toLocalDateTime()
            "%02d:%02d-%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
        }
        return AgendaSummary(
            id = id,
            title = title,
            date = start.toLocalDate().toString(),
            time = time,
            location = location,
        )
    }
}

/** 一门课的摘要(供代理展示与后续引用 [id])。 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CourseSummary(
    /** 课程 ID,删除 / 引用时用它。 */
    val id: String,
    /** 课程名。 */
    val name: String,
    /** 星期:1 = 周一 … 7 = 周日。 */
    val weekday: Int,
    /** 开始节次。 */
    val startSection: Int,
    /** 结束节次。 */
    val endSection: Int,
    /** 上课时间,如 08:00-08:45。 */
    val time: String,
    /** 上课地点。 */
    val location: String,
    /** 教师。 */
    val teacher: String,
    /** 周次说明,如 第1-16周。 */
    val weeks: String,
)

/** 一条日程 / 倒计时的摘要。 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AgendaSummary(
    /** 日程 ID。 */
    val id: String,
    /** 标题。 */
    val title: String,
    /** 日期 yyyy-MM-dd。 */
    val date: String,
    /** 时间 HH:MM-HH:MM;全天为空串。 */
    val time: String,
    /** 地点。 */
    val location: String,
)

/** 一节课的作息信息。 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PeriodSummary(
    /** 节次序号(从 1 开始)。 */
    val index: Int,
    /** 开始时刻 HH:MM。 */
    val start: String,
    /** 结束时刻 HH:MM。 */
    val end: String,
)

/** 学期概况。 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SemesterSummary(
    /** 当前教学周。 */
    val currentWeek: Int,
    /** 学期总周数。 */
    val weekCount: Int,
    /** 开学日期 yyyy-MM-dd;未设置为空串。 */
    val startDate: String,
)
