package com.kxin.classtable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `parseVersion handles standard versions`() {
        val parsed = UpdateRepository.parseVersion("3.10.0")
        assertEquals(listOf(3, 10, 0), parsed.numbers)
        assertEquals(null, parsed.preRelease)
    }

    @Test
    fun `parseVersion handles v prefix and build metadata`() {
        val parsed = UpdateRepository.parseVersion("v3.10.1+20261030")
        assertEquals(listOf(3, 10, 1), parsed.numbers)
        assertEquals(null, parsed.preRelease)
    }

    @Test
    fun `parseVersion handles dot-separated and hyphen pre-release`() {
        val parsed = UpdateRepository.parseVersion("3.10.0-rc.2")
        assertEquals(listOf(3, 10, 0), parsed.numbers)
        assertEquals(listOf("rc", "2"), parsed.preRelease)
    }

    @Test
    fun `parseVersion handles combined pre-release like rc1`() {
        val parsed = UpdateRepository.parseVersion("3.10.0-rc1")
        assertEquals(listOf(3, 10, 0), parsed.numbers)
        assertEquals(listOf("rc", "1"), parsed.preRelease)
    }

    @Test
    fun `isNewer correctly compares major minor patch`() {
        assertTrue(UpdateRepository.isNewer("3.9.0", "3.10.0"))
        assertTrue(UpdateRepository.isNewer("3.10.0", "3.10.1"))
        assertTrue(UpdateRepository.isNewer("2.99.99", "3.0.0"))
        assertFalse(UpdateRepository.isNewer("3.10.0", "3.10.0"))
        assertFalse(UpdateRepository.isNewer("3.10.0", "3.9.9"))
    }

    @Test
    fun `isNewer respects pre-release lower precedence than stable`() {
        // 任何同号预发布版都应低于正式版
        assertTrue(UpdateRepository.isNewer("3.10.0-rc.1", "3.10.0"))
        assertTrue(UpdateRepository.isNewer("3.10.0-beta.2", "3.10.0"))
        assertFalse(UpdateRepository.isNewer("3.10.0", "3.10.0-rc.1"))
    }

    @Test
    fun `isNewer compares pre-release identifiers accurately`() {
        // alpha < beta < rc
        assertTrue(UpdateRepository.isNewer("3.10.0-alpha.2", "3.10.0-beta.1"))
        assertTrue(UpdateRepository.isNewer("3.10.0-beta.1", "3.10.0-rc.1"))

        // 数值比较: rc.1 < rc.2 < rc.10
        assertTrue(UpdateRepository.isNewer("3.10.0-rc.1", "3.10.0-rc.2"))
        assertTrue(UpdateRepository.isNewer("3.10.0-rc.2", "3.10.0-rc.10"))
        assertFalse(UpdateRepository.isNewer("3.10.0-rc.2", "3.10.0-rc.1"))

        // rc1 < rc2
        assertTrue(UpdateRepository.isNewer("3.10.0-rc1", "3.10.0-rc2"))
    }
}
