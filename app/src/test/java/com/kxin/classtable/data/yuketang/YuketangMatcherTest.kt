package com.kxin.classtable.data.yuketang

import com.kxin.classtable.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YuketangMatcherTest {

    @Test
    fun `exact courseName match gives highest score`() {
        val course = Course(
            id = "c1",
            name = "高等数学",
            teacher = "张教授",
            location = "理科楼101",
            weekday = 1,
            startPeriod = 1,
            endPeriod = 2,
        )

        val classroom1 = YuketangCourse(
            classroomId = "1001",
            name = "计科1班",
            courseName = "高等数学",
            teacherName = "张教授",
        )

        val classroom2 = YuketangCourse(
            classroomId = "1002",
            name = "计科2班",
            courseName = "大学物理",
            teacherName = "李老师",
        )

        val match = YuketangMatcher.match(listOf(course), listOf(classroom1, classroom2)).first()
        assertNotNull(match.best)
        assertEquals("1001", match.best?.classroomId)
    }

    @Test
    fun `tie scores result in null best and non-empty candidates`() {
        val course = Course(
            id = "c1",
            name = "线性代数",
            teacher = "王老师",
            location = "一教202",
            weekday = 2,
            startPeriod = 3,
            endPeriod = 4,
        )

        val classroom1 = YuketangCourse(
            classroomId = "2001",
            name = "21软件1班",
            courseName = "线性代数",
            teacherName = "王老师",
        )

        val classroom2 = YuketangCourse(
            classroomId = "2002",
            name = "21软件2班",
            courseName = "线性代数",
            teacherName = "王老师",
        )

        val match = YuketangMatcher.match(listOf(course), listOf(classroom1, classroom2)).first()
        // 两个班级课程名与教师名完全相同且同分，应交由用户手动挑选，best 为 null
        assertNull(match.best)
        assertEquals(2, match.candidates.size)
    }
}
