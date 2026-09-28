package com.kxin.classtable.data.importer

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import org.json.JSONObject
import java.io.File

// 注:@Parcelize(kotlin-parcelize 插件)在 AGP 9 的 built-in Kotlin 下不生效
// (Google Issue 478401081),故这两个类手写 Parcelable 实现,行为与原生成代码一致。
data class SchoolEntry(
    val id: String,
    val name: String,
    val initial: String,
    val folder: String,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(name)
        dest.writeString(initial)
        dest.writeString(folder)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SchoolEntry> = object : Parcelable.Creator<SchoolEntry> {
            override fun createFromParcel(source: Parcel): SchoolEntry = SchoolEntry(
                id = source.readString().orEmpty(),
                name = source.readString().orEmpty(),
                initial = source.readString().orEmpty(),
                folder = source.readString().orEmpty(),
            )

            override fun newArray(size: Int): Array<SchoolEntry?> = arrayOfNulls(size)
        }
    }
}

data class AdapterEntry(
    val folder: String,
    val adapterId: String,
    val adapterName: String,
    val category: String,
    val jsPath: String,
    val importUrl: String,
    val description: String,
    val maintainer: String = "",
) : Parcelable {
    /** 通用教务类适配器没有固定入口,需要用户手动填写教务系统网址。 */
    val needsManualUrl: Boolean get() = importUrl.isBlank()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(folder)
        dest.writeString(adapterId)
        dest.writeString(adapterName)
        dest.writeString(category)
        dest.writeString(jsPath)
        dest.writeString(importUrl)
        dest.writeString(description)
        dest.writeString(maintainer)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<AdapterEntry> = object : Parcelable.Creator<AdapterEntry> {
            override fun createFromParcel(source: Parcel): AdapterEntry = AdapterEntry(
                folder = source.readString().orEmpty(),
                adapterId = source.readString().orEmpty(),
                adapterName = source.readString().orEmpty(),
                category = source.readString().orEmpty(),
                jsPath = source.readString().orEmpty(),
                importUrl = source.readString().orEmpty(),
                description = source.readString().orEmpty(),
                maintainer = source.readString().orEmpty(),
            )

            override fun newArray(size: Int): Array<AdapterEntry?> = arrayOfNulls(size)
        }
    }
}

/**
 * shiguang_warehouse 索引读取(缓存优先)。
 * 设置页「适配器同步」会把云端 bundle 落到 filesDir/warehouse/,
 * 此处优先读缓存,不存在或损坏时回退 APK 内置 assets,保证离线可用。
 * index.json / adapters.json 由 tools/yaml2json.mjs 预编译自 YAML,
 * JS 适配脚本按 resources/<folder>/<jsPath> 读取。
 */
object WarehouseIndex {

    /** 缓存根目录名(filesDir 与 assets 下同名)。 */
    const val CACHE_DIR = "warehouse"

    /** 开发者自检/工具目录,不参与正常导入。 */
    val SELF_CHECK_FOLDERS: Set<String> = setOf("GLOBAL_TOOLS")

    /** 通用教务目录:需手动填写教务网址。 */
    val GENERIC_JIAOWU_FOLDERS: Set<String> =
        setOf("urp_jiaowu", "zhengfang_jiaowu", "qingguo_jiaowu", "chaoxing_jiaowu")

    fun loadSchools(context: Context): Result<List<SchoolEntry>> =
        parseFirst(context, "index.json") { json ->
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
        }

    fun loadAdapters(context: Context): Result<List<AdapterEntry>> =
        parseFirst(context, "adapters.json") { json ->
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
        }

    fun readScript(context: Context, folder: String, jsPath: String): String? =
        readText(context, "resources/$folder/$jsPath")

    fun categoryLabel(category: String): String = when (category) {
        "BACHELOR_AND_ASSOCIATE" -> "本专科"
        "POSTGRADUATE" -> "研究生"
        "GENERAL_TOOL" -> "通用"
        else -> category
    }

    /** 缓存优先读取文本:filesDir 命中就用缓存,否则回退 assets。 */
    private fun readText(context: Context, relative: String): String? {
        val cached = File(context.filesDir, "$CACHE_DIR/$relative")
        if (cached.isFile) {
            runCatching { cached.readText(Charsets.UTF_8) }.getOrNull()?.let { return it }
        }
        return runCatching {
            context.assets.open("$CACHE_DIR/$relative").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    /** 依次尝试「缓存 → 内置」两个来源,返回首个解析成功的结果。 */
    private fun <T> parseFirst(
        context: Context,
        relative: String,
        parse: (String) -> List<T>,
    ): Result<List<T>> {
        val cached = File(context.filesDir, "$CACHE_DIR/$relative")
        val candidates = listOf(
            if (cached.isFile) runCatching { cached.readText(Charsets.UTF_8) }.getOrNull() else null,
            runCatching {
                context.assets.open("$CACHE_DIR/$relative").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull(),
        )
        var last: Throwable? = null
        for (text in candidates) {
            if (text == null) continue
            runCatching { parse(text) }
                .onSuccess { return Result.success(it) }
                .onFailure { last = it }
        }
        return Result.failure(last ?: IllegalStateException("适配器索引缺失: $relative"))
    }
}
