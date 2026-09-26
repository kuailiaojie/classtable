package com.kxin.classtable.ui.agenda

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 倒计时列表:与议程同一批数据,换个看法 ——
 * 按「还剩几天」排序,进行中的在前,已结束的折叠在后面。
 */
@Composable
internal fun CountdownList(
    events: List<AgendaEvent>,
    now: Long,
    onAdd: () -> Unit,
    onEventClick: (AgendaEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val upcoming = events.filter { it.endAt >= now }.sortedBy { it.startAt }
    val finished = events.filter { it.endAt < now }.sortedByDescending { it.endAt }
    var showFinished by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = YohakuDimens.screenPadding,
            end = YohakuDimens.screenPadding,
            top = YohakuDimens.gapCard,
            bottom = YohakuDimens.gapCard + YohakuDimens.navReservedHeight,
        ),
        verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapCard),
    ) {
        if (upcoming.isEmpty()) {
            item(key = "empty") { EmptyCountdownCard(onAdd = onAdd) }
        } else {
            items(upcoming, key = { it.id }) { event ->
                CountdownCard(
                    event = event,
                    today = today,
                    finished = false,
                    onClick = { onEventClick(event) },
                )
            }
        }

        if (finished.isNotEmpty()) {
            item(key = "finished-header") {
                FinishedHeader(
                    count = finished.size,
                    expanded = showFinished,
                    onToggle = { showFinished = !showFinished },
                )
            }
            if (showFinished) {
                items(finished, key = { it.id }) { event ->
                    CountdownCard(
                        event = event,
                        today = today,
                        finished = true,
                        onClick = { onEventClick(event) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownCard(
    event: AgendaEvent,
    today: LocalDate,
    finished: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val startDay = event.startLocal().toLocalDate()
    val endDay = event.endLocal().toLocalDate()
    val daysToStart = daysUntil(startDay, today)
    val daysOverdue = -daysUntil(endDay, today)
    val ongoing = !finished && daysToStart <= 0

    val rightText = when {
        finished -> "逾期 ${abs(daysOverdue)} 天"
        daysToStart > 0 -> "还有 $daysToStart 天"
        else -> "进行中"
    }
    val rightColor = if (finished) colors.error else colors.accent

    YohakuCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        containerColor = if (finished) null else categoryTint(event.category),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountdownBadge(
                event = event,
                daysToStart = daysToStart,
                finished = finished,
                ongoing = ongoing,
            )
            Spacer(modifier = Modifier.width(YohakuDimens.gapCard))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = YohakuType.copy16,
                    color = if (finished) colors.neutral7 else colors.neutral10,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOf(
                        event.category.label,
                        dateText(startDay),
                        if (event.allDay) "全天" else timeRangeText(event.startLocal(), event.endLocal()),
                    ).joinToString(" · "),
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
            }
            Text(
                text = rightText,
                style = YohakuType.label12,
                color = rightColor,
            )
        }
    }
}

@Composable
private fun CountdownBadge(
    event: AgendaEvent,
    daysToStart: Long,
    finished: Boolean,
    ongoing: Boolean,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusControl)
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 56.dp)
            .clip(shape)
            .background(colors.neutral2),
        contentAlignment = Alignment.Center,
    ) {
        if (finished) {
            Text(
                text = "✓\n已结束",
                style = YohakuType.gridDate,
                color = colors.neutral6,
                textAlign = TextAlign.Center,
            )
        } else {
            val top = when {
                ongoing -> "进行中"
                daysToStart <= 0L -> "今天"
                else -> daysToStart.toString()
            }
            val bottom = when {
                ongoing -> event.category.label
                daysToStart <= 0L -> "今天"
                else -> "天"
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = top,
                    style = if (ongoing) YohakuType.label12 else YohakuType.title20,
                    color = colors.accent,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = bottom,
                    style = YohakuType.gridDate,
                    color = colors.neutral6,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun EmptyCountdownCard(onAdd: () -> Unit) {
    val colors = LocalYohakuColors.current
    YohakuCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "没有进行中的倒计时",
                    style = YohakuType.copy16,
                    color = colors.neutral10,
                )
                Text(
                    text = "新的考试、活动或待办会排在这里",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
            }
            Text(
                text = "添加",
                style = YohakuType.copy13,
                color = colors.accent,
                modifier = Modifier
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun FinishedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalYohakuColors.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已结束 $count",
            style = YohakuType.label12,
            color = colors.neutral7,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "⌄",
            style = YohakuType.copy15,
            color = colors.neutral6,
            modifier = Modifier
                .padding(end = 4.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}
