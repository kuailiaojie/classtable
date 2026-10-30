package com.kxin.classtable.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AdjustmentsTest {

    @Test
    fun `encode and decode roundtrip`() {
        val date1 = LocalDate.of(2026, 10, 1)
        val date2 = LocalDate.of(2026, 10, 10)
        val source2 = LocalDate.of(2026, 10, 8)

        val list = listOf(
            ScheduleAdjustment(date = date1, sourceDate = null, label = "国庆停课"),
            ScheduleAdjustment(date = date2, sourceDate = source2, label = "补周四课"),
        )

        val json = Adjustments.encode(list)
        val decoded = Adjustments.decode(json)

        assertEquals(2, decoded.size)
        assertEquals(date1, decoded[0].date)
        assertNull(decoded[0].sourceDate)
        assertEquals("国庆停课", decoded[0].label)
        assertTrue(decoded[0].isRest)

        assertEquals(date2, decoded[1].date)
        assertEquals(source2, decoded[1].sourceDate)
        assertEquals("补周四课", decoded[1].label)
        assertFalse(decoded[1].isRest)
    }

    @Test
    fun `resolve resolves rest and makeup days accurately`() {
        val targetDate = LocalDate.of(2026, 10, 10)
        val sourceDate = LocalDate.of(2026, 10, 8)
        val adjustments = listOf(
            ScheduleAdjustment(date = targetDate, sourceDate = sourceDate, label = "补课"),
            ScheduleAdjustment(date = LocalDate.of(2026, 10, 1), sourceDate = null, label = "停课"),
        )

        val normalDay = Adjustments.resolve(adjustments, LocalDate.of(2026, 10, 5))
        assertEquals(LocalDate.of(2026, 10, 5), normalDay.effectiveDate)
        assertFalse(normalDay.rest)
        assertFalse(normalDay.makeup)

        val restDay = Adjustments.resolve(adjustments, LocalDate.of(2026, 10, 1))
        assertTrue(restDay.rest)
        assertFalse(restDay.makeup)

        val makeupDay = Adjustments.resolve(adjustments, targetDate)
        assertFalse(makeupDay.rest)
        assertTrue(makeupDay.makeup)
        assertEquals(sourceDate, makeupDay.effectiveDate)
    }
}
