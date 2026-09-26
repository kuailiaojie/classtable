package com.kxin.classtable.domain

import com.nlf.calendar.Solar
import java.time.LocalDate

/**
 * 农历 / 节气 / 节日(日历周条下的小字)。
 *
 * 依赖 `cn.6tail:lunar`(纯 Java,无第三方依赖)。全部接口都包一层兜底:
 * 农历换算对越界日期会抛异常,而周条上任何一个日期取值失败都不该让整块日历崩掉。
 */
object LunarDate {

    /** 周条下的一行小字:节气 → 节日 → 农历日名(如「秋分」「中秋节」「十六」)。取不到返回空串。 */
    fun label(date: LocalDate): String = runCatching {
        val lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        lunar.jieQi.takeIf { it.isNotEmpty() }
            ?: lunar.festivals.firstOrNull()
            ?: lunar.solar.festivals.firstOrNull()
            ?: lunar.dayInChinese
    }.getOrDefault("")

    /** 完整农历写法(如「农历八月十七」);取不到返回空串。 */
    fun fullText(date: LocalDate): String = runCatching {
        val lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        "农历${lunar.monthInChinese}月${lunar.dayInChinese}"
    }.getOrDefault("")

    /** 是否节气当天(周条上想给节气泡个不同格式时用)。 */
    fun isSolarTerm(date: LocalDate): Boolean = runCatching {
        Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar.jieQi.isNotEmpty()
    }.getOrDefault(false)
}
