package com.kxin.classtable.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 助手(自然语言建课 / 建日程 / 询问课表)的对话与动作模型。
 *
 * 模型被要求**只输出 JSON**:{ "reply": "给用户看的中文", "actions": [ … ] }。
 * `actions` 是「要 App 执行的结构化动作」——App 先渲染成预览卡片,用户确认后才真正落库
 * (见 AssistantViewModel)。这样模型幻觉不会直接污染课表。
 */
sealed interface AiAction {

    /** 新建一门课:按节次(第几节)排。 */
    data class CreateCourse(
        val name: String,
        val teacher: String = "",
        val location: String = "",
        /** 1 = 周一 … 7 = 周日。 */
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        /** 上课周次(离散);空 = 全学期。 */
        val weeks: List<Int> = emptyList(),
        val note: String = "",
    ) : AiAction

    /** 新建一条日程 / 倒计时。[startTime]/[endTime] 为 null 表示全天。 */
    data class CreateAgenda(
        val title: String,
        val category: String = "TODO",
        /** ISO 日期 yyyy-MM-dd。 */
        val date: String,
        val startTime: String? = null,
        val endTime: String? = null,
        val location: String = "",
        val note: String = "",
    ) : AiAction
}

/** 一轮助手回复:给用户看的话 + 待确认的动作。 */
data class AiChatReply(
    val reply: String,
    val actions: List<AiAction>,
)

/** 对话历史里的一条(role = "user" | "assistant")。 */
data class AiChatMessage(val role: String, val content: String)

/**
 * 助手系统提示词。要求:中文作答;只输出 JSON;动作字段与 [AiAction] 对应;
 * 信息不足就反问、不要瞎编(宁可不产出动作)。
 */
val AI_ASSISTANT_PROMPT = """
    你是「余课」课表 App 里的助手。用户会用自然语言让你**新建课程 / 新建日程**,或者**询问课表与安排**。
    你只能通过输出 JSON 来回应,格式:
    {"reply":"给用户看的中文回复","actions":[...]}
    actions 是数组,元素二选一:
      建课:{"type":"course","name":"高数","teacher":"张三","location":"教三201","day":3,"startSection":1,"endSection":2,"weeks":[1,2,3],"note":""}
      建日程:{"type":"agenda","title":"小组会议","category":"TODO","date":"2026-10-12","startTime":"14:00","endTime":"16:00","location":"","note":""}
    规则:
    - day:1=周一 … 7=周日;startSection/endSection 是第几节;weeks 是周次数组,用户没指定就给空数组(表示每周)。
    - 日程 startTime/endTime 用 24 小时 "HH:MM";全天事项把两个都省略。
    - date 用 yyyy-MM-dd;用户说「明天/周三」时,按下面给出的「今天」换算成具体日期。
    - 只想聊天或回答问题时 actions 给空数组 []。
    - 信息不足(如没说是星期几、没有课程名)时,reply 里用中文反问,actions 给 []。不要编造。
    - reply 要简短、口语化,别复述 JSON,别用 markdown。
""".trimIndent()

/** 从模型文本里抠出 JSON 并解析。[raw] 允许前后有多余文字或 ```json 围栏。 */
fun parseAiChatReply(raw: String): AiChatReply {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return AiChatReply(reply = raw.trim(), actions = emptyList())
    val json = runCatching { JSONObject(raw.substring(start, end + 1)) }
        .getOrElse { return AiChatReply(reply = raw.trim(), actions = emptyList()) }
    val reply = json.optString("reply").trim().ifBlank { "好的。" }
    val actionsArr = json.optJSONArray("actions") ?: JSONArray()
    val actions = (0 until actionsArr.length()).mapNotNull { i ->
        actionsArr.optJSONObject(i)?.let(::parseAction)
    }
    return AiChatReply(reply = reply, actions = actions)
}

private fun parseAction(o: JSONObject): AiAction? = when (o.optString("type").lowercase()) {
    "course" -> {
        val name = o.optString("name").trim()
        val day = o.optInt("day")
        val start = o.optInt("startSection")
        val end = o.optInt("endSection", start).coerceAtLeast(start)
        if (name.isBlank() || day !in 1..7 || start < 1) null
        else AiAction.CreateCourse(
            name = name,
            teacher = o.optString("teacher").trim(),
            location = o.optString("location").trim(),
            day = day,
            startSection = start,
            endSection = end,
            weeks = o.optJSONArray("weeks")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }.filter { it > 0 }
            }.orEmpty(),
            note = o.optString("note").trim(),
        )
    }

    "agenda" -> {
        val title = o.optString("title").trim()
        val date = o.optString("date").trim()
        if (title.isBlank() || date.isBlank()) null
        else AiAction.CreateAgenda(
            title = title,
            category = o.optString("category").ifBlank { "TODO" },
            date = date,
            startTime = o.optString("startTime").trim().takeIf { it.isNotBlank() },
            endTime = o.optString("endTime").trim().takeIf { it.isNotBlank() },
            location = o.optString("location").trim(),
            note = o.optString("note").trim(),
        )
    }

    else -> null
}
