package com.kxin.classtable.domain

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * 节假日数据源:公开接口、无需 key,但**按顺序试多个源**。
 *
 * 这类第三方服务会抽风、被限流或被拦(国内网络尤其),之前只挂一个源,一失败整个功能就不可用 ——
 * 用户看到的就是「节假日服务暂时不可用」。现在依次尝试:带「法定/补班」类型的完整接口,
 * 再退到开源节假日数据(holiday-cn)的两个 CDN;任何一家能用就够。抓取只生成预览,不写课表数据。
 */
object HolidayClient {
    private const val PREFS = "holiday_cache"
    private const val KEY_DAYS = "days_"
    private const val KEY_FETCHED_AT = "fetched_at_"

    /** 缓存新鲜期(天):节假日公告一年只动几次,30 天内不重复联网。 */
    private const val FRESH_DAYS = 30L

    private val sources = listOf<(Int) -> String>(
        { year -> "https://timor.tech/api/holiday/year/$year/" },
        { year -> "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json" },
        { year -> "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json" },
    )

    /**
     * 取某年的节假日。
     *
     * 三级策略(与参考实现一致):
     * 1) 缓存还新鲜(30 天内抓过)就直接用,不联网;
     * 2) 否则依次试多个数据源,成功即写入缓存;
     * 3) **全部失败就退回过期缓存** —— 这条是关键:节假日数据一年只变几次,拿上一次的结果
     *    也远好过甩一个「服务不可用」;真的一点缓存都没有才报错。
     */
    suspend fun fetch(context: Context, year: Int): List<HolidayDay> {
        require(year in 2000..2100) { "年份需在 2000–2100 之间" }
        val cached = readCache(context, year)
        if (cached.days.isNotEmpty() && isFresh(cached.fetchedAt)) return cached.days

        val failures = mutableListOf<String>()
        for (build in sources) {
            val url = build(year)
            val days = try {
                request(url, year)
            } catch (e: Exception) {
                failures += "${url.substringAfter("//").substringBefore("/")}(${e.message})"
                continue
            }
            if (days.isNotEmpty()) {
                writeCache(context, year, days)
                return days
            }
        }
        if (cached.days.isNotEmpty()) return cached.days
        error(
            "节假日服务暂时不可用,请稍后重试(已尝试 ${sources.size} 个来源:" +
                failures.joinToString("、") + ")",
        )
    }

    private data class Cached(val days: List<HolidayDay>, val fetchedAt: Long)

    private fun isFresh(fetchedAt: Long): Boolean =
        fetchedAt > 0L && ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()).toLocalDate(),
            LocalDate.now(),
        ) < FRESH_DAYS

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readCache(context: Context, year: Int): Cached {
        val p = prefs(context)
        val raw = p.getString(KEY_DAYS + year, null).orEmpty()
        val days = raw.split(';').mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size < 4) return@mapNotNull null
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
            HolidayDay(date, parts[1], parts[2] == "1", parts[3].toIntOrNull() ?: 2)
        }
        return Cached(days, p.getLong(KEY_FETCHED_AT + year, 0L))
    }

    private fun writeCache(context: Context, year: Int, days: List<HolidayDay>) {
        prefs(context).edit()
            .putString(
                KEY_DAYS + year,
                days.joinToString(";") { "${it.date}|${it.name}|${if (it.isRest) 1 else 0}|${it.wage}" },
            )
            .putLong(KEY_FETCHED_AT + year, System.currentTimeMillis())
            .apply()
    }

    private suspend fun request(url: String, year: Int): List<HolidayDay> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
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
            parseAny(bytes.toString(Charsets.UTF_8), year)
        } finally {
            connection.disconnect()
        }
    }

    /** 两种返回格式:timor(带法定/补班类型)与 holiday-cn(只有休/班)。按字段名分辨。 */
    internal fun parseAny(body: String, year: Int): List<HolidayDay> =
        if (body.contains("\"days\"")) parseHolidayCn(body, year) else parseTimor(body, year)

    /** timor.tech:一年一个对象,wage 3 = 法定节假日 / 2 = 普通假日 / 1 = 上班。 */
    fun parseTimor(body: String, year: Int): List<HolidayDay> {
        val root = JSONObject(body)
        require(root.optInt("code", -1) == 0) { "返回异常" }
        val holidays = root.optJSONObject("holiday") ?: error("数据缺失")
        val out = ArrayList<HolidayDay>()
        holidays.keys().forEach { key ->
            val entry = holidays.optJSONObject(key) ?: return@forEach
            val text = entry.optString("date").takeIf { it.isNotBlank() } ?: "$year-$key"
            val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return@forEach
            require(date.year == year) { "年份不匹配" }
            out += HolidayDay(
                date = date,
                name = entry.optString("name").take(80).ifBlank { "调休" },
                isRest = entry.optBoolean("holiday", false),
                wage = entry.optInt("wage", 2),
            )
        }
        return checked(out, year)
    }

    /**
     * holiday-cn:`{"days":[{"name","date","isOffDay"}]}`。
     *
     * 它不区分「法定节假日」与「普通假日」,所以休息日一律按 wage=2 —— planHolidays 在拿不到
     * wage>=3 时会退化成「每个假期补 1 天」的保守估计,补课天数可能少一点,人工在预览里能改。
     */
    fun parseHolidayCn(body: String, year: Int): List<HolidayDay> {
        val days = JSONObject(body).optJSONArray("days") ?: error("数据缺失")
        val out = ArrayList<HolidayDay>()
        for (i in 0 until days.length()) {
            val entry = days.optJSONObject(i) ?: continue
            val date = runCatching { LocalDate.parse(entry.optString("date")) }.getOrNull() ?: continue
            require(date.year == year) { "年份不匹配" }
            val off = entry.optBoolean("isOffDay", false)
            out += HolidayDay(
                date = date,
                name = entry.optString("name").take(80).ifBlank { "调休" },
                isRest = off,
                wage = if (off) 2 else 1,
            )
        }
        return checked(out, year)
    }

    private fun checked(days: List<HolidayDay>, year: Int): List<HolidayDay> {
        val result = days.sortedBy { it.date }
        require(result.isNotEmpty()) { "$year 年数据为空" }
        require(result.map { it.date }.distinct().size == result.size) { "日期重复" }
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
