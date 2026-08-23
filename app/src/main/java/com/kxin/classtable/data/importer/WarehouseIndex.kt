package com.kxin.classtable.data.importer

import android.content.Context
import org.json.JSONObject

data class SchoolEntry(
    val id: String,
    val name: String,
    val initial: String,
    val folder: String,
)

data class AdapterEntry(
    val folder: String,
    val adapterId: String,
    val adapterName: String,
    val category: String,
    val jsPath: String,
    val importUrl: String,
    val description: String,
    val maintainer: String = "",
) {
    /** 通用教务类适配器没有固定入口,需要用户手动填写教务系统网址。 */
    val needsManualUrl: Boolean get() = importUrl.isBlank()
}

/**
 * shiguang_warehouse 索引读取。
 * index.json / adapters.json 由 tools/yaml2json.mjs 预编译自 YAML,
 * JS 适配脚本按 assets/warehouse/resources/<folder>/<jsPath> 读取。
 */
object WarehouseIndex {

    /** 开发者自检/工具目录,不参与正常导入。 */
    val SELF_CHECK_FOLDERS: Set<String> = setOf("GLOBAL_TOOLS")

    /** 通用教务目录:需手动填写教务网址。 */
    val GENERIC_JIAOWU_FOLDERS: Set<String> =
        setOf("urp_jiaowu", "zhengfang_jiaowu", "qingguo_jiaowu", "chaoxing_jiaowu")

    fun loadSchools(context: Context): List<SchoolEntry> = runCatching {
        val json = context.assets.open("warehouse/index.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("schools")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SchoolEntry(
                id = o.optString("id"),
                name = o.optString("name"),
                initial = o.optString("initial"),
                folder = o.optString("resource_folder"),
            )
        }
    }.getOrDefault(emptyList())

    fun loadAdapters(context: Context): List<AdapterEntry> = runCatching {
        val json = context.assets.open("warehouse/adapters.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("adapters")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AdapterEntry(
                folder = o.optString("folder"),
                adapterId = o.optString("adapter_id"),
                adapterName = o.optString("adapter_name"),
                category = o.optString("category"),
                jsPath = o.optString("asset_js_path"),
                importUrl = o.optString("import_url"),
                description = o.optString("description"),
                maintainer = o.optString("maintainer"),
            )
        }
    }.getOrDefault(emptyList())

    fun readScript(context: Context, folder: String, jsPath: String): String? = runCatching {
        context.assets.open("warehouse/resources/$folder/$jsPath").bufferedReader().use { it.readText() }
    }.getOrNull()

    fun categoryLabel(category: String): String = when (category) {
        "BACHELOR_AND_ASSOCIATE" -> "本专科"
        "POSTGRADUATE" -> "研究生"
        "GENERAL_TOOL" -> "通用"
        else -> category
    }
}
