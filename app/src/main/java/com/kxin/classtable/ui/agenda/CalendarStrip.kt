package com.kxin.classtable.ui.agenda

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * 日历周条:一行七天,每天「星期 / 日号 / 农历」。
 * 今天 accent 实底,选中日 accent 描边;点任意一天即选中,‹ › 翻周,「今天」跳回。
 */
@Composable
internal fun CalendarStrip(
    monday: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${monday.monthValue}月",
                style = YohakuType.copy15,
                color = colors.neutral10,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Stepper("‹", onPrevWeek)
            Stepper("›", onNextWeek)
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.gridPadding),
        ) {
            (0..6).forEach { offset ->
                val date = monday.plusDays(offset.toLong())
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

@Composable
private fun Stepper(glyph: String, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    Text(
        text = glyph,
        style = YohakuType.copy16,
        color = colors.neutral7,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(30.dp)
            .clip(RoundedCornerShape(YohakuDimens.radiusChip))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}
