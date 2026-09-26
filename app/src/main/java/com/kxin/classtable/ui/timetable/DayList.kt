package com.kxin.classtable.ui.timetable

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseTint
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.Course
import java.time.LocalDate

/**
 * 日模式:某一天的课程列表(卡片式,卡片之间的距离就是空堂)。
 *
 * 与周模式共用同一份作息 / 调休解析 —— 停课的这一天列表为空并说明原因,补课的这一天
 * 取的是「原课程日期」那天的课。
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
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        val weekName = "一二三四五六日"[date.dayOfWeek.value - 1]
        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${date.monthValue}月${date.dayOfMonth}日",
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
            Text(
                text = weekLabel,
                style = YohakuType.copy13,
                color = colors.neutral7,
                modifier = Modifier.padding(top = 2.dp),
            )
            val status = when {
                plan.rest -> "调休 · 停课"
                plan.makeup -> "调休 · 补 ${plan.effectiveDate.monthValue}/${plan.effectiveDate.dayOfMonth} 的课"
                ongoing != null -> "正在上课:${ongoing.first.name} · 还有 ${ongoing.third - nowMinute} 分钟下课"
                next != null -> "距 ${next.first.name} 上课还有 ${next.second - nowMinute} 分钟"
                isToday -> "今天没有更多课了"
                dayCourses.isEmpty() -> "这一天没有课"
                else -> "${dayCourses.size} 门课"
            }
            Text(
                text = status,
                style = YohakuType.label12,
                color = if (plan.rest || plan.makeup || ongoing != null) colors.accent else colors.neutral7,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (dayCourses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (plan.rest) "停课(调休)" else "没有课",
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
                        // 末尾预留悬浮导航的高度:中途内容从栏下穿过,最后一节课仍能卷上来
                        bottom = YohakuDimens.gapCard + YohakuDimens.navReservedHeight,
                    ),
                    verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapSection),
                ) {
                    itemsIndexed(dayCourses, key = { _, c -> c.id }) { index, course ->
                        // 入场错峰:延迟按序号递增,但封顶,免得长列表末尾等太久
                        val appear = remember(course.id) { Animatable(0f) }
                        LaunchedEffect(course.id) {
                            appear.animateTo(
                                targetValue = 1f,
                                animationSpec = YohakuMotion.tween(
                                    durationMs = YohakuMotion.durBase,
                                    easing = YohakuMotion.easeOut,
                                    delayMs = YohakuMotion.staggerDelay(index.coerceAtMost(7), 30),
                                ),
                            )
                        }
                        val isCurrent = Schedule.isCourseOngoing(course, periods)
                        YohakuCard(
                            modifier = Modifier
                                .animateItem()
                                .graphicsLayer {
                                    alpha = appear.value
                                    translationY = (1f - appear.value) * 24f
                                },
                            accentBar = isCurrent,
                            containerColor = courseTint(course),
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = Schedule.courseTimeText(course, periods),
                                    style = YohakuType.timeMono,
                                    color = colors.neutral7,
                                    modifier = Modifier.width(100.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = course.name,
                                        style = YohakuType.copy16,
                                        color = colors.neutral10,
                                    )
                                    val meta = listOf(course.location, course.teacher)
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
                }
            }
        }
    }
}
