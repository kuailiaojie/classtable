package com.kxin.classtable.ui.timetable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kxin.classtable.data.BlockCorner
import com.kxin.classtable.data.GridDensity
import com.kxin.classtable.data.NameSize
import com.kxin.classtable.data.TimetablePrefs
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseTint
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.Course

/** 表头:一~日 + 该天日号;今天用 accent 标出,调休日标「休 / 补」。 */
@Composable
internal fun WeekdayHeader(
    dayNumbers: List<Int>,
    today: Int,
    marks: List<String?>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.gridPadding, vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Spacer(modifier = Modifier.width(YohakuDimens.gridGutterWidth))
        (1..7).forEach { d ->
            val isToday = d == today
            val mark = marks.getOrNull(d - 1)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "一二三四五六日"[d - 1].toString(),
                    style = YohakuType.gridWeekday,
                    color = if (isToday) colors.accent else colors.neutral8,
                )
                Text(
                    text = dayNumbers.getOrNull(d - 1)?.toString() ?: "",
                    style = YohakuType.gridDate,
                    color = if (isToday) colors.accent else colors.neutral6,
                )
                if (mark != null) {
                    Text(
                        text = mark,
                        style = YohakuType.gridMeta,
                        color = colors.accent,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .width(12.dp)
                            .height(2.dp)
                            .background(if (isToday) colors.accent else Color.Transparent),
                    )
                }
            }
        }
    }
}

/**
 * 这一周七天各按哪一天上课:返回 `(周次, 星期几)`,停课为 null。
 *
 * 没设开学日时拿不到具体日期,调休无从谈起,退化成「自然星期」。
 */
internal fun resolveTeachingDays(
    adjustments: List<ScheduleAdjustment>,
    semesterStartDay: Long,
    weekCount: Int,
    week: Int,
): List<Pair<Int, Int>?> {
    val dates = Schedule.weekDates(semesterStartDay, week)
    if (dates.isEmpty()) return (1..7).map { week to it }
    return dates.map { Adjustments.teachingDay(adjustments, it, semesterStartDay, weekCount) }
}

/** 网格:左侧节次留白列 + 七列,课程块绝对定位。 */
@Composable
internal fun WeekGrid(
    courses: List<Course>,
    periods: List<Schedule.Period>,
    week: Int,
    today: Int,
    nowMinute: Int,
    showNowLine: Boolean,
    teachingDays: List<Pair<Int, Int>?>,
    prefs: TimetablePrefs,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val rowCount = periods.size.coerceAtLeast(1)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 行高:优先铺满可用高度;低于下限(小屏或节次特别多)时整格纵向滚动。
        // 下限随「行高密度」设置变化(宽松 46 / 标准 38 / 紧凑 32),于是同一块屏能放下多少节由用户定。
        val rowH = maxOf(maxHeight / rowCount, minRowHeight(prefs.density))
        val gridHeight = rowH * rowCount
        val colW = (maxWidth - YohakuDimens.gridPadding * 2 - YohakuDimens.gridGutterWidth) / 7
        val gutter = YohakuDimens.gridGutterWidth
        // 列取课按「这一天实际上哪天的课」算:停课的列空着,补课的列去取原课程日期的课
        val lanesPerDay = (1..7).map { d ->
            val teaching = teachingDays.getOrNull(d - 1)
            val dayCourses = if (teaching == null) {
                emptyList()
            } else {
                courses.filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
            }
            layoutDay(dayCourses, periods)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight),
            ) {
                // 今天整列淡淡的底色(只做方位提示,不喧哗)
                if (today in 1..7) {
                    Box(
                        modifier = Modifier
                            .offset(x = YohakuDimens.gridPadding + gutter + colW * (today - 1))
                            .width(colW)
                            .fillMaxHeight()
                            .background(colors.neutral1),
                    )
                }

                // 节次留白列:每行「节号 + 该节开始时间」。
                Column(
                    modifier = Modifier
                        .width(gutter)
                        .fillMaxHeight()
                        .padding(end = 3.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    periods.forEachIndexed { idx, period ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowH),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Text(
                                text = "${idx + 1}",
                                style = YohakuType.gridGutter,
                                color = colors.neutral8,
                            )
                            Text(
                                text = Schedule.clockText(period.start),
                                style = YohakuType.gridGutterTime,
                                color = colors.neutral7,
                            )
                        }
                    }
                }

                // 课程块(按天分列,同一天内按车道并排)。staggerIndex 让入场动画逐块错峰。
                var staggerIndex = 0
                lanesPerDay.forEachIndexed { dayIdx, (blocks, laneCount) ->
                    val used = laneCount.coerceAtMost(3)
                    val laneW = colW / used
                    blocks.forEach { block ->
                        CourseBlock(
                            course = block.course,
                            rowH = rowH,
                            top = block.top,
                            height = block.height,
                            x = YohakuDimens.gridPadding + gutter + colW * dayIdx + laneW * block.lane.coerceAtMost(used - 1),
                            width = laneW,
                            // 「正在上」只在**真实当前周**的今天那一列标出:翻到别的周时,
                            // 时间点虽然对得上(只看时刻),但那一天并不是今天,不该再标。
                            isCurrent = showNowLine && dayIdx + 1 == today &&
                                Schedule.isCourseOngoing(block.course, periods),
                            prefs = prefs,
                            periods = periods,
                            staggerIndex = staggerIndex++,
                            onClick = onCourseClick,
                        )
                    }
                }

                // 当前时间线:只画在「今天」那一列,位置带缓动(分钟变化或切周全周时不跳)。
                // 必须带标签 —— 光一条横线没人知道那是什么,之前就被当成「莫名多出来的小横条」。
                val targetRow = Schedule.fractionalRow(nowMinute, periods)
                if (showNowLine && today in 1..7 && targetRow != null) {
                    val row by animateFloatAsState(
                        targetValue = targetRow,
                        animationSpec = YohakuMotion.gentleSpring(),
                        label = "nowLine",
                    )
                    val lineX = YohakuDimens.gridPadding + gutter + colW * (today - 1)
                    val lineY = rowH * row
                    Box(
                        modifier = Modifier
                            .offset(x = lineX, y = lineY)
                            .width(colW)
                            .height(1.5.dp)
                            .background(colors.accent),
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = lineX, y = (lineY - 12.dp).coerceAtLeast(0.dp))
                            .background(colors.paper)
                            .padding(horizontal = 3.dp),
                    ) {
                        Text(
                            text = "现在",
                            style = YohakuType.gridMeta,
                            color = colors.accent,
                        )
                    }
                }
            }
        }
    }
}

/** 网格中的一个课程块:可配淡彩底 / 圆角 / 显示内容 / 字号,入场带错峰动画。 */
@Composable
private fun CourseBlock(
    course: Course,
    rowH: Dp,
    top: Float,
    height: Float,
    x: Dp,
    width: Dp,
    isCurrent: Boolean,
    prefs: TimetablePrefs,
    periods: List<Schedule.Period>,
    staggerIndex: Int,
    onClick: (Course) -> Unit,
) {
    val colors = LocalYohakuColors.current
    val blockH = rowH * height
    // 够高才放第二行(元信息);只够一行时让课程名独占
    val roomy = blockH >= 30.dp
    val spacious = blockH >= 54.dp
    val shape = RoundedCornerShape(cornerRadius(prefs.corner))
    val tinted = prefs.showTint

    // 入场:淡入 + 轻微放大,按序号错峰(GSAP stagger 的 Compose 版)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = YohakuMotion.tween(
                durationMs = YohakuMotion.durBase,
                easing = YohakuMotion.easeExpoOut,
                delayMs = YohakuMotion.staggerDelay(staggerIndex, 22),
            ),
        )
    }
    // 「正在上」的 accent 条轻微呼吸
    val barAlpha by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0.98f,
        animationSpec = YohakuMotion.tween(YohakuMotion.durSlow),
        label = "barAlpha",
    )

    val metas = buildList {
        if (prefs.showPeriod && !course.isCustomScheduled()) {
            add(
                if (course.startPeriod == course.endPeriod) {
                    "${course.startPeriod}节"
                } else {
                    "${course.startPeriod}-${course.endPeriod}节"
                },
            )
        }
        if (prefs.showTime) add(Schedule.courseTimeText(course, periods))
        if (prefs.showLocation && course.location.isNotEmpty()) add(course.location)
        if (prefs.showTeacher && course.teacher.isNotEmpty()) add(course.teacher)
    }
    val metaCount = if (spacious) 2 else if (roomy) 1 else 0

    Box(
        modifier = Modifier
            .graphicsLayer {
                alpha = appear.value
                val s = 0.9f + 0.1f * appear.value
                scaleX = s
                scaleY = s
            }
            .offset(x = x, y = rowH * top)
            .width(width)
            .height((blockH - YohakuDimens.gridCellGap).coerceAtLeast(12.dp))
            .clip(shape)
            .background(if (tinted) courseTint(course) else colors.raised)
            .then(if (tinted) Modifier else Modifier.border(1.dp, colors.line, shape))
            .clickable { onClick(course) },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .width(YohakuDimens.gridAccentBarWidth)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = barAlpha }
                        .background(colors.accent),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = blockHPadding(prefs.density), vertical = 2.dp),
            ) {
                Text(
                    text = course.name,
                    style = nameStyle(prefs.nameSize),
                    color = colors.neutral10,
                    maxLines = if (roomy) prefs.nameLines else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                metas.take(metaCount).forEach { meta ->
                    Text(
                        text = meta,
                        style = YohakuType.gridMeta,
                        color = colors.neutral7,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 一天内的课程块:换算成 (top, height) 行高倍数后,贪心分配并排车道。 */
private data class GridBlock(
    val course: Course,
    val top: Float,
    val height: Float,
    val lane: Int = 0,
)

private fun layoutDay(
    courses: List<Course>,
    periods: List<Schedule.Period>,
): Pair<List<GridBlock>, Int> {
    val blocks = courses.mapNotNull { c ->
        val (top, height) = if (c.hasCustomTime()) {
            Schedule.customCourseLayout(c, periods)
        } else {
            (c.startPeriod - 1).toFloat() to (c.endPeriod - c.startPeriod + 1).toFloat()
        }
        if (height <= 0f) null else GridBlock(c, top, height)
    }.sortedWith(compareBy({ it.top }, { -it.height }))

    // 车道:每条车道记住已占据到的最底部,能塞就复用
    val laneEnds = mutableListOf<Float>()
    val placed = blocks.map { b ->
        val free = laneEnds.indexOfFirst { it <= b.top + 0.01f }
        val lane = if (free >= 0) free else laneEnds.size.also { laneEnds.add(0f) }
        laneEnds[lane] = b.top + b.height
        b.copy(lane = lane)
    }
    return placed to laneEnds.size.coerceAtLeast(1)
}

/** 行高下限:宽松 / 标准 / 紧凑。 */
private fun minRowHeight(density: GridDensity): Dp = when (density) {
    GridDensity.LOOSE -> 46.dp
    GridDensity.STANDARD -> YohakuDimens.gridMinRowHeight
    GridDensity.COMPACT -> 32.dp
}

/** 课程块左右内边距,随密度收窄。 */
private fun blockHPadding(density: GridDensity): Dp = when (density) {
    GridDensity.LOOSE -> 4.dp
    GridDensity.STANDARD -> YohakuDimens.gridBlockPadding
    GridDensity.COMPACT -> 2.dp
}

private fun cornerRadius(corner: BlockCorner): Dp = when (corner) {
    BlockCorner.SQUARE -> 0.dp
    BlockCorner.MEDIUM -> YohakuDimens.radiusControl
    BlockCorner.ROUND -> 10.dp
}

private fun nameStyle(size: NameSize): TextStyle = when (size) {
    NameSize.SMALL -> YohakuType.gridNameSm
    NameSize.MEDIUM -> YohakuType.gridName
    NameSize.LARGE -> YohakuType.gridNameLg
}
