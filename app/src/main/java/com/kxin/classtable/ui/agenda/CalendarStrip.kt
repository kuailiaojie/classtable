package com.kxin.classtable.ui.agenda

import androidx.compose.animation.animateColorAsState
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
            Text(
                text = "${displayedMonday.monthValue}月",
                style = YohakuType.copy15,
                color = colors.neutral10,
            )
            Spacer(modifier = Modifier.weight(1f))
            // 「今天」:回到本周并选中今天
            Surface(
                modifier = Modifier.clickable(onClick = onToday),
                shape = RoundedCornerShape(YohakuDimens.radiusChip),
                color = colors.raised,
                border = BorderStroke(1.dp, colors.line),
            ) {
                Text(
                    text = "↩ 今天",
                    style = YohakuType.label12,
                    color = colors.accent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
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
    val shape = RoundedCornerShape(YohakuDimens.radiusChip)
    // 选中 / 今天的底色与描边都带缓动(切换日期时不硬跳)
    val background by animateColorAsState(
        targetValue = if (isToday) colors.accent else Color.Transparent,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "dayBg",
    )
    val outline by animateColorAsState(
        targetValue = if (isSelected && !isToday) colors.accent else Color.Transparent,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "dayOutline",
    )
    val lunar = remember(date) { LunarDate.label(date) }

    Column(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "一二三四五六日"[date.dayOfWeek.value - 1].toString(),
            style = YohakuType.label12,
            color = if (isToday) colors.accent else colors.neutral6,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 2.dp)
                .clip(shape)
                .background(background)
                .then(if (outline != Color.Transparent) Modifier.border(1.dp, outline, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = YohakuType.copy15,
                color = if (isToday) Color.White else colors.neutral9,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = lunar,
            style = YohakuType.gridDate,
            color = if (isToday) colors.accent else colors.neutral6,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
