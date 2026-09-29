package com.kxin.classtable.notify

import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** 「预览实时活动」用独立的静音键前缀:预览不会覆盖真实提醒,也能单独取消。 */
const val PREVIEW_MUTE_PREFIX = "preview:"

/**
 * 实时活动的一个连续时段。
 *
 * 一节课可能跨多个小节(如第 3–4 节),这里每小节一段,段与段之间的空档就是课间 ——
 * 状态推进正是靠段与段之间的空档算出「课间中」这一相。
 */
data class LiveUpdateSegment(val startAtMillis: Long, val endAtMillis: Long)

/** 实时活动的四个阶段。相变的时刻即 [LiveUpdate.refreshBoundaries]。 */
enum class LiveUpdatePhase { BEFORE_CLASS, IN_CLASS, BREAK, FINISHED }

/**
 * 某一时刻的展示状态:**只跟相位走的一行状态文案**、下一个相变时刻(即倒计时目标)、以及进度。
 *
 * 文案里不写分钟数 —— 秒级倒计时交给系统的 `when` + Chronometer 自己走(见
 * [Notifier.buildLiveUpdate]),我们只在相位边界、以及每分钟(为了推进确定态进度条)原地重画。
 */
data class LiveUpdateStatus(
    val phase: LiveUpdatePhase,
    val statusText: String,
    val nextTransitionAtMillis: Long?,
    val progressPercent: Int? = null,
)

/**
 * 实时活动需要展示的一节课(课前倒计时 → 上课中 → 课间中 → 已下课)。
 *
 * **文案只跟相位走,秒级倒计时交给系统**:通知上写 `when` + Chronometer,由系统自己走秒;
 * 我们不为了刷新分钟数而重发。服务按 [nextRefreshAtMillis] 醒来只做两件事:相位切换时换一帧、
 * 以及上课期间每分钟推进一次确定态进度条(进度条系统不会自己动)。
 *
 * **通知身份**([notificationIdentityAt])只包含相位与下一个相变时刻:分钟刷新期间身份不变,
 * 原地更新同一条通知(胶囊不会重弹);跨到新相位时身份变化,换到另一个通知槽 —— 让胶囊重新
 * 展开一次,这正是「状态真的变了才提醒」的表达方式。
 */
data class LiveUpdate(
    val courseId: String,
    val name: String,
    val location: String,
    val timeText: String,
    val muteKey: String,
    val segments: List<LiveUpdateSegment>,
) {
    fun startAtMillis(): Long? = segments.minByOrNull { it.startAtMillis }?.startAtMillis

    fun endAtMillis(): Long? = segments.maxByOrNull { it.endAtMillis }?.endAtMillis

    fun isPreview(): Boolean = muteKey.startsWith(PREVIEW_MUTE_PREFIX)

    /** 课程结束后就该收掉(服务与恢复时的第一道判断)。 */
    fun shouldStop(now: Long = System.currentTimeMillis()): Boolean {
        val end = endAtMillis() ?: return true
        return now >= end
    }

    /** 需要唤醒的时刻:每个分段的起止。排程的边界闹钟与服务都以它为准。 */
    fun refreshBoundaries(): List<Long> = segments
        .flatMap { listOf(it.startAtMillis, it.endAtMillis) }
        .distinct()
        .sorted()

    /**
     * 下一次需要重绘通知的时刻:最近的相位边界,或下一个整分(用来推进确定态进度条)。
     *
     * 对齐到目标时刻的**秒**而不是墙上的整分:进度按「当前小节起点 → 下一相变」算百分比,
     * 重画的时刻贴着相变那一刻的秒,进度才不会差一秒。
     */
    fun nextRefreshAtMillis(now: Long = System.currentTimeMillis()): Long? {
        if (shouldStop(now)) return null
        val boundary = refreshBoundaries().firstOrNull { it > now }
        val minuteTick = statusAt(now).nextTransitionAtMillis
            ?.takeIf { it > now }
            ?.let { target ->
                val remaining = target - now
                now + (remaining - 1L) % 60_000L + 1L
            }
        return listOfNotNull(boundary, minuteTick).minOrNull()
    }

    fun statusAt(now: Long = System.currentTimeMillis()): LiveUpdateStatus {
        val timeline = segments.filter { it.endAtMillis > it.startAtMillis }.sortedBy { it.startAtMillis }
        val first = timeline.firstOrNull()
        val last = timeline.lastOrNull()
        if (first == null || last == null) {
            return LiveUpdateStatus(LiveUpdatePhase.BEFORE_CLASS, "即将开始", null)
        }
        if (now < first.startAtMillis) {
            return LiveUpdateStatus(LiveUpdatePhase.BEFORE_CLASS, "即将开始", first.startAtMillis)
        }
        timeline.forEachIndexed { index, segment ->
            if (now < segment.endAtMillis) {
                val next = timeline.getOrNull(index + 1)
                val transition = next?.startAtMillis ?: last.endAtMillis
                return LiveUpdateStatus(
                    phase = LiveUpdatePhase.IN_CLASS,
                    statusText = "上课中",
                    nextTransitionAtMillis = transition,
                    progressPercent = elapsedPercent(now, segment.startAtMillis, transition),
                )
            }
            val next = timeline.getOrNull(index + 1)
            if (next != null && now < next.startAtMillis) {
                return LiveUpdateStatus(
                    phase = LiveUpdatePhase.BREAK,
                    statusText = "课间中",
                    nextTransitionAtMillis = next.startAtMillis,
                    progressPercent = elapsedPercent(now, segment.endAtMillis, next.startAtMillis),
                )
            }
        }
        return LiveUpdateStatus(LiveUpdatePhase.FINISHED, "已下课", null)
    }

    /** 写入通知 extras,供服务把「这是哪一帧」带出去([notificationIdentityAt])。 */
    fun notificationIdentityAt(now: Long = System.currentTimeMillis()): String {
        val status = statusAt(now)
        return "$courseId|$muteKey|${status.phase}|${status.nextTransitionAtMillis}"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("courseId", courseId)
        .put("name", name)
        .put("location", location)
        .put("timeText", timeText)
        .put("muteKey", muteKey)
        .put(
            "segments",
            JSONArray().apply {
                segments.forEach { segment ->
                    put(
                        JSONObject()
                            .put("start", segment.startAtMillis)
                            .put("end", segment.endAtMillis),
                    )
                }
            },
        )

    companion object {
        fun fromJson(json: JSONObject): LiveUpdate {
            val segments = json.optJSONArray("segments")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { item ->
                        LiveUpdateSegment(item.optLong("start"), item.optLong("end"))
                    }
                }
            }.orEmpty().filter { it.endAtMillis > it.startAtMillis }
            return LiveUpdate(
                courseId = json.optString("courseId"),
                name = json.optString("name"),
                location = json.optString("location"),
                timeText = json.optString("timeText"),
                muteKey = json.optString("muteKey"),
                segments = segments,
            )
        }

        /**
         * 把课程换算成当天的时间分段。
         *
         * 自定时间课程(startPeriod <= 0)只有一段;普通课程按小节逐段展开,中间的课间自然成为
         * 段间空档。最后再按课程自己钉住的整体起止裁一次,保证与课表其它地方显示的时间一致。
         */
        fun segmentsOf(
            course: Course,
            date: LocalDate,
            periods: List<Schedule.Period>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<LiveUpdateSegment> {
            val startMinute = Schedule.courseStartMinute(course, periods) ?: return emptyList()
            val endMinute = Schedule.courseEndMinute(course, periods) ?: return emptyList()
            if (endMinute <= startMinute) return emptyList()
            val spanStart = date.atTime(startMinute / 60, startMinute % 60).atZone(zone).toInstant().toEpochMilli()
            val spanEnd = date.atTime(endMinute / 60, endMinute % 60).atZone(zone).toInstant().toEpochMilli()
            if (course.isCustomScheduled()) return listOf(LiveUpdateSegment(spanStart, spanEnd))
            val perPeriod = (course.startPeriod..course.endPeriod).mapNotNull { index ->
                val period = periods.getOrNull(index - 1) ?: return@mapNotNull null
                val start = date.atTime(period.start / 60, period.start % 60).atZone(zone).toInstant().toEpochMilli()
                val end = date.atTime(period.end / 60, period.end % 60).atZone(zone).toInstant().toEpochMilli()
                LiveUpdateSegment(start, end).takeIf { it.endAtMillis > it.startAtMillis }
            }
            if (perPeriod.isEmpty()) return listOf(LiveUpdateSegment(spanStart, spanEnd))
            val clipped = perPeriod.mapNotNull { segment ->
                val start = maxOf(segment.startAtMillis, spanStart)
                val end = minOf(segment.endAtMillis, spanEnd)
                LiveUpdateSegment(start, end).takeIf { it.endAtMillis > it.startAtMillis }
            }
            return clipped.ifEmpty { listOf(LiveUpdateSegment(spanStart, spanEnd)) }
        }
    }
}

/** 分段在 Intent extra 里的编码格式:"start:end;start:end"。 */
internal fun encodeLiveSegments(segments: List<LiveUpdateSegment>): String =
    segments.joinToString(";") { "${it.startAtMillis}:${it.endAtMillis}" }

internal fun decodeLiveSegments(value: String): List<LiveUpdateSegment> = value
    .split(';')
    .mapNotNull { encoded ->
        val start = encoded.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
        val end = encoded.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
        LiveUpdateSegment(start, end).takeIf { it.endAtMillis > it.startAtMillis }
    }

private fun elapsedPercent(nowMillis: Long, startMillis: Long, endMillis: Long): Int {
    val duration = endMillis - startMillis
    if (duration <= 0L) return 0
    return ((nowMillis - startMillis).coerceIn(0L, duration) * 100L / duration).toInt()
}
