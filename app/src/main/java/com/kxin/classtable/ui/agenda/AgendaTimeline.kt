package com.kxin.classtable.ui.agenda

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.kxin.classtable.domain.LunarDate
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AgendaPriority
import java.time.LocalDate

/**
 * 议程时间线:某一天的日程,按「全天 / 上午 / 下午 / 晚上」分段,每条给出起止时刻。
 */
@Composable
internal fun AgendaTimeline(
    date: LocalDate,
    today: LocalDate,
    events: List<AgendaEvent>,
    onAdd: () -> Unit,
    onEventClick: (AgendaEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val allDay = events.filter { it.allDay }.sortedBy { it.startAt }
    val timed = events.filterNot { it.allDay }.sortedBy { it.startAt }
    // 分段按浏览顺序固定:全天 → 上午 → 下午 → 晚上
    val segments = buildList {
        if (allDay.isNotEmpty()) add("全天" to allDay)
        listOf("上午", "下午", "晚上").forEach { label ->
            val items = timed.filter { periodLabel(it.startLocal().hour) == label }
            if (items.isNotEmpty()) add(label to items)
        }
    }
    val lunar = remember(date) { LunarDate.fullText(date) }

    Column(modifier = modifier.fillMaxSize()) {
        // 日期头:相对日 + 日期 + 农历 + 条目数,右侧「＋」
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(YohakuDimens.radiusChip),
                        color = colors.neutral2,
                    ) {
                        Text(
                            text = relativeDayLabel(date, today),
                            style = YohakuType.label12,
                            color = colors.neutral7,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dateText(date),
                        style = YohakuType.copy16,
                        color = colors.neutral10,
                    )
                }
                Text(
                    text = listOfNotNull(
                        lunar.ifEmpty { null },
                        "${events.size} 个日程",
                        when (val diff = daysUntil(date, today)) {
                            0L -> null
                            else -> if (diff > 0) "$diff 天后" else "已过 ${-diff} 天"
                        },
                    ).joinToString(" · "),
                    style = YohakuType.label12,
                    color = colors.neutral7,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            AddButton(onClick = onAdd)
        }

        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "这一天还没有日程",
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
                    top = 4.dp,
                    bottom = YohakuDimens.navReservedHeight,
                ),
                verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapTight),
            ) {
                segments.forEach { (label, items) ->
                    item(key = "seg-$label") {
                        Text(
                            text = "$label · ${items.size}",
                            style = YohakuType.label12,
                            color = colors.neutral6,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    itemsIndexed(items, key = { _, e -> e.id }) { index, event ->
                        val appear = remember(event.id) { Animatable(0f) }
                        LaunchedEffect(event.id) {
                            appear.animateTo(
                                targetValue = 1f,
                                animationSpec = YohakuMotion.tween(
                                    durationMs = YohakuMotion.durBase,
                                    easing = YohakuMotion.easeOut,
                                    delayMs = YohakuMotion.staggerDelay(index.coerceAtMost(7), 30),
                                ),
                            )
                        }
                        AgendaRow(
                            event = event,
                            modifier = Modifier
                                .animateItem()
                                .graphicsLayer {
                                    alpha = appear.value
                                    translationY = (1f - appear.value) * 20f
                                },
                            onClick = { onEventClick(event) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(
    event: AgendaEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(52.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (event.allDay) {
                Text(text = "全天", style = YohakuType.timeMono, color = colors.neutral8)
            } else {
                Text(
                    text = "%02d:%02d".format(event.startLocal().hour, event.startLocal().minute),
                    style = YohakuType.timeMono,
                    color = colors.neutral8,
                )
                Text(
                    text = "%02d:%02d".format(event.endLocal().hour, event.endLocal().minute),
                    style = YohakuType.gridDate,
                    color = colors.neutral6,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(categoryMark(event.category)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        YohakuCard(
            modifier = Modifier.weight(1f),
            containerColor = categoryTint(event.category),
        ) {
            Text(
                text = event.title,
                style = YohakuType.copy16,
                color = colors.neutral10,
            )
            val meta = buildList {
                add(event.category.label)
                if (event.location.isNotEmpty()) add("@${event.location}")
                if (event.priority != AgendaPriority.NONE) {
                    add("优先级 ${event.priority.label}")
                }
            }.joinToString(" · ")
            Text(
                text = meta,
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            if (event.note.isNotEmpty()) {
                Text(
                    text = event.note,
                    style = YohakuType.copy13,
                    color = colors.neutral7,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(colors.raised)
            .border(BorderStroke(1.dp, colors.line), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "＋",
            style = YohakuType.title20,
            color = colors.accent,
            textAlign = TextAlign.Center,
        )
    }
}
