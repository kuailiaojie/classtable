package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kxin.classtable.domain.model.Semester

@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val weekCount: Int,
    val startDay: Long,
) {
    fun toDomain(): Semester = Semester(id, name, weekCount, startDay)

    companion object {
        fun fromDomain(s: Semester): SemesterEntity = SemesterEntity(s.id, s.name, s.weekCount, s.startDay)
    }
}
