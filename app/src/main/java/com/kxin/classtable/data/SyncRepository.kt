package com.kxin.classtable.data

import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.CourseEntity
import com.kxin.classtable.data.local.DeletedCourseDao
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AiProvider
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地优先 + Firestore 同步(经反代 REST):
 * 未登录 = 纯本地;登录后 pull 远端 → 合并(updatedAt 后者胜)→ 应用删除墓碑 → push 本地。
 * 删除通过「墓碑」传播,避免远端删除在下次 pull 时复活。
 *
 * 除课程外,配置(作息/学期/主题/提醒等)以 users/{uid}/settings/config 单文档随同步走 LWW:
 * 本地从未改过(updatedAt=0)且远端存在时整包应用远端,避免新设备默认值覆盖云端;
 * AI 密钥与引导标记仅存本机,不落云端。
 *
 * 与旧 SDK 版差异:Firestore REST 无实时监听,同步为「按需 pull/push」;
 * 请求带 ID token Bearer,远端安全规则(request.auth.uid == userId)照常评估。
 */
@Singleton
class SyncRepository @Inject constructor(
    private val courseDao: CourseDao,
    private val deletedDao: DeletedCourseDao,
    private val gateway: FirebaseGateway,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
) {
    /** 请求 URL 路径基址(含 v1 版本前缀,反代转发到 firestore.googleapis.com 后即完整路径)。 */
    private fun dbBase() = "v1/projects/${gateway.projectId}/databases/(default)/documents"

    /** commit 请求体 write.update.name 的资源名基址——不带 v1 前缀,Firestore 要求以 "projects" 开头。 */
    private fun docBase() = "projects/${gateway.projectId}/databases/(default)/documents"

    private fun coursesCol(uid: String) = "${dbBase()}/users/$uid/courses"

    private fun deletedCol(uid: String) = "${dbBase()}/users/$uid/deleted"

    /** Firestore 文档路径段必须成对(集合/文档):users/{uid}/settings 只是集合路径,配置单文档固定落在 settings/config。 */
    private fun settingsDoc(uid: String) = "${dbBase()}/users/$uid/settings/config"

    private fun coursesDocName(uid: String, id: String) = "${docBase()}/users/$uid/courses/${encodeSegment(id)}"

    private fun deletedDocName(uid: String, id: String) = "${docBase()}/users/$uid/deleted/${encodeSegment(id)}"

    private fun settingsDocName(uid: String) = "${docBase()}/users/$uid/settings/config"

    suspend fun syncNow(): Result<Unit> = runCatching {
        val uid = authRepository.uid ?: return Result.success(Unit)
        val token = authRepository.freshIdToken() ?: return Result.success(Unit)

        // 0) 配置单文档同步(登录后立即拉取配置,应用端 LWW 合并)
        syncSettings(uid, token)

        // 1) 拉远端:课程 + 删除墓碑
        val remoteCourses = pullDocuments(coursesCol(uid), token)
            .mapNotNull { doc -> RemoteCourse.fromFields(doc.optJSONObject("fields") ?: return@mapNotNull null).toDomain() }
        val remoteDeleted = pullDocuments(deletedCol(uid), token)
            .mapNotNull { doc ->
                val id = doc.optString("id")
                val updatedAt = doc.optJSONObject("fields")
                    ?.optJSONObject("updatedAt")?.optString("integerValue")?.toLongOrNull()
                if (id.isBlank() || updatedAt == null) null else id to updatedAt
            }

        // 2) 合并(updatedAt 后者胜),并应用远端 + 本地墓碑
        val local = courseDao.getAll().map { it.toDomain() }
        val localTombstones = deletedDao.getAll().associate { it.id to it.updatedAt }
        val merged = merge(local, remoteCourses).filterNot { c ->
            remoteDeleted.any { t -> t.first == c.id && t.second >= c.updatedAt } ||
                (localTombstones[c.id]?.let { it >= c.updatedAt } == true)
        }

        // 3) 写回本地:覆盖式 upsert + 清理多余行
        courseDao.upsertAll(merged.map { CourseEntity.fromDomain(it) })
        val finalIds = merged.map { it.id }.toSet()
        courseDao.getAll().forEach { e -> if (e.id !in finalIds) courseDao.deleteById(e.id) }

        // 4) 推远端:课程 + 墓碑(幂等,单次 commit)
        val writes = JSONArray()
        merged.forEach { c ->
            writes.put(
                JSONObject().put(
                    "update",
                    JSONObject()
                        .put("name", coursesDocName(uid, c.id))
                        .put("fields", RemoteCourse.toFields(RemoteCourse.fromDomain(c))),
                ),
            )
        }
        deletedDao.getAll().forEach { t ->
            writes.put(
                JSONObject().put(
                    "update",
                    JSONObject()
                        .put("name", deletedDocName(uid, t.id))
                        .put("fields", JSONObject().put("updatedAt", JSONObject().put("integerValue", t.updatedAt.toString()))),
                ),
            )
        }
        if (writes.length() > 0) {
            gateway.firestore(
                "${dbBase()}:commit",
                "POST",
                JSONObject().put("writes", writes),
                token,
            )
        }

        Result.success(Unit)
    }

    /** 拉集合全部文档,返回 [{id, fields}]。pageSize=300 覆盖课程量级,无需翻页。 */
    private suspend fun pullDocuments(colPath: String, token: String): List<JSONObject> {
        val resp = gateway.firestore("$colPath?pageSize=300", "GET", null, token)
        val docs = resp.optJSONArray("documents") ?: return emptyList()
        val out = ArrayList<JSONObject>(docs.length())
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val fields = doc.optJSONObject("fields") ?: continue
            out.add(JSONObject().put("id", doc.optString("name").substringAfterLast('/')).put("fields", fields))
        }
        return out
    }

    /**
     * 配置单文档同步(users/{uid}/settings/config):拉远端 → 按 updatedAt 取新 → 写回。
     * 网络失败向上抛(整个 syncNow 失败,登录流程据此提示);文档不存在(404)视为空。
     * 本地从未改过(updatedAt==0)且远端不存在时不创建文档。
     */
    private suspend fun syncSettings(uid: String, token: String) {
        val docPath = settingsDoc(uid)
        val remoteDoc: JSONObject? = try {
            gateway.firestore(docPath, "GET", null, token)
        } catch (e: FirebaseApiException) {
            // Firestore 对不存在的文档返回 404;网关把 error.message/status 暴露在 code 里
            if (e.code == "NOT_FOUND" || e.code.contains("not found", ignoreCase = true)) null else throw e
        }
        val remote = remoteDoc?.let { parseSettingsDoc(it) }
        val local = settingsRepository.currentSettings()
        val localUpdatedAt = settingsRepository.settingsUpdatedAt()

        when {
            remote == null && localUpdatedAt <= 0L -> Unit // 全新设备,两端都没有配置
            remote == null -> pushSettings(uid, token, local, localUpdatedAt) // 本地改过,远端无 → 建文档
            remote.updatedAt > localUpdatedAt ->
                settingsRepository.applySynced(remote.settings, remote.updatedAt) // 远端新 → 应用
            else -> pushSettings(uid, token, local, localUpdatedAt) // 本地新/相同 → 推本地(幂等)
        }
    }

    private suspend fun pushSettings(uid: String, token: String, s: AppSettings, updatedAt: Long) {
        val fields = JSONObject()
            .put("themeMode", JSONObject().put("stringValue", s.themeMode.name))
            .put("accentHex", JSONObject().put("stringValue", s.accentHex))
            .put("currentWeek", JSONObject().put("integerValue", s.currentWeek.toString()))
            .put("periodTimes", JSONObject().put("stringValue", s.periodTimes))
            .put("semesterStartDay", JSONObject().put("integerValue", s.semesterStartDay.toString()))
            .put("semesterWeekCount", JSONObject().put("integerValue", s.semesterWeekCount.toString()))
            .put("aiProvider", JSONObject().put("stringValue", s.aiProvider))
            .put("aiBaseUrl", JSONObject().put("stringValue", s.aiBaseUrl))
            .put("aiModel", JSONObject().put("stringValue", s.aiModel))
            .put("notificationsEnabled", JSONObject().put("booleanValue", s.notificationsEnabled))
            .put("notifyLeadMinutes", JSONObject().put("integerValue", s.notifyLeadMinutes.toString()))
            .put("updatedAt", JSONObject().put("integerValue", updatedAt.toString()))
        gateway.firestore(
            "${dbBase()}:commit",
            "POST",
            JSONObject().put(
                "writes",
                JSONArray().put(
                    JSONObject().put(
                        "update",
                        JSONObject().put("name", settingsDocName(uid)).put("fields", fields),
                    ),
                ),
            ),
            token,
        )
    }

    /** 解析配置文档;字段缺失/损坏时回退默认值,保证部分文档也能安全合并。 */
    private fun parseSettingsDoc(doc: JSONObject): RemoteSettings {
        val f = doc.optJSONObject("fields")
            ?: return RemoteSettings(AppSettings(), 0L)
        fun str(key: String) = f.optJSONObject(key)?.optString("stringValue").orEmpty()
        fun int(key: String) = f.optJSONObject(key)?.optString("integerValue")?.toLongOrNull() ?: 0L
        fun bool(key: String) = f.optJSONObject(key)?.optBoolean("booleanValue") ?: false
        val settings = AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(str("themeMode")) }.getOrDefault(ThemeMode.SYSTEM),
            accentHex = str("accentHex").ifBlank { "#C56473" },
            currentWeek = int("currentWeek").toInt().coerceAtLeast(1),
            periodTimes = str("periodTimes").ifBlank { Schedule.DEFAULT_PERIODS },
            semesterStartDay = int("semesterStartDay"),
            semesterWeekCount = int("semesterWeekCount").toInt().coerceAtLeast(1),
            aiProvider = str("aiProvider").ifBlank { AiProvider.GEMINI.name },
            aiBaseUrl = str("aiBaseUrl"),
            aiModel = str("aiModel"),
            notificationsEnabled = bool("notificationsEnabled"),
            notifyLeadMinutes = int("notifyLeadMinutes").toInt().coerceIn(0, 180),
        )
        return RemoteSettings(settings, int("updatedAt"))
    }

    private data class RemoteSettings(val settings: AppSettings, val updatedAt: Long)

    private fun merge(local: List<Course>, remote: List<Course>): List<Course> {
        val byId = HashMap<String, Course>()
        local.forEach { byId[it.id] = it }
        remote.forEach { r ->
            val l = byId[r.id]
            if (l == null || r.updatedAt > l.updatedAt) byId[r.id] = r
        }
        return byId.values.toList()
    }
}
