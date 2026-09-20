package com.kxin.classtable.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 节假日服务返回的一天。[wage]:3 = 法定节假日,2 = 普通假日,1 = 上班(调休补班)。
 */
data class HolidayDay(
    val date: LocalDate,
    val name: String,
    val isRest: Boolean,
    val wage: Int = 2,
)

/** 一个法定假期:放假日,以及需要补的班。 */
data class HolidayPlan(
    val name: String,
    val restDates: List<LocalDate>,
    val makeups: List<HolidayMakeup>,
)

/** 一个补班日,以及它**最可能**替代的那个教学日期(具体某天)。学校细则可能不同,只作建议。 */
data class HolidayMakeup(val date: LocalDate, val source: LocalDate?)

/**
 * 节假日数据源:公开接口、无需 key。抓取只生成预览,不写任何课表数据。
 */
object HolidayClient {
    private const val ENDPOINT = "https://timor.tech/api/holiday/year/%d/"

    /** 抓取某年的节假日与调休安排。 */
    suspend fun fetch(year: Int): List<HolidayDay> = withContext(Dispatchers.IO) {
        require(year in 2000..2100) { "年份需在 2000–2100 之间" }
        val connection = URL(ENDPOINT.format(year)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode == 200) { "节假日服务暂时不可用,请稍后重试" }
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 512 * 1024) { "节假日数据过大" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            parse(bytes.toString(Charsets.UTF_8), year)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(body: String, year: Int): List<HolidayDay> {
        val root = JSONObject(body)
        require(root.optInt("code", -1) == 0) { "节假日服务返回异常" }
        val holidays = root.optJSONObject("holiday") ?: error("节假日数据缺失")
        val out = ArrayList<HolidayDay>()
        holidays.keys().forEach { key ->
            val entry = holidays.optJSONObject(key) ?: return@forEach
            val text = entry.optString("date").takeIf { it.isNotBlank() } ?: "$year-$key"
            val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return@forEach
            require(date.year == year) { "节假日年份不匹配" }
            out += HolidayDay(
                date = date,
                name = entry.optString("name").take(80).ifBlank { "调休" },
                isRest = entry.optBoolean("holiday", false),
                wage = entry.optInt("wage", 2),
            )
        }
        val result = out.sortedBy { it.date }
        require(result.isNotEmpty()) { "$year 年节假日尚未公布" }
        require(result.map { it.date }.distinct().size == result.size) { "节假日日期重复" }
        return result
    }
}

/**
 * 把放假日按连续区间分组成假期,并给每个补班日配上它替代的那个工作日。
 *
 * 规则(与参考实现逐条一致):
 * - 一个假期只需补「它占掉的本该上课的工作日」减去「其中的法定节假日」;
 * - 补班日从假期前后**最近的**上班日里取,按到假期的距离排序;
 * - 一个假期里第 k 个补班日,对应被占掉的几个工作日里**靠后的**那几个(按时间顺序配对)。
 *
 * 这只是建议:不同学校细则不同,预览里可以逐条改或直接不勾。
 */
fun planHolidays(days: List<HolidayDay>): List<HolidayPlan> {
    val sorted = days.sortedBy { it.date }
    val runs = buildList<MutableList<HolidayDay>> {
        sorted.filter { it.isRest }.forEach { rest ->
            val current = lastOrNull()
            if (current != null && ChronoUnit.DAYS.between(current.last().date, rest.date) == 1L) {
                current += rest
            } else {
                add(mutableListOf(rest))
            }
        }
    }
    val unassigned = sorted.filterNot { it.isRest }.toMutableList()
    val owned = runs.map { run ->
        val borrowed = run.count { it.date.dayOfWeek.value <= 5 }
        val statutory = run.count { it.wage >= 3 }.takeIf { it > 0 && borrowed > 0 }
            ?: minOf(1, borrowed)
        val picked = unassigned.sortedBy { distanceToRun(run, it.date) }
            .take((borrowed - statutory).coerceAtLeast(0))
        unassigned.removeAll(picked)
        picked.sortedBy { it.date }
    }.toMutableList()
    unassigned.forEach { leftover ->
        val index = runs.indices.minByOrNull { distanceToRun(runs[it], leftover.date) } ?: return@forEach
        owned[index] = (owned[index] + leftover).sortedBy { it.date }
    }
    return runs.mapIndexed { index, run ->
        val borrowed = run.map { it.date }.filter { it.dayOfWeek.value <= 5 }
        val own = owned[index]
        val targets = borrowed.takeLast(own.size)
        HolidayPlan(
            name = run.first().name,
            restDates = run.map { it.date },
            makeups = own.mapIndexed { position, makeup ->
                HolidayMakeup(makeup.date, targets.getOrNull(position))
            },
        )
    }
}

private fun distanceToRun(run: List<HolidayDay>, date: LocalDate): Long = when {
    date < run.first().date -> ChronoUnit.DAYS.between(date, run.first().date)
    date > run.last().date -> ChronoUnit.DAYS.between(run.last().date, date)
    else -> 0L
}
