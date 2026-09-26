package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AgendaPriority

/** 用户自建日程 / 倒计时条目。 */
@Entity(tableName = "agenda_events")
data class AgendaEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean,
    val location: String,
    val note: String,
    val priority: String,
    val remindEnabled: Boolean,
    val remindLeadMinutes: Int,
    val updatedAt: Long,
) {
    fun toDomain(): AgendaEvent = AgendaEvent(
        id = id,
        title = title,
        category = runCatching { AgendaCategory.valueOf(category) }.getOrDefault(AgendaCategory.TODO),
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
        location = location,
        note = note,
        priority = runCatching { AgendaPriority.valueOf(priority) }.getOrDefault(AgendaPriority.NONE),
        remindEnabled = remindEnabled,
        remindLeadMinutes = remindLeadMinutes,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(e: AgendaEvent): AgendaEntity = AgendaEntity(
            id = e.id,
            title = e.title,
            category = e.category.name,
            startAt = e.startAt,
            endAt = e.endAt,
            allDay = e.allDay,
            location = e.location,
            note = e.note,
            priority = e.priority.name,
            remindEnabled = e.remindEnabled,
            remindLeadMinutes = e.remindLeadMinutes,
            updatedAt = e.updatedAt,
        )
    }
}
