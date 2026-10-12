package com.kxin.classtable.data

import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Dates
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 助手可调用的**只读工具**。
 *
 * 写操作不在这里 —— 它们只能经「动作 + 用户确认」落地(见 [AiAction] 与 AssistantViewModel)。
 * 于是模型永远只是「读事实、提计划」,真正改数据的那一步始终由用户点。
 */
enum class AgentToolName {
    GET_CURRENT_OVERVIEW,
    SEARCH_COURSES,
    GET_WEEK_SCHEDULE,
    GET_SEMESTER_SCHEDULE,
    GET_PERIODS,
    GET_SETTINGS,
    GET_SCHEDULE_ADJUSTMENTS,
    ;

    companion object {
        /** 宽松匹配:大小写 / 连字符 / 空格都归一,便于各家模型稳定命中。 */
        fun of(raw: String): AgentToolName? {
            val n = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
            return entries.firstOrNull { it.name == n }
        }
    }
}

/** 模型提出的一次工具调用。[args] 一律是字符串(与两家 wire 的宽松解析一致)。 */
data class AiToolRequest(val name: String, val args: Map<String, String>)

/** 一次工具调用结果。[content] 是给模型看的事实文本;失败时 [ok]=false。 */
data class AgentToolResult(val name: String, val ok: Boolean, val content: String)

/**
 * 工具执行所需的全部本地事实。一次装配、整轮复用 —— 也顺带保证同一轮里读到的是一致的快照。
 */
data class AgentContext(
    val settings: AppSettings,
    val courses: List<Course>,
    val agenda: List<AgendaEvent>,
    val memory: List<String>,
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val periods: List<Schedule.Period> get() = Schedule.parsePeriods(settings.periodTimes)
    val weekCount: Int get() = settings.semesterWeekCount.coerceAtLeast(1)
    val currentWeek: Int get() = Schedule.weekOf(today.toEpochDay(), settings.semesterStartDay, weekCount)

    /**
     * 事实版本:课程 / 日程 / 设置 / 记忆任一变化就换。进程级缓存用它判失效 ——
     * 只要底层没变,同一问题的读取就不必再算一遍。
     */
    val version: String get() = buildString {
        append(settings.semesterStartDay).append('/').append(settings.semesterWeekCount).append('/')
        append(settings.periodTimes.hashCode()).append('/')
        append(settings.scheduleAdjustments.hashCode()).append('/')
        append(memory.size).append('/')
        courses.forEach { append(it.id).append(':').append(it.updatedAt).append(',') }
        agenda.forEach { append(it.id).append(':').append(it.updatedAt).append(',') }
    }
}

/**
 * 进程级事实缓存。
 *
 * 与「同一轮内相同调用只执行一次」(由循环里的 per-turn map 负责)不同,这一层跨轮 / 跨问题复用:
 * 只要课程 / 日程 / 设置没变,「这周有什么课」不必每次重算。五分钟过期,并随 [AgentContext.version] 失效。
 */
internal object AgentFactCache {
    private data class Entry(val version: String, val at: Long, val content: String)

    private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 5 * 60 * 1000L

    private fun key(call: AiToolRequest): String =
        call.name.trim().uppercase() + "|" +
            call.args.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    fun get(call: AiToolRequest, ctx: AgentContext): String? {
        val entry = map[key(call)] ?: return null
        val stale = entry.version != ctx.version || System.currentTimeMillis() - entry.at > TTL_MS
        if (stale) {
            map.remove(key(call))
            return null
        }
        return entry.content
    }

    fun put(call: AiToolRequest, ctx: AgentContext, content: String) {
        // 容量上限:条目只在被访问时按 TTL 惰性清理,长会话里可能累积到一个不成比例的规模。
        if (map.size >= MAX_ENTRIES) map.clear()
        map[key(call)] = Entry(ctx.version, System.currentTimeMillis(), content)
    }

    private const val MAX_ENTRIES = 64
}

/** 只读工具的执行入口。 */
object AgentTools {

    /** 提示词里的工具目录(与 [AiFunctions.readTools] 同源)。 */
    fun catalog(): String = AiFunctions.catalog()

    fun execute(call: AiToolRequest, ctx: AgentContext): AgentToolResult {
        val name = AgentToolName.of(call.name)
            ?: return AgentToolResult(
                call.name, false,
                "未知工具「${call.name}」。可用工具:${AgentToolName.entries.joinToString { it.name }}。",
            )
        AgentFactCache.get(call, ctx)?.let { return AgentToolResult(name.name, true, it) }
        return runCatching { render(name, call.args, ctx) }
            .fold(
                onSuccess = { content ->
                    AgentFactCache.put(call, ctx, content)
                    AgentToolResult(name.name, true, content)
                },
                onFailure = { AgentToolResult(name.name, false, "工具执行失败:${it.message}") },
            )
    }

    private fun render(name: AgentToolName, args: Map<String, String>, ctx: AgentContext): String =
        when (name) {
            AgentToolName.GET_CURRENT_OVERVIEW -> overview(ctx)
            AgentToolName.SEARCH_COURSES -> search(args, ctx)
            AgentToolName.GET_WEEK_SCHEDULE -> weekSchedule(args["week"]?.toIntOrNull(), ctx)
            AgentToolName.GET_SEMESTER_SCHEDULE -> semesterSchedule(ctx)
            AgentToolName.GET_PERIODS -> periods(ctx)
            AgentToolName.GET_SETTINGS -> settings(ctx)
            AgentToolName.GET_SCHEDULE_ADJUSTMENTS -> adjustments(ctx)
        }

    private fun overview(ctx: AgentContext): String {
        val sb = StringBuilder()
        sb.append("[当前状态] ").append(ctx.today)
            .append(' ').append(Dates.weekdayLabel(ctx.today.dayOfWeek.value))
            .append(",第 ").append(ctx.currentWeek).append(" 教学周(共 ").append(ctx.weekCount).append(" 周)")
            .append(",作息共 ").append(ctx.periods.size).append(" 节")

        val todayCourses = ctx.courses
            .filter { it.isOnWeekday(ctx.today.dayOfWeek.value) && it.isActiveOnWeek(ctx.currentWeek) }
            .sortedBy { it.startPeriod }
        sb.append("\n今日课程(").append(todayCourses.size).append(" 门):")
        if (todayCourses.isEmpty()) {
            sb.append("无")
        } else {
            todayCourses.forEach { sb.append('\n').append(courseLine(it, ctx)) }
        }

        val upcoming = upcomingAgenda(ctx)
        sb.append("\n近期日程(").append(upcoming.size).append(" 条):")
        if (upcoming.isEmpty()) sb.append("无") else upcoming.forEach { sb.append('\n').append(it) }
        return sb.toString()
    }

    private fun search(args: Map<String, String>, ctx: AgentContext): String {
        val text = listOf("query", "name", "teacher", "location").mapNotNull { args[it]?.trim() }
            .filter { it.isNotEmpty() }
        val weekday = args["weekday"]?.toIntOrNull()
        if (text.isEmpty() && weekday == null) {
            return "请在 args 里至少给一个条件:query / name / teacher / location / weekday。"
        }
        val matched = ctx.courses.filter { c ->
            val hitText = text.isEmpty() || text.all { q ->
                c.name.contains(q, true) || c.teacher.contains(q, true) || c.location.contains(q, true)
            }
            val hitDay = weekday == null || c.isOnWeekday(weekday)
            hitText && hitDay
        }
        if (matched.isEmpty()) return "没有匹配的课程。"
        val shown = matched.take(24)
        val sb = StringBuilder("匹配 ${matched.size} 门")
        if (matched.size > shown.size) sb.append("(已截断,只列前 ${shown.size} 门)")
        sb.append(':')
        shown.forEach { sb.append('\n').append(courseLine(it, ctx)) }
        return sb.toString()
    }

    private fun weekSchedule(week: Int?, ctx: AgentContext): String {
        val w = (week ?: ctx.currentWeek).coerceIn(1, ctx.weekCount)
        val sb = StringBuilder("第 $w 周安排:")
        var total = 0
        for (day in 1..7) {
            val list = ctx.courses
                .filter { it.isOnWeekday(day) && it.isActiveOnWeek(w) }
                .sortedBy { it.startPeriod }
            if (list.isEmpty()) continue
            total += list.size
            sb.append('\n').append(Dates.weekdayLabel(day)).append(':')
            list.forEach { sb.append("\n  ").append(courseLine(it, ctx)) }
        }
        if (total == 0) sb.append(" 这一周没有课。")
        return sb.toString()
    }

    private fun semesterSchedule(ctx: AgentContext): String {
        if (ctx.courses.isEmpty()) return "本学期没有任何课程。"
        val sb = StringBuilder("全学期课程 ${ctx.courses.size} 门(id 用于修改/删除):")
        ctx.courses.sortedWith(compareBy({ it.weekday }, { it.startPeriod }))
            .forEach { sb.append('\n').append(courseLine(it, ctx)) }
        return sb.toString()
    }

    private fun periods(ctx: AgentContext): String {
        if (ctx.periods.isEmpty()) return "尚未设置作息。"
        val sb = StringBuilder("作息(共 ${ctx.periods.size} 节):")
        ctx.periods.forEachIndexed { i, p ->
            sb.append("\n第").append(i + 1).append("节 ")
                .append(Schedule.clockText(p.start)).append('-').append(Schedule.clockText(p.end))
        }
        return sb.toString()
    }

    private fun settings(ctx: AgentContext): String = buildString {
        // 与「改设置」共用同一份白名单:能改什么、取值范围,模型看到的就是本地会校验的那张表。
        append("[可改设置]\n").append(AiSettingRegistry.describeWithCurrent(ctx.settings))
        append("\n\n调休安排:").append(Adjustments.decode(ctx.settings.scheduleAdjustments).size).append(" 条")
        append("\n助手记忆:").append(ctx.memory.size).append(" 条")
        append("\n可打开页面:").append(AiPages.describe())
    }

    private fun adjustments(ctx: AgentContext): String {
        val items = Adjustments.decode(ctx.settings.scheduleAdjustments)
        if (items.isEmpty()) return "没有配置调休 / 停课 / 补课安排。"
        val sb = StringBuilder("调休安排 ${items.size} 条:")
        items.forEach { sb.append("\n- ").append(it.date).append(' ').append(it.summary()) }
        return sb.toString()
    }

    /** 课程一行:`- id=… 周X 第a-b节 名称 @地点 教师 [周次]`。ID 是改 / 删的唯一依据。 */
    private fun courseLine(course: Course, ctx: AgentContext): String = buildString {
        append("- id=").append(course.id).append(' ')
        append(Course.weekdaysText(course)).append(' ')
        if (course.isCustomScheduled()) {
            append(Schedule.courseTimeText(course, ctx.periods))
        } else {
            append("第").append(course.startPeriod).append('-').append(course.endPeriod).append("节")
        }
        append(' ').append(course.name)
        if (course.location.isNotBlank()) append(" @").append(course.location)
        if (course.teacher.isNotBlank()) append(' ').append(course.teacher)
        append(" [").append(course.weeksText(ctx.weekCount)).append(']')
    }

    private fun upcomingAgenda(ctx: AgentContext): List<String> {
        val from = ctx.today.atStartOfDay(ctx.zone).toInstant().toEpochMilli()
        return ctx.agenda.filter { it.endAt >= from }.sortedBy { it.startAt }.take(5).map { e ->
            val start = Instant.ofEpochMilli(e.startAt).atZone(ctx.zone).toLocalDateTime()
            buildString {
                append("- ").append(start.toLocalDate()).append(' ')
                if (e.allDay) append("全天") else append(Schedule.clockText(start.hour * 60 + start.minute))
                append(' ').append(e.title)
                if (e.location.isNotBlank()) append(" @").append(e.location)
            }
        }
    }
}

/** 原生函数调用的参数(JSON 字符串)→ 字符串映射。解析失败当作空参,交给工具自己报错。 */
fun parseToolArgs(json: String): Map<String, String> = runCatching {
    val obj = JSONObject(json)
    // 注意:JSONObject.keys() 是 Iterator(不是 Iterable),不能直接用 associateWith。
    val out = LinkedHashMap<String, String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key)
        out[key] = if (value == null || value == JSONObject.NULL) "" else value.toString()
    }
    out
}.getOrDefault(emptyMap())
