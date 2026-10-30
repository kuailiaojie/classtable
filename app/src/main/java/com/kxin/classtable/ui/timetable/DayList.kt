package com.kxin.classtable.ui.timetable

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.EntranceTracker
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuCheckCircle
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.yohakuNavReservedHeight
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseTint
import com.kxin.classtable.design.rememberYohakuHaptics
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Dates
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.ui.agenda.categoryTint
import com.kxin.classtable.ui.agenda.spans
import com.kxin.classtable.ui.agenda.startLocal
import com.kxin.classtable.ui.agenda.endLocal
import java.time.LocalDate

/** 时间线元素:课程、日程(作业/考试/待办)、课间空堂 */
internal sealed interface DayTimelineItem {
    val sortMinute: Int
    val key: String

    data class CourseItem(
        val course: Course,
        val startMinute: Int,
        val endMinute: Int,
    ) : DayTimelineItem {
        override val sortMinute: Int get() = startMinute
        override val key: String get() = "course_${course.id}_$startMinute"
    }

    data class AgendaItem(
        val event: AgendaEvent,
        val startMinute: Int,
        val endMinute: Int,
    ) : DayTimelineItem {
        override val sortMinute: Int get() = startMinute
        override val key: String get() = "agenda_${event.id}"
    }

    data class GapItem(
        val startMinute: Int,
        val endMinute: Int,
        val durationMinutes: Int,
    ) : DayTimelineItem {
        override val sortMinute: Int get() = startMinute
        override val key: String get() = "gap_${startMinute}_$endMinute"
    }
}

/**
 * 日模式:某一天的全景时间轴(融合课程、空堂规划与个人待办/DDL)。
 *
 * 遵循 Apple HIG 融合时间流理念:课程与任务按时间穿插呈现，
 * 且完成项在时间线上划线淡化保留。
 */
@Composable
internal fun DayList(
    date: LocalDate,
    weekLabel: String,
    courses: List<Course>,
    periods: List<Schedule.Period>,
    adjustments: List<ScheduleAdjustment>,
    semesterStartDay: Long,
    semesterWeekCount: Int,
    nowMinute: Int,
    isToday: Boolean,
    onCourseClick: (Course) -> Unit,
    onWeekClick: (() -> Unit)? = null,
    entrance: EntranceTracker,
    modifier: Modifier = Modifier,
    agendaEvents: List<AgendaEvent> = emptyList(),
    onToggleAgenda: ((String, Boolean) -> Unit)? = null,
    onAgendaClick: ((AgendaEvent) -> Unit)? = null,
    onAddAgenda: ((startMinute: Int, endMinute: Int) -> Unit)? = null,
    onAddCourse: ((weekday: Int, period: Int?) -> Unit)? = null,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()

    val plan = remember(adjustments, date) { Adjustments.resolve(adjustments, date) }
    val teaching = remember(adjustments, semesterStartDay, semesterWeekCount, date) {
        Adjustments.teachingDay(adjustments, date, semesterStartDay, semesterWeekCount)
    }
    val dayCourses = remember(courses, teaching, periods) {
        if (teaching == null) {
            emptyList()
        } else {
            courses
                .filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
                .sortedBy { Schedule.courseStartMinute(it, periods) ?: Int.MAX_VALUE }
        }
    }

    val scheduled = remember(dayCourses, periods) {
        dayCourses.mapNotNull { course ->
            val s = course.customStartMinute
                ?: periods.getOrNull(course.startPeriod - 1)?.start
                ?: return@mapNotNull null
            val e = course.customEndMinute
                ?: periods.getOrNull(course.endPeriod - 1)?.end
                ?: return@mapNotNull null
            Triple(course, s, e)
        }.sortedBy { it.second }
    }
    val ongoing = if (isToday) scheduled.firstOrNull { nowMinute >= it.second && nowMinute < it.third } else null
    val next = if (isToday) scheduled.firstOrNull { it.second > nowMinute } else null

    // 当日日程过滤与全天/分时拆分
    val dayAgendas = remember(agendaEvents, date) {
        agendaEvents.filter { it.spans(date) }
    }
    val allDayAgendas = remember(dayAgendas) { dayAgendas.filter { it.allDay } }
    val timedAgendas = remember(dayAgendas) { dayAgendas.filterNot { it.allDay } }

    // 全景时间流编排:课程 + 空堂(>=60分钟) + 定时日程
    val timelineItems = remember(scheduled, timedAgendas) {
        val list = mutableListOf<DayTimelineItem>()

        // 1. 课程项
        scheduled.forEach { (c, s, e) ->
            list.add(DayTimelineItem.CourseItem(c, s, e))
        }

        // 2. 空堂检测(相邻两门课之间间隔 >= 60 分钟)
        for (i in 0 until scheduled.size - 1) {
            val curEnd = scheduled[i].third
            val nextStart = scheduled[i + 1].second
            val gap = nextStart - curEnd
            if (gap >= 60) {
                list.add(DayTimelineItem.GapItem(curEnd, nextStart, gap))
            }
        }

        // 3. 定时日程项: 跨天日程按当日视界截断起止分钟，避免跨天倒置
        timedAgendas.forEach { a ->
            val startLocal = a.startLocal()
            val endLocal = a.endLocal()
            val s = if (startLocal.toLocalDate() < date) 0 else (startLocal.hour * 60 + startLocal.minute)
            val e = if (endLocal.toLocalDate() > date) 1440 else (endLocal.hour * 60 + endLocal.minute)
            if (e > s) {
                list.add(DayTimelineItem.AgendaItem(a, s, e))
            }
        }

        list.sortedBy { it.sortMinute }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = YohakuDimens.maxContentWidth)
                .fillMaxWidth(),
        ) {
            val weekName = Dates.weekdayChar(date.dayOfWeek.value)
            Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Dates.monthDay(date),
                        style = YohakuType.title20,
                        color = colors.neutral10,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "周$weekName",
                        style = YohakuType.copy13,
                        color = colors.neutral7,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (onWeekClick != null) {
                        Modifier
                            .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                            .clickable { onWeekClick() }
                            .padding(vertical = 2.dp)
                    } else {
                        Modifier.padding(top = 2.dp)
                    },
                ) {
                    Text(
                        text = weekLabel,
                        style = YohakuType.copy13,
                        color = colors.neutral7,
                    )
                    if (onWeekClick != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "▾",
                            style = YohakuType.copy13,
                            color = colors.neutral6,
                        )
                    }
                }

                val status = when {
                    plan.rest -> "调休 · 停课"
                    plan.makeup -> "调休 · 补 ${Dates.slash(plan.effectiveDate)} 的课"
                    ongoing != null -> "正在上课:${ongoing.first.name} · 还有 ${ongoing.third - nowMinute} 分钟下课"
                    next != null -> "距 ${next.first.name} 上课还有 ${next.second - nowMinute} 分钟"
                    isToday && dayCourses.isNotEmpty() -> "今日课程结束"
                    dayCourses.isEmpty() && dayAgendas.isNotEmpty() -> "今日无课 · ${dayAgendas.size} 项日程安排"
                    dayCourses.isEmpty() -> "这一天没有课"
                    else -> "${dayCourses.size} 门课" + if (dayAgendas.isNotEmpty()) " · ${dayAgendas.size} 项日程" else ""
                }
                Text(
                    text = status,
                    style = YohakuType.label12,
                    color = if (plan.rest || plan.makeup || ongoing != null) colors.accent else colors.neutral7,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (dayCourses.isEmpty() && dayAgendas.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(date) {
                                detectTapGestures(
                                    onLongPress = {
                                        haptics.tick()
                                        val targetDay = teaching?.second ?: date.dayOfWeek.value
                                        onAddCourse?.invoke(targetDay, null)
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (plan.rest) "停课(调休)" else "没有课程与日程安排 · 适宜自由阅读",
                            style = YohakuType.copy14,
                            color = colors.neutral7,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = YohakuDimens.screenPadding,
                            end = YohakuDimens.screenPadding,
                            top = YohakuDimens.gapCard,
                            bottom = YohakuDimens.gapCard + yohakuNavReservedHeight(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapSection),
                    ) {
                        // 1. 全天日程固定置顶
                        if (allDayAgendas.isNotEmpty()) {
                            item(key = "all_day_section") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "全天待办 / 目标",
                                        style = YohakuType.label12,
                                        color = colors.neutral6,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                    allDayAgendas.forEach { agenda ->
                                        AllDayAgendaCard(
                                            event = agenda,
                                            onToggle = { onToggleAgenda?.invoke(agenda.id, !agenda.completed) },
                                            onClick = { onAgendaClick?.invoke(agenda) },
                                        )
                                    }
                                }
                            }
                        }

                        // 2. 混合时间流
                        itemsIndexed(timelineItems, key = { _, item -> item.key }) { index, item ->
                            val animate = entrance.shouldAnimate(item.key)
                            val appear = remember(item.key) { Animatable(if (animate) 0f else 1f) }
                            LaunchedEffect(item.key) {
                                if (animate) {
                                    try {
                                        appear.animateTo(
                                            targetValue = 1f,
                                            animationSpec = YohakuMotion.tween(
                                                durationMs = YohakuMotion.durBase,
                                                easing = YohakuMotion.easeOut,
                                                delayMs = YohakuMotion.staggerDelay(index.coerceAtMost(7), YohakuMotion.stagger),
                                            ),
                                        )
                                    } finally {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                            appear.snapTo(1f)
                                        }
                                        entrance.markSeen(item.key)
                                    }
                                } else {
                                    appear.snapTo(1f)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .animateItem()
                                    .graphicsLayer {
                                        alpha = appear.value
                                        translationY = (1f - appear.value) * 24f
                                    },
                            ) {
                                when (item) {
                                    is DayTimelineItem.CourseItem -> {
                                        val isCurrent = isToday && Schedule.isCourseOngoing(item.course, periods)
                                        val isPast = isToday && (item.endMinute < nowMinute)
                                        YohakuCard(
                                            accentBar = isCurrent,
                                            containerColor = courseTint(item.course),
                                            onClick = { onCourseClick(item.course) },
                                            onLongClick = {
                                                haptics.tick()
                                                val targetDay = teaching?.second ?: date.dayOfWeek.value
                                                onAddCourse?.invoke(targetDay, item.course.startPeriod)
                                            },
                                            modifier = if (isPast) Modifier.graphicsLayer { alpha = 0.85f } else Modifier,
                                        ) {
                                            Row(verticalAlignment = Alignment.Top) {
                                                Text(
                                                    text = Schedule.courseTimeText(item.course, periods),
                                                    style = YohakuType.timeMono,
                                                    color = if (isPast) colors.neutral6 else colors.neutral7,
                                                    modifier = Modifier.width(100.dp),
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.course.name,
                                                        style = YohakuType.copy16,
                                                        color = if (isPast) colors.neutral8 else colors.neutral10,
                                                    )
                                                    val meta = listOf(item.course.location, item.course.teacher)
                                                        .filter { it.isNotEmpty() }
                                                        .joinToString(" · ")
                                                    if (meta.isNotEmpty()) {
                                                        Text(
                                                            text = meta,
                                                            style = YohakuType.copy13,
                                                            color = colors.neutral7,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    is DayTimelineItem.AgendaItem -> {
                                        // 日程待办:支持打卡、划线淡化
                                        YohakuCard(
                                            containerColor = if (item.event.completed) colors.neutral2 else categoryTint(item.event.category),
                                            onClick = { onAgendaClick?.invoke(item.event) },
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "%02d:%02d–%02d:%02d".format(
                                                        item.startMinute / 60, item.startMinute % 60,
                                                        item.endMinute / 60, item.endMinute % 60,
                                                    ),
                                                    style = YohakuType.timeMono,
                                                    color = if (item.event.completed) colors.neutral5 else colors.neutral7,
                                                    modifier = Modifier.width(100.dp),
                                                )

                                                // 触感打卡复选框: HIG 44×44pt 触达与触感打卡
                                                YohakuCheckCircle(
                                                    completed = item.event.completed,
                                                    onToggle = {
                                                        onToggleAgenda?.invoke(item.event.id, !item.event.completed)
                                                    },
                                                )

                                                Spacer(modifier = Modifier.width(4.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.event.title,
                                                        style = YohakuType.copy16.copy(
                                                            textDecoration = if (item.event.completed) TextDecoration.LineThrough else TextDecoration.None,
                                                        ),
                                                        color = if (item.event.completed) colors.neutral6 else colors.neutral10,
                                                    )
                                                    val meta = buildList {
                                                        add(item.event.category.label)
                                                        if (item.event.completed) add("已完成")
                                                        if (item.event.location.isNotEmpty()) add("@${item.event.location}")
                                                    }.joinToString(" · ")
                                                    Text(
                                                        text = meta,
                                                        style = YohakuType.copy13,
                                                        color = colors.neutral6,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    is DayTimelineItem.GapItem -> {
                                        // 空堂规划建议卡片
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(YohakuDimens.radiusCard))
                                                .background(colors.neutral2)
                                                .clickable { onAddAgenda?.invoke(item.startMinute, item.endMinute) }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                val h = item.durationMinutes / 60
                                                val m = item.durationMinutes % 60
                                                val durationStr = if (h > 0 && m > 0) "${h}小时${m}分" else if (h > 0) "${h}小时" else "${m}分钟"
                                                Text(
                                                    text = "课间空堂 $durationStr · 适宜自习与整理",
                                                    style = YohakuType.label12,
                                                    color = colors.neutral7,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(
                                                    text = "＋ 安排待办",
                                                    style = YohakuType.label12,
                                                    color = colors.accent,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllDayAgendaCard(
    event: AgendaEvent,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    YohakuCard(
        containerColor = if (event.completed) colors.neutral2 else categoryTint(event.category),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YohakuCheckCircle(
                completed = event.completed,
                onToggle = onToggle,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "[${event.category.label}] ${event.title}",
                style = YohakuType.copy15.copy(
                    textDecoration = if (event.completed) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = if (event.completed) colors.neutral6 else colors.neutral10,
                modifier = Modifier.weight(1f),
            )
            if (event.completed) {
                Text(text = "已完成", style = YohakuType.label12, color = colors.neutral6)
            }
        }
    }
}
