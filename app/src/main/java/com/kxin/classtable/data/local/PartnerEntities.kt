package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kxin.classtable.domain.WeekSpec
import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.PartnerProfile
import com.kxin.classtable.domain.model.PartnerType
import com.kxin.classtable.domain.model.WeekType

@Entity(tableName = "partner_profiles")
data class PartnerProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val colorHue: Int,
    val avatarUrl: String = "",
    val shareCode: String = "",
    val isCurrent: Boolean = false,
    val updatedAt: Long = 0L,
) {
    fun toDomain(): PartnerProfile = PartnerProfile(
        id = id,
        name = name,
        type = PartnerType.of(type),
        colorHue = colorHue,
        avatarUrl = avatarUrl,
        shareCode = shareCode,
        isCurrent = isCurrent,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(p: PartnerProfile): PartnerProfileEntity = PartnerProfileEntity(
            id = p.id,
            name = p.name,
            type = p.type.name,
            colorHue = p.colorHue,
            avatarUrl = p.avatarUrl,
            shareCode = p.shareCode,
            isCurrent = p.isCurrent,
            updatedAt = p.updatedAt,
        )
    }
}

@Entity(
    tableName = "partner_courses",
    indices = [Index(value = ["partnerId"])],
)
data class PartnerCourseEntity(
    @PrimaryKey val id: String,
    val partnerId: String,
    val name: String,
    val teacher: String,
    val location: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weekType: String,
    val weekStart: Int,
    val weekEnd: Int,
    val weeks: String = "",
    val customStartMinute: Int? = null,
    val customEndMinute: Int? = null,
    val weekdays: Int = 0,
    val note: String = "",
    val colorHex: String = "",
    val updatedAt: Long = 0L,
) {
    fun toDomain(partner: PartnerProfile): Course = Course(
        id = id,
        name = name,
        teacher = teacher,
        location = location,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weekType = enumOrDefault(weekType, WeekType.EVERY_WEEK),
        weekStart = weekStart,
        weekEnd = weekEnd,
        weeks = WeekSpec.decode(weeks),
        semesterId = partner.id,
        updatedAt = updatedAt,
        customStartMinute = customStartMinute,
        customEndMinute = customEndMinute,
        weekdays = if (weekdays > 0) weekdays else 1 shl (weekday - 1),
        note = note,
        colorHex = colorHex,
        colorHue = partner.colorHue,
        isPartner = true,
        ownerName = partner.name,
    )

    companion object {
        fun fromDomain(c: Course, partnerId: String): PartnerCourseEntity {
            val scopedId = if (c.id.startsWith("${partnerId}_")) c.id else "${partnerId}_${c.id}"
            return PartnerCourseEntity(
                id = scopedId,
                partnerId = partnerId,
                name = c.name,
                teacher = c.teacher,
                location = c.location,
                weekday = c.weekday,
                startPeriod = c.startPeriod,
                endPeriod = c.endPeriod,
                weekType = c.weekType.name,
                weekStart = c.weekStart,
                weekEnd = c.weekEnd,
                weeks = WeekSpec.encode(c.weeks),
                customStartMinute = c.customStartMinute,
                customEndMinute = c.customEndMinute,
                weekdays = c.weekdays,
                note = c.note,
                colorHex = c.colorHex,
                updatedAt = c.updatedAt,
            )
        }
    }
}
