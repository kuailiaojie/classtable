package com.kxin.classtable.data

import com.kxin.classtable.data.local.PartnerCourseEntity
import com.kxin.classtable.data.local.PartnerDao
import com.kxin.classtable.data.local.PartnerProfileEntity
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.PartnerType
import com.kxin.classtable.domain.model.WeekType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerRepositoryTest {

    private val dummyDao = object : PartnerDao {
        override fun observeAllProfiles(): Flow<List<PartnerProfileEntity>> = emptyFlow()
        override suspend fun getAllProfiles(): List<PartnerProfileEntity> = emptyList()
        override fun observeCurrentProfile(): Flow<PartnerProfileEntity?> = emptyFlow()
        override suspend fun getProfile(id: String): PartnerProfileEntity? = null
        override suspend fun insertProfile(profile: PartnerProfileEntity) {}
        override suspend fun insertProfiles(profiles: List<PartnerProfileEntity>) {}
        override suspend fun deleteProfile(id: String) {}
        override suspend fun deleteAllProfiles() {}
        override suspend fun clearCurrent() {}
        override suspend fun setCurrent(id: String) {}
        override fun observeAllCourses(): Flow<List<PartnerCourseEntity>> = emptyFlow()
        override suspend fun getAllCourses(): List<PartnerCourseEntity> = emptyList()
        override fun observeCourses(partnerId: String): Flow<List<PartnerCourseEntity>> = emptyFlow()
        override suspend fun getCourses(partnerId: String): List<PartnerCourseEntity> = emptyList()
        override suspend fun insertCourses(courses: List<PartnerCourseEntity>) {}
        override suspend fun deleteCoursesByPartner(partnerId: String) {}
        override suspend fun deleteAllCourses() {}
    }

    private val repo = PartnerRepository(dummyDao)

    @Test
    fun `exportSharePayload and parseSharePayload round-trip preserves courses and metadata`() {
        val sampleCourses = listOf(
            Course(
                id = "c1",
                name = "高等数学",
                teacher = "林教授",
                location = "理教 201",
                weekday = 1,
                startPeriod = 1,
                endPeriod = 2,
                weekType = WeekType.EVERY_WEEK,
                weekStart = 1,
                weekEnd = 16,
            ),
            Course(
                id = "c2",
                name = "大学物理",
                teacher = "陈老师",
                location = "二教 105",
                weekday = 3,
                startPeriod = 3,
                endPeriod = 4,
                weekType = WeekType.ODD_WEEK,
                weekStart = 1,
                weekEnd = 15,
            ),
        )

        val tokenText = repo.exportSharePayload(
            ownerName = "小鹿",
            type = PartnerType.COUPLE,
            colorHue = 345,
            courses = sampleCourses,
            obscureCourseDetails = false,
        )
        assertTrue(tokenText.contains("余课课表口令￥"))

        val parsed = repo.parseSharePayload(tokenText).getOrThrow()
        val profile = parsed.first
        val courses = parsed.second

        assertEquals("小鹿", profile.name)
        assertEquals(PartnerType.COUPLE, profile.type)
        assertEquals(345, profile.colorHue)
        assertEquals(2, courses.size)
        assertEquals("高等数学", courses[0].name)
        assertEquals("理教 201", courses[0].location)
        assertEquals("林教授", courses[0].teacher)
        assertTrue(courses[0].isPartner)
        assertEquals("小鹿", courses[0].ownerName)
    }

    @Test
    fun `exportSharePayload with obscureCourseDetails masks course names and locations`() {
        val sampleCourses = listOf(
            Course(
                id = "c1",
                name = "秘密选修课",
                teacher = "张老师",
                location = "实验楼 402",
                weekday = 2,
                startPeriod = 1,
                endPeriod = 2,
                note = "私人备注",
            ),
        )

        val tokenText = repo.exportSharePayload(
            ownerName = "阿泽",
            type = PartnerType.FRIEND,
            colorHue = 165,
            courses = sampleCourses,
            obscureCourseDetails = true,
        )

        val parsed = repo.parseSharePayload(tokenText).getOrThrow()
        val course = parsed.second.single()
        assertEquals("忙碌(有课)", course.name)
        assertEquals("", course.location)
        assertEquals("", course.teacher)
        assertEquals("", course.note)
    }

    @Test
    fun `calculateCommonFreeSlots finds continuous free periods for both partners`() {
        val periods = Schedule.parsePeriods("480-525, 535-580, 600-645, 655-700, 840-885, 895-940")
        val myCourses = listOf(
            Course(id = "m1", name = "高数", weekday = 1, startPeriod = 1, endPeriod = 2),
        )
        val partnerCourses = listOf(
            Course(id = "p1", name = "英语", weekday = 1, startPeriod = 5, endPeriod = 6, isPartner = true),
        )

        // 周一第 1-2 节我有课，第 5-6 节伴侣有课 -> 第 3-4 节 (600..700) 双方共同空闲
        val slots = repo.calculateCommonFreeSlots(
            myCourses = myCourses,
            partnerCourses = partnerCourses,
            periods = periods,
            week = 1,
            weekday = 1,
        )

        assertEquals(1, slots.size)
        val slot = slots.single()
        assertEquals(1, slot.weekday)
        assertEquals(3, slot.startPeriod)
        assertEquals(4, slot.endPeriod)
        assertEquals(600, slot.startMinute)
        assertEquals(700, slot.endMinute)
        assertEquals(100, slot.durationMinutes)
        assertEquals(2, slot.periodCount)
    }
}
