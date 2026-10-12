package com.kxin.classtable.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 助手可调用的**函数定义** —— 单一事实来源。
 *
 * 这里只描述「有哪些只读工具、参数长什么样」,统一供三处使用:
 * 1. 生成提示词里的工具目录(见 [catalog]);
 * 2. 组装 **OpenAI 兼容端**的 `tools` 数组([openAiToolsJson]);
 * 3. 组装 **Gemini** 的 `tools.functionDeclarations`([geminiToolsJson])。
 *
 * 于是「模型看到的函数」与「本地真正执行的函数」([AgentTools])永远同一份定义 —— 加一个工具只需
 * 在这里加一条,不必再改提示词字符串。写操作仍走 JSON `actions` + 用户确认([AiAction]),不在这里。
 */
data class AiFunctionSpec(
    val name: String,
    val description: String,
    /** 只读工具为 true;写操作若要开放成原生函数,应标 false 并在执行前要求确认。 */
    val readOnly: Boolean,
    val parameters: JSONObject,
)

object AiFunctions {

    /**
     * 是否把只读工具以**原生 function calling** 交给模型(需要 provider 支持 `tools`)。
     *
     * 关掉即回到老协议:模型在 JSON 里给 `tool` 字段,App 照样执行 —— 两条路径共用 [AgentTools]。
     * 带工具请求若被 provider 拒绝(部分第三方兼容端不认 `tools`),客户端会自动退回纯文本再试一次。
     */
    const val NATIVE_READ_TOOLS = true

    private fun schema(
        vararg props: Pair<String, JSONObject>,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        put("required", JSONArray(required))
        put("additionalProperties", false)
    }

    private fun str(desc: String): JSONObject = JSONObject().put("type", "string").put("description", desc)

    private fun num(desc: String): JSONObject = JSONObject().put("type", "integer").put("description", desc)

    val readTools: List<AiFunctionSpec> = listOf(
        AiFunctionSpec(
            name = "GET_CURRENT_OVERVIEW",
            description = "当前日期 / 教学周 / 作息节数,以及今日课程与近期日程",
            readOnly = true,
            parameters = schema(),
        ),
        AiFunctionSpec(
            name = "SEARCH_COURSES",
            description = "按条件定位真实课程记录,返回课程 ID(query 匹配课程名 / 教师 / 地点)",
            readOnly = true,
            parameters = schema(
                "query" to str("关键词"),
                "weekday" to num("星期,1=周一 … 7=周日"),
            ),
        ),
        AiFunctionSpec(
            name = "GET_WEEK_SCHEDULE",
            description = "某一周的课程与空档",
            readOnly = true,
            parameters = schema("week" to num("第几教学周,省略为当前周")),
        ),
        AiFunctionSpec(
            name = "GET_SEMESTER_SCHEDULE",
            description = "全学期课程一览(含 ID 与周次)",
            readOnly = true,
            parameters = schema(),
        ),
        AiFunctionSpec(
            name = "GET_PERIODS",
            description = "作息:每节的起止时间",
            readOnly = true,
            parameters = schema(),
        ),
        AiFunctionSpec(
            name = "GET_SETTINGS",
            description = "可改设置的键与当前值,以及可打开的页面目录",
            readOnly = true,
            parameters = schema(),
        ),
        AiFunctionSpec(
            name = "GET_SCHEDULE_ADJUSTMENTS",
            description = "调休 / 停课 / 补课安排",
            readOnly = true,
            parameters = schema(),
        ),
    )

    /** 提示词里的工具目录(与 [readTools] 同源)。 */
    fun catalog(): String = readTools.joinToString("\n") { "${it.name} - ${it.description}" }

    /** OpenAI 兼容端的 `tools` 数组。 */
    fun openAiToolsJson(specs: List<AiFunctionSpec> = readTools): JSONArray = JSONArray().apply {
        specs.forEach { spec ->
            put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", spec.parameters),
                    ),
            )
        }
    }

    /** Gemini 的 `tools` 数组(functionDeclarations)。 */
    fun geminiToolsJson(specs: List<AiFunctionSpec> = readTools): JSONArray = JSONArray().apply {
        put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray().apply {
                    specs.forEach { spec ->
                        put(
                            JSONObject()
                                .put("name", spec.name)
                                .put("description", spec.description)
                                .put("parameters", spec.parameters),
                        )
                    }
                },
            ),
        )
    }
}
