package com.kxin.classtable.data

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
    val updatedAt: Long = 0L,
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
        updatedAt = updatedAt,
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
            updatedAt = e.updatedAt,
        )

        // ---- Firestore REST 字段编解码(org.json,零依赖) ----

        fun fromFields(fields: JSONObject): RemoteAgenda {
            fun s(n: String) = fields.optJSONObject(n)?.optString("stringValue").orEmpty()
            fun l(n: String) = fields.optJSONObject(n)?.optString("integerValue")?.toLongOrNull() ?: 0L
            fun b(n: String) = fields.optJSONObject(n)?.optBoolean("booleanValue") ?: false
            return RemoteAgenda(
                id = s("id"),
                title = s("title"),
                category = s("category").ifBlank { "TODO" },
                startAt = l("startAt"),
                endAt = l("endAt"),
                allDay = b("allDay"),
                location = s("location"),
                note = s("note"),
                priority = s("priority").ifBlank { "NONE" },
                updatedAt = l("updatedAt"),
            )
        }

        fun toFields(e: RemoteAgenda): JSONObject = JSONObject().apply {
            fun str(n: String, v: String) = put(n, JSONObject().put("stringValue", v))
            fun long(n: String, v: Long) = put(n, JSONObject().put("integerValue", v.toString()))
            fun bool(n: String, v: Boolean) = put(n, JSONObject().put("booleanValue", v))
            str("id", e.id)
            str("title", e.title)
            str("category", e.category)
            long("startAt", e.startAt)
            long("endAt", e.endAt)
            bool("allDay", e.allDay)
            str("location", e.location)
            str("note", e.note)
            str("priority", e.priority)
            long("updatedAt", e.updatedAt)
        }
    }
}
