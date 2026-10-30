package com.kxin.classtable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleTest {

    @Test
    fun `parsePeriods handles default and custom specs`() {
        val periods = Schedule.parsePeriods("480-530, 540-590")
        assertEquals(2, periods.size)
        assertEquals(480, periods[0].start)
        assertEquals(530, periods[0].end)
        assertEquals(50, periods[0].duration)

        assertEquals(540, periods[1].start)
        assertEquals(590, periods[1].end)
    }

    @Test
    fun `parsePeriods handles legacy format without dashes`() {
        val periods = Schedule.parsePeriods("480, 540, 600")
        assertEquals(3, periods.size)
        assertEquals(480, periods[0].start)
        assertEquals(540, periods[0].end)
        assertEquals(540, periods[1].start)
        assertEquals(600, periods[1].end)
    }

    @Test
    fun `weekDates and weekDayNumbers generate correct date sequence`() {
        // 2026-09-01 is Tuesday, so Monday is 2026-08-31
        val startDay = LocalDate.of(2026, 9, 1).toEpochDay()
        val dates = Schedule.weekDates(startDay, 1)
        assertEquals(7, dates.size)
        assertEquals(LocalDate.of(2026, 8, 31), dates[0])
        assertEquals(LocalDate.of(2026, 9, 6), dates[6])

        val dayNums = Schedule.weekDayNumbers(startDay, 1)
        assertEquals(listOf(31, 1, 2, 3, 4, 5, 6), dayNums)
    }

    @Test
    fun `weekRangeText formats correctly`() {
        val startDay = LocalDate.of(2026, 9, 1).toEpochDay()
        val text = Schedule.weekRangeText(startDay, 1)
        assertEquals("8/31–9/6", text)

        val emptyText = Schedule.weekRangeText(0L, 1)
        assertEquals("", emptyText)
    }
}
