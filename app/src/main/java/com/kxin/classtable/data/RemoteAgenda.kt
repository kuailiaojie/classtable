package com.kxin.classtable.data

import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AgendaPriority
import org.json.JSONObject

/** Firestore 日程文档(users/{uid}/agenda/{id})。全默认值构造,便于反序列化。 */
data class RemoteAgenda(
    val id: String = "",
    val title: String = "",
    val category: String = "TODO",
    val startAt: Long = 0L,
    val endAt: Long = 0L,
    val allDay: Boolean = false,
    val location: String = "",
    val note: String = "",
    val priority: String = "NONE",
    val remindEnabled: Boolean = false,
    val remindLeadMinutes: Int = 10,
    val updatedAt: Long = 0L,
    val courseId: String? = null,
    val completed: Boolean = false,
) {
    fun toDomain(): AgendaEvent = AgendaEvent(
        id = id,
        title = title,
        category = enumOrDefault(category, AgendaCategory.TODO),
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
        location = location,
        note = note,
        priority = enumOrDefault(priority, AgendaPriority.NONE),
        remindEnabled = remindEnabled,
        remindLeadMinutes = remindLeadMinutes,
        updatedAt = updatedAt,
        courseId = courseId,
        completed = completed,
    )

    companion object {
        fun fromDomain(e: AgendaEvent): RemoteAgenda = RemoteAgenda(
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
            courseId = e.courseId,
            completed = e.completed,
        )

        // ---- Firestore REST 字段编解码(org.json,零依赖) ----

        fun fromFields(fields: JSONObject): RemoteAgenda {
            val rawCourseId = FirestoreFields.getString(fields, "courseId")
            return RemoteAgenda(
                id = FirestoreFields.getString(fields, "id"),
                title = FirestoreFields.getString(fields, "title"),
                category = FirestoreFields.getString(fields, "category", "TODO"),
                startAt = FirestoreFields.getLong(fields, "startAt"),
                endAt = FirestoreFields.getLong(fields, "endAt"),
                allDay = FirestoreFields.getBoolean(fields, "allDay"),
                location = FirestoreFields.getString(fields, "location"),
                note = FirestoreFields.getString(fields, "note"),
                priority = FirestoreFields.getString(fields, "priority", "NONE"),
                remindEnabled = FirestoreFields.getBoolean(fields, "remindEnabled"),
                remindLeadMinutes = FirestoreFields.getInt(fields, "remindLeadMinutes", 10),
                updatedAt = FirestoreFields.getLong(fields, "updatedAt"),
                courseId = rawCourseId.ifBlank { null },
                completed = FirestoreFields.getBoolean(fields, "completed"),
            )
        }

        fun toFields(e: RemoteAgenda): JSONObject = JSONObject().apply {
            FirestoreFields.putString(this, "id", e.id)
            FirestoreFields.putString(this, "title", e.title)
            FirestoreFields.putString(this, "category", e.category)
            FirestoreFields.putLong(this, "startAt", e.startAt)
            FirestoreFields.putLong(this, "endAt", e.endAt)
            FirestoreFields.putBoolean(this, "allDay", e.allDay)
            FirestoreFields.putString(this, "location", e.location)
            FirestoreFields.putString(this, "note", e.note)
            FirestoreFields.putString(this, "priority", e.priority)
            FirestoreFields.putBoolean(this, "remindEnabled", e.remindEnabled)
            FirestoreFields.putInt(this, "remindLeadMinutes", e.remindLeadMinutes)
            FirestoreFields.putLong(this, "updatedAt", e.updatedAt)
            e.courseId?.let { FirestoreFields.putString(this, "courseId", it) }
            FirestoreFields.putBoolean(this, "completed", e.completed)
        }
    }
}
