package com.kxin.classtable.domain

import com.kxin.classtable.domain.model.WeekType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekSpecTest {

    @Test
    fun `encode and decode CSV weeks`() {
        val list = listOf(2, 4, 6, 8, 10)
        val encoded = WeekSpec.encode(list)
        assertEquals("2,4,6,8,10", encoded)
        val decoded = WeekSpec.decode(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `decode handles malformed strings and duplicates`() {
        assertEquals(emptyList<Int>(), WeekSpec.decode(""))
        assertEquals(emptyList<Int>(), WeekSpec.decode("abc,def"))
        assertEquals(listOf(1, 3, 5), WeekSpec.decode("5,1,3,1, -2, 0"))
    }

    @Test
    fun `fromWeeks resolves single and even weeks`() {
        val oddSpec = WeekSpec.fromWeeks(listOf(1, 3, 5, 7, 9), 20)
        assertEquals(WeekType.ODD_WEEK, oddSpec.type)
        assertEquals(1, oddSpec.start)
        assertEquals(9, oddSpec.end)

        val evenSpec = WeekSpec.fromWeeks(listOf(2, 4, 6, 8), 20)
        assertEquals(WeekType.EVEN_WEEK, evenSpec.type)
        assertEquals(2, evenSpec.start)
        assertEquals(8, evenSpec.end)

        val customSpec = WeekSpec.fromWeeks(listOf(1, 2, 4, 7), 20)
        assertEquals(WeekType.CUSTOM, customSpec.type)
        assertEquals(listOf(1, 2, 4, 7), customSpec.weeks)
    }

    @Test
    fun `parse handles Chinese and range week strings`() {
        val s1 = WeekSpec.parse("3-16周(单)", 20)
        assertNotNull(s1)
        assertEquals(WeekType.ODD_WEEK, s1?.type)
        assertEquals(3, s1?.start)
        assertEquals(16, s1?.end)

        val s2 = WeekSpec.parse("2-18双周", 20)
        assertNotNull(s2)
        assertEquals(WeekType.EVEN_WEEK, s2?.type)
        assertEquals(2, s2?.start)
        assertEquals(18, s2?.end)

        val s3 = WeekSpec.parse("1-8,10,12", 20)
        assertNotNull(s3)
        assertEquals(WeekType.CUSTOM, s3?.type)
        assertTrue(s3!!.weeks.containsAll(listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 12)))
    }
}
