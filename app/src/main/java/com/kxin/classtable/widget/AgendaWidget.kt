package com.kxin.classtable.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kxin.classtable.domain.model.AgendaEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 4×2「日程」:一列近期的日程与倒计时。
 *
 * 每行左边是时刻(今天的定时条目写「HH:MM」,更远的写「M/D」,全天写「全天」),
 * 中间是标题与分类 / 地点,右边写「还有 N 天」;正在进行中的那条带 accent 左边条。
 * 数据源见 [WidgetData.upcomingAgenda] —— 已结束的条目不出现在这里。
 */
class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = WidgetPrefsStore.read(context)
        val skin = skinOf(context, prefs)
        // 多取一条用来判断「还有几条没显示」
        val events = WidgetData.upcomingAgenda(context, prefs.maxRows + 1)
        provideContent { AgendaContent(events, prefs, skin) }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

@Composable
fun AgendaContent(
    events: List<AgendaEvent>,
    prefs: WidgetPrefs,
    skin: WidgetSkin,
) {
    val now = System.currentTimeMillis()
    WidgetFrame(skin, prefs) {
        Column {
            WidgetTitle("日程", skin)
            when {
                events.isEmpty() -> Text(
                    text = "近期没有日程",
                    style = TextStyle(fontSize = 12.sp, color = ColorProvider(skin.sub)),
                    maxLines = 2,
                )
                else -> events.take(prefs.maxRows).forEach { event ->
                    AgendaLine(event, prefs, skin, now)
                }
            }
            if (events.size > prefs.maxRows) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = "还有 ${events.size - prefs.maxRows} 条",
                    style = TextStyle(fontSize = 10.sp, color = ColorProvider(skin.sub)),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 一条日程在小组件里的一行。 */
@Composable
private fun AgendaLine(
    event: AgendaEvent,
    prefs: WidgetPrefs,
    skin: WidgetSkin,
    now: Long,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = if (prefs.compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (event.isOngoing(now)) {
            Box(GlanceModifier.width(3.dp).height(18.dp).background(skin.accent)) {}
            Spacer(GlanceModifier.width(6.dp))
        }
        if (prefs.showTime) {
            Text(
                text = timeText(event),
                style = TextStyle(fontSize = 11.sp, color = ColorProvider(skin.sub)),
                modifier = GlanceModifier.width(46.dp),
                maxLines = 1,
            )
        }
        // 标题列吃掉剩余空间(Glance 没有 weight,用 defaultWeight 给同排的「还有 N 天」留出位置)
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = event.title,
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(skin.ink),
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            val meta = buildList {
                add(event.category.label)
                if (prefs.showLocation && event.location.isNotBlank()) add(event.location)
            }.joinToString(" · ")
            Text(
                text = meta,
                style = TextStyle(fontSize = 10.sp, color = ColorProvider(skin.sub)),
                maxLines = 1,
            )
        }
        Text(
            text = daysText(event, now),
            style = TextStyle(fontSize = 10.sp, color = ColorProvider(skin.sub)),
            modifier = GlanceModifier.padding(start = 6.dp),
            maxLines = 1,
        )
    }
}

private fun timeText(event: AgendaEvent): String {
    if (event.allDay) return "全天"
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDate()
    return if (day == LocalDate.now(zone)) {
        java.time.LocalTime.ofInstant(Instant.ofEpochMilli(event.startAt), zone)
            .let { "%02d:%02d".format(it.hour, it.minute) }
    } else {
        "%d/%d".format(day.monthValue, day.dayOfMonth)
    }
}

private fun daysText(event: AgendaEvent, now: Long): String {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, day)
    return if (days <= 0L) "今天" else "还有 $days 天"
}
