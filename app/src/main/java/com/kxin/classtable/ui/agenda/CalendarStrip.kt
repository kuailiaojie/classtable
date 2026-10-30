package com.kxin.classtable.ui.agenda

import com.kxin.classtable.domain.Schedule
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberYohakuHaptics
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.Dates
import com.kxin.classtable.domain.LunarDate
import java.time.LocalDate

/** 周条可拖动的翻周范围:以进入时的周为锚,前后各约 1000 周。 */
private const val WeekPageCount = 2001
private const val WeekPageCenter = WeekPageCount / 2

/**
 * 日历周条:一行七天,每天「星期 / 日号 / 农历」。
 * 今天 accent 实底,选中日 accent 描边;点任意一天即选中,**左右拖动翻周**,「今天」跳回。
 */
@Composable
internal fun CalendarStrip(
    monday: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onWeekChange: (LocalDate) -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    // 以进入时的周为锚:页码与「周」一一对应,拖动才有一页一周的稳定映射
    val anchorEpoch = remember { monday.toEpochDay() }
    val targetPage = WeekPageCenter + weeksBetween(anchorEpoch, monday.toEpochDay())
    val pagerState = rememberPagerState(initialPage = targetPage) { WeekPageCount }
    val displayedMonday =
        LocalDate.ofEpochDay(anchorEpoch + (pagerState.currentPage - WeekPageCenter) * 7L)

    // 外部跳周(「今天」)→ 动画滚到对应页;拖动落定后页码已一致,不会二次滚动
    LaunchedEffect(targetPage) {
        if (pagerState.currentPage != targetPage) pagerState.animateScrollToPage(targetPage)
    }
    // 拖动落定 → 通知父级换周(父级据此把选中日平移到同一星期几)
    val settledPage = pagerState.settledPage
    LaunchedEffect(settledPage) {
        onWeekChange(LocalDate.ofEpochDay(anchorEpoch + (settledPage - WeekPageCenter) * 7L))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 月份随翻周变化:交叉淡化,不再在过阈值那一帧硬跳
            AnimatedContent(
                targetState = displayedMonday.monthValue,
                transitionSpec = {
                    fadeIn(YohakuMotion.tween(YohakuMotion.durBase)) togetherWith
                        fadeOut(YohakuMotion.tween(YohakuMotion.durFast))
                },
                label = "stripMonth",
            ) { month ->
                Text(
                    text = "$month 月",
                    style = YohakuType.copy15,
                    color = colors.neutral10,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            val today = LocalDate.now()
            val atToday = selected == today && displayedMonday.toEpochDay() == Schedule.mondayEpochDay(today.toEpochDay())
            // 「今天」:回到本周并选中今天 (若已在今天则不显示，符合课表与设计规范一致性)
            AnimatedVisibility(
                visible = !atToday,
                enter = fadeIn(YohakuMotion.tween(YohakuMotion.durFast)),
                exit = fadeOut(YohakuMotion.tween(YohakuMotion.durFast)),
            ) {
                Surface(
                    modifier = Modifier
                        .yohakuTouchTarget()
                        .clickable(onClick = onToday),
                    shape = RoundedCornerShape(YohakuDimens.radiusControl),
                    color = colors.raised,
                    border = BorderStroke(1.dp, colors.line),
                ) {
                    Text(
                        text = "今天",
                        style = YohakuType.label12,
                        color = colors.accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { page ->
            val pageMonday = LocalDate.ofEpochDay(anchorEpoch + (page - WeekPageCenter) * 7L)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.gridPadding),
            ) {
                (0..6).forEach { offset ->
                    val date = pageMonday.plusDays(offset.toLong())
                    DayCell(
                        date = date,
                        isToday = date == today,
                        isSelected = date == selected,
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 两个「周一」相隔的周数(差的整数天,向下取整到周)。 */
private fun weeksBetween(fromEpochDay: Long, toEpochDay: Long): Int =
    Math.floorDiv(toEpochDay - fromEpochDay, 7L).toInt()

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    val cellShape = RoundedCornerShape(YohakuDimens.radiusCard)

    // 严谨的选中与今天状态分级：
    // 1. 今天且被选中：accent 实底白字，最高视觉焦点
    // 2. 非今天被选中：neutral9 深色方块（纸面白字），明确指明当前查看日
    // 3. 今天但未选中：accent 极淡底色 + 细描边，温和锚定“今天”，不抢夺选中焦点
    // 4. 普通未选中日：透明背景无描边
    val targetBg = when {
        isToday && isSelected -> colors.accent
        isSelected -> colors.neutral9
        isToday -> colors.accent.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val targetOutline = when {
        isToday && !isSelected -> colors.accent.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val background by animateColorAsState(
        targetValue = targetBg,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "dayBg",
    )
    val outline by animateColorAsState(
        targetValue = targetOutline,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "dayOutline",
    )

    val dateTextColor = when {
        isToday && isSelected -> Color.White
        isSelected -> colors.paper
        isToday -> colors.accent
        else -> colors.neutral9
    }
    val weekdayColor = when {
        isToday -> colors.accent
        isSelected -> colors.neutral9
        else -> colors.neutral6
    }
    val lunarColor = when {
        isToday -> colors.accent
        isSelected -> colors.neutral9
        else -> colors.neutral6
    }
    val lunar = remember(date) { LunarDate.label(date) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(YohakuDimens.radiusControl))
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 星期文字固定 16dp 高度，确保整行严格水平基线对齐
        Box(
            modifier = Modifier.height(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = Dates.weekdayChar(date.dayOfWeek.value),
                style = YohakuType.label12,
                color = weekdayColor,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 日期数字方块：标准 36x36dp 正方块，几何严谨居中排布，彻底告别扁平拉伸
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(cellShape)
                .background(background)
                .then(
                    if (outline != Color.Transparent) {
                        Modifier.border(1.dp, outline, cellShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = YohakuType.copy15,
                color = dateTextColor,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 农历文字固定 14dp 高度，杜绝因字高或换行导致的像素抖动
        Box(
            modifier = Modifier.height(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = lunar,
                style = YohakuType.gridDate,
                color = lunarColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}
