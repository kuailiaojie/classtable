package com.kxin.classtable.domain

import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType

/**
 * 课程的周次计划:四档语义 + 精确周次集合。
 *
 * - [WeekType.EVERY_WEEK] 每周(不看范围)
 * - [WeekType.ODD_WEEK] / [WeekType.EVEN_WEEK] 单/双周,范围 [start]..[end] **生效**
 * - [WeekType.CUSTOM] 精确周次 [weeks];为空时回退旧数据的连续范围 [start]..[end]
 *
 * 单双周按**学期绝对周次**算奇偶(与适配器 `w % 2 === 0` 一致),不是相对范围起点 ——
 * 所以「第3-19的双周」= 第 4、6、…、18 周。
 */
object WeekSpec {

    /** 连续且从第 1 周起的段,长到什么程度就当作「整学期」(沿用导入侧的旧启发式)。 */
    private const val FULL_RUN_MIN = 15

    /** 不知道学期周数时的上行假设:课程详情页也按 30 周截断,保持一致。 */
    private const val ASSUMED_MAX_WEEKS = 30

    data class Spec(
        val type: WeekType,
        val start: Int,
        val end: Int,
        val weeks: List<Int> = emptyList(),
    )

    /**
     * 适配器/识别给出的 `weeks[]` → Spec:能整体表达成档位就用档位,否则退回 CUSTOM + 精确集合。
     * 逐条精确,不再把离散周次压成 min..max。
     */
    fun everySpec(weekCount: Int): Spec =
        Spec(WeekType.EVERY_WEEK, 1, if (weekCount > 0) weekCount else ASSUMED_MAX_WEEKS)

    /** 认不出来的文本按「每周」处理(填错一格不该把整行课丢掉)。 */
    fun parseOrEvery(text: String, weekCount: Int): Spec = parse(text, weekCount) ?: everySpec(weekCount)

    fun fromWeeks(weeks: List<Int>, weekCount: Int): Spec {
        val set = weeks.filter { it > 0 }.distinct().sorted()
        if (set.isEmpty()) return everySpec(weekCount)
        val min = set.first()
        val max = set.last()
        // 从第 1 周起连续、且覆盖到学期末(或足够长)→ 每周
        val contiguousFromOne = set.size == max
        if (contiguousFromOne && (max >= FULL_RUN_MIN || (weekCount > 0 && max >= weekCount))) {
            return everySpec(weekCount)
        }
        // 恰好是 [min,max] 内的全部奇数/偶数 → 单周/双周,范围刚好贴合
        if (set == (min..max).filter { it % 2 == 1 }) return Spec(WeekType.ODD_WEEK, min, max)
        if (set == (min..max).filter { it % 2 == 0 }) return Spec(WeekType.EVEN_WEEK, min, max)
        return Spec(WeekType.CUSTOM, min, max, set)
    }

    /**
     * 文本 → Spec。支持:
     * `每周` / `单周` / `双周`、`3-19`、`1,3,5`、`1-3,5`、`3-19双周`、`3-19单周`。
     * 认不出来返回 null(调用方决定丢弃还是回退)。
     */
    fun parse(text: String, weekCount: Int): Spec? {
        val count = if (weekCount > 0) weekCount else ASSUMED_MAX_WEEKS
        val t = text.trim().replace(Regex("\\s+"), "")
        if (t.isEmpty()) return null

        val hasEvery = t.contains("每周") || t.contains("全周")
        val hasOdd = t.contains("单")
        val hasEven = t.contains("双")

        // 去掉文字标记,剩下的应该只有数字与分隔符
        var rest = t
        for (mark in listOf("每周", "全周", "单双周", "单周", "双周", "单", "双", "周")) {
            rest = rest.replace(mark, "")
        }
        rest = rest.trim(',', '、', '-', '–', '—', '~', '至').trim()

        // 只有档位、没有数字
        if (rest.isEmpty()) return when {
            hasEvery -> Spec(WeekType.EVERY_WEEK, 1, count)
            hasOdd && !hasEven -> Spec(WeekType.ODD_WEEK, 1, count)
            hasEven && !hasOdd -> Spec(WeekType.EVEN_WEEK, 1, count)
            else -> null
        }

        // 单个 "a-b" 且带奇偶标记:保留原始范围,显示成「第a-b周(单/双周)」
        val single = Regex("^(\\d+)[-–—~至](\\d+)$").find(rest)
        if (single != null) {
            val a = single.groupValues[1].toInt()
            val b = single.groupValues[2].toInt()
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            return when {
                hasEvery -> Spec(WeekType.EVERY_WEEK, 1, count)
                hasOdd && !hasEven -> Spec(WeekType.ODD_WEEK, lo, hi)
                hasEven && !hasOdd -> Spec(WeekType.EVEN_WEEK, lo, hi)
                else -> Spec(WeekType.CUSTOM, lo, hi, (lo..hi).toList())
            }
        }

        val weeks = parseExpression(rest) ?: return null
        if (weeks.isEmpty()) return null
        return fromWeeks(weeks, count)
    }

    /** `1,3,5` / `1-3,5` → 周次列表;格式不对返回 null。 */
    private fun parseExpression(s: String): List<Int>? {
        val out = sortedSetOf<Int>()
        for (part in s.split(',', '、')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val m = Regex("^(\\d+)[-–—~至](\\d+)$").find(p)
            if (m != null) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                if (hi - lo > 100) return null
                for (w in lo..hi) out.add(w)
            } else {
                out.add(p.toIntOrNull() ?: return null)
            }
        }
        return out.filter { it > 0 }
    }

    /** 精确周次 ↔ CSV 字符串(本地库与远端都存这个形态)。 */
    fun encode(weeks: List<Int>): String =
        weeks.filter { it > 0 }.distinct().sorted().joinToString(",")

    fun decode(csv: String): List<Int> =
        csv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()

    /** 显示文案:每周 / 单周 / 双周 / 第3-19周(双周) / 第1-3,5,7-9周。 */
    fun text(
        type: WeekType,
        start: Int,
        end: Int,
        weeks: List<Int> = emptyList(),
        weekCount: Int = 0,
    ): String {
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        return when (type) {
            WeekType.EVERY_WEEK -> "每周"
            WeekType.ODD_WEEK ->
                if (coversAll(lo, hi, weekCount)) "单周" else "${rangeText(lo, hi)}(单周)"
            WeekType.EVEN_WEEK ->
                if (coversAll(lo, hi, weekCount)) "双周" else "${rangeText(lo, hi)}(双周)"
            WeekType.CUSTOM -> {
                val set = weeks.filter { it > 0 }.distinct().sorted()
                if (set.isNotEmpty()) compactText(set) else rangeText(lo, hi)
            }
        }
    }

    /** [text] 的 [Spec] 便捷重载。 */
    fun text(spec: Spec, weekCount: Int = 0): String =
        text(spec.type, spec.start, spec.end, spec.weeks, weekCount)

    /** 该计划实际会上的周次(编辑器切到「自定义」时用来把当前档位铺进网格)。 */
    fun weeksOf(spec: Spec, weekCount: Int): List<Int> = when (spec.type) {
        WeekType.EVERY_WEEK ->
            (1..if (weekCount > 0) weekCount else ASSUMED_MAX_WEEKS).toList()
        WeekType.ODD_WEEK -> (spec.start..spec.end).filter { it % 2 == 1 }
        WeekType.EVEN_WEEK -> (spec.start..spec.end).filter { it % 2 == 0 }
        WeekType.CUSTOM -> spec.weeks
    }

    /** 旧数据(自定义 = 连续范围,没有精确集合)也能读回来编辑。 */
    fun of(course: Course, weekCount: Int): Spec {
        val weeks = when {
            course.weeks.isNotEmpty() -> course.weeks.filter { it > 0 }.distinct().sorted()
            course.weekType == WeekType.CUSTOM && course.weekStart <= course.weekEnd ->
                (course.weekStart..course.weekEnd).toList()
            else -> emptyList()
        }
        return Spec(course.weekType, course.weekStart, course.weekEnd, weeks)
    }

    /**
     * 是否覆盖整个学期。学期末那周的奇偶决定这一档的最后一周是 [weekCount] 还是 [weekCount]-1,
     * 所以用 `hi >= weekCount - 1` 判断 —— 否则「1-19 单周」这种(20 周学期里的全部单周)
     * 会被写成带范围的啰嗦文案,而不是干脆的「单周」。
     */
    private fun coversAll(lo: Int, hi: Int, weekCount: Int): Boolean {
        if (lo > 1) return false
        return if (weekCount > 0) hi >= weekCount - 1 else hi >= FULL_RUN_MIN
    }

    private fun rangeText(lo: Int, hi: Int): String = if (lo >= hi) "第${lo}周" else "第${lo}-${hi}周"

    /** 连续段压缩:[1,2,3,5,7,8,9] → 「第1-3,5,7-9周」 */
    private fun compactText(set: List<Int>): String {
        val parts = ArrayList<String>()
        var i = 0
        while (i < set.size) {
            var j = i
            while (j + 1 < set.size && set[j + 1] == set[j] + 1) j++
            parts += if (j > i) "${set[i]}-${set[j]}" else "${set[i]}"
            i = j + 1
        }
        return "第" + parts.joinToString(",") + "周"
    }
}

/** 「每周 / 单周 / 双周 / 第3-19周(双周)」;不知道学期周数时传 0。 */
fun Course.weeksText(weekCount: Int = 0): String =
    WeekSpec.text(weekType, weekStart, weekEnd, weeks, weekCount)
