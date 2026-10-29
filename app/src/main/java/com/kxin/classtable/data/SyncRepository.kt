package com.kxin.classtable.data

import com.kxin.classtable.data.local.AgendaDao
import com.kxin.classtable.data.local.AgendaEntity
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.CourseEntity
import com.kxin.classtable.data.local.DeletedAgendaDao
import com.kxin.classtable.data.local.DeletedCourseDao
import com.kxin.classtable.domain.Defaults
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.AiProvider
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地优先 + Firestore 同步(经反代 REST):
 * 未登录 = 纯本地;登录后 pull 远端 → 合并(updatedAt 后者胜)→ 应用删除墓碑 → push 本地。
 * 删除通过「墓碑」传播,避免远端删除在下次 pull 时复活;push 时每条墓碑随附对云端活文档的
 * delete 写(本地删掉的课在云端即时消失,不再残留),墓碑到期(保留窗口 TOMBSTONE_TTL_MS)
 * 后连同云端墓碑文档一起回收,防止 deleted 集合无限增长。
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
    private val agendaDao: AgendaDao,
    private val deletedAgendaDao: DeletedAgendaDao,
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

    /** 日程与它的删除墓碑:与课程各走一套集合,互不影响。 */
    private fun agendaCol(uid: String) = "${dbBase()}/users/$uid/agenda"

    private fun deletedAgendaCol(uid: String) = "${dbBase()}/users/$uid/deleted_agenda"

    /** Firestore 文档路径段必须成对(集合/文档):users/{uid}/settings 只是集合路径,配置单文档固定落在 settings/config。 */
    private fun settingsDoc(uid: String) = "${dbBase()}/users/$uid/settings/config"

    private fun coursesDocName(uid: String, id: String) = "${docBase()}/users/$uid/courses/${encodeSegment(id)}"

    private fun deletedDocName(uid: String, id: String) = "${docBase()}/users/$uid/deleted/${encodeSegment(id)}"

    private fun agendaDocName(uid: String, id: String) = "${docBase()}/users/$uid/agenda/${encodeSegment(id)}"

    private fun deletedAgendaDocName(uid: String, id: String) =
        "${docBase()}/users/$uid/deleted_agenda/${encodeSegment(id)}"

    private fun settingsDocName(uid: String) = "${docBase()}/users/$uid/settings/config"

    private companion object {
        /** Firestore 单次 commit 的写上限是 500;留出安全余量,超出即分批顺序提交。 */
        const val WRITES_PER_COMMIT = 400

        /** 墓碑保留窗口(180 天):窗口内墓碑用于压制已删内容复活,到期后连同云端文档一起回收。 */
        const val TOMBSTONE_TTL_MS = 180L * 24 * 60 * 60 * 1000
    }

    /** commit 的 update 写:整份文档字段替换。 */
    private fun updateWrite(name: String, fields: JSONObject): JSONObject =
        JSONObject().put("update", JSONObject().put("name", name).put("fields", fields))

    /** commit 的 delete 写:删除远端文档;文档不存在时是空操作,天然幂等。 */
    private fun deleteWrite(name: String): JSONObject = JSONObject().put("delete", name)

    /** 墓碑文档的 fields:只有一个 updatedAt,与 pull 端的解析约定一致。 */
    private fun tombstoneFields(updatedAt: Long): JSONObject =
        JSONObject().put("updatedAt", JSONObject().put("integerValue", updatedAt.toString()))

    /**
     * 同步入口:未登录直接返回;已登录则记录耗时(performance)与成败(analytics / crash),
     * 再交给 [syncNowInternal] 做实际合并。
     */
    suspend fun syncNow(): Result<Unit> {
        if (authRepository.uid == null) return Result.success(Unit)
        return Perf.trace("sync_duration") { syncNowInternal() }
            .onSuccess { Analytics.log("sync_succeeded") }
            .onFailure { error ->
                Analytics.log("sync_failed", "reason" to error.javaClass.simpleName)
                Crash.recordException(error, "op" to "sync")
            }
    }

    private suspend fun syncNowInternal(): Result<Unit> = runCatching {
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

        // 1') 拉远端:日程 + 删除墓碑(与课程同一套约定,各走各的集合)
        val remoteAgenda = pullDocuments(agendaCol(uid), token)
            .mapNotNull { doc ->
                RemoteAgenda.fromFields(doc.optJSONObject("fields") ?: return@mapNotNull null).toDomain()
            }
        val remoteDeletedAgenda = pullDocuments(deletedAgendaCol(uid), token)
            .mapNotNull { doc ->
                val id = doc.optString("id")
                val updatedAt = doc.optJSONObject("fields")
                    ?.optJSONObject("updatedAt")?.optString("integerValue")?.toLongOrNull()
                if (id.isBlank() || updatedAt == null) null else id to updatedAt
            }

        // 2) 合并(updatedAt 后者胜),并应用远端 + 本地墓碑
        val local = courseDao.getAll().map { it.toDomain() }
        val localCourseTombs = deletedDao.getAll().associate { it.id to it.updatedAt }
        val merged = mergeById(local, remoteCourses, { it.id }, { it.updatedAt }).filterNot { c ->
            remoteDeleted.any { t -> t.first == c.id && t.second >= c.updatedAt } ||
                (localCourseTombs[c.id]?.let { it >= c.updatedAt } == true)
        }

        // 2') 日程合并(同样 updatedAt 后者胜 + 两端墓碑过滤)
        val localAgenda = agendaDao.getAll().map { it.toDomain() }
        val localAgendaTombs = deletedAgendaDao.getAll().associate { it.id to it.updatedAt }
        val mergedAgenda = mergeById(localAgenda, remoteAgenda, { it.id }, { it.updatedAt })
            .filterNot { e ->
                remoteDeletedAgenda.any { t -> t.first == e.id && t.second >= e.updatedAt } ||
                    (localAgendaTombs[e.id]?.let { it >= e.updatedAt } == true)
            }

        // 3) 写回本地:覆盖式 upsert + 清理多余行
        courseDao.upsertAll(merged.map { CourseEntity.fromDomain(it) })
        val finalIds = merged.map { it.id }.toSet()
        courseDao.getAll().forEach { e -> if (e.id !in finalIds) courseDao.deleteById(e.id) }

        // 3') 日程写回本地
        agendaDao.upsertAll(mergedAgenda.map { AgendaEntity.fromDomain(it) })
        val finalAgendaIds = mergedAgenda.map { it.id }.toSet()
        agendaDao.getAll().forEach { e -> if (e.id !in finalAgendaIds) agendaDao.deleteById(e.id) }

        // 4) 推远端:课程 + 日程 + 墓碑 + 删除写。
        //
        // 此前这里只把墓碑 upsert 进 users/{uid}/deleted,却从不对 users/{uid}/courses 里的
        // 活文档下发删除写 —— 本地删掉的课程在云端永远残留(控制台可见,重新登录也会再拉回来)。
        // 现在每条墓碑都随附对云端活文档的 delete 写(文档已不存在时为空操作,幂等)。
        val mergedIds = merged.map { it.id }.toSet()
        val mergedAgendaIds = mergedAgenda.map { it.id }.toSet()

        // 墓碑并集 = 本地行 ∪ 远端墓碑,updatedAt 取两端较新。远端墓碑可能来自尚未升级的
        // 旧版设备(旧版只写墓碑、不删活文档),它们留下的残留要靠新版本在这里补删干净。
        val courseTombs = HashMap<String, Long>(localCourseTombs)
        remoteDeleted.forEach { (id, ts) -> courseTombs[id] = maxOf(courseTombs[id] ?: 0L, ts) }
        val agendaTombs = HashMap<String, Long>(localAgendaTombs)
        remoteDeletedAgenda.forEach { (id, ts) -> agendaTombs[id] = maxOf(agendaTombs[id] ?: 0L, ts) }

        val gcBefore = System.currentTimeMillis() - TOMBSTONE_TTL_MS
        val purgeCourseTombs = ArrayList<String>()
        val purgeAgendaTombs = ArrayList<String>()

        // Firestore 单次 commit 最多 500 个写,超限整单被拒,墓碑就永远推不上去 —— 课程/
        // 日程/墓碑一起计数,大课表或长期未同步后必超。分批顺序提交;每批全幂等,中途失败
        // 下次同步整体重推即可。
        //
        // 同一份数据的墓碑 update/delete 与活文档 delete 必须落在同一批里,否则先提交的
        // 墓碑被本地回收后,后一批失败会让云端只剩活文档,已删课程就会复活。
        val chunks = ArrayList<ArrayList<JSONObject>>()
        var current = ArrayList<JSONObject>()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current
                current = ArrayList()
            }
        }
        /** 追加同一个 id 的一组写;当前批装不下时先提交旧批。 */
        fun addIdWrites(writes: List<JSONObject>) {
            if (current.isNotEmpty() && current.size + writes.size > WRITES_PER_COMMIT) flush()
            current += writes
        }

        fun pushTombstones(
            tombs: Map<String, Long>,
            localTombs: Map<String, Long>,
            mergedIds: Set<String>,
            liveName: (String, String) -> String,
            tombName: (String, String) -> String,
            purgeList: MutableList<String>,
        ) {
            tombs.forEach { (id, ts) ->
                val live = id in mergedIds // 同 id 已重建有效课程(删后又加回):活文档不删
                val local = localTombs.containsKey(id)
                val writes = ArrayList<JSONObject>()
                if (ts < gcBefore) {
                    // 到期墓碑:云端墓碑文档连同活文档一并回收;本地行等所有 commit 送达后再清,
                    // 中途失败保留,下次同步重试
                    if (!live) writes.add(deleteWrite(liveName(uid, id)))
                    writes.add(deleteWrite(tombName(uid, id)))
                    if (local) purgeList.add(id)
                } else {
                    // 本地行不比远端旧才覆盖(取两端较新的 ts,不会把远端墓碑往旧写)
                    if (local && (localTombs[id] ?: 0L) >= ts) {
                        writes.add(updateWrite(tombName(uid, id), tombstoneFields(ts)))
                    }
                    if (!live) writes.add(deleteWrite(liveName(uid, id)))
                }
                addIdWrites(writes)
            }
        }

        merged.forEach { c ->
            addIdWrites(
                listOf(updateWrite(coursesDocName(uid, c.id), RemoteCourse.toFields(RemoteCourse.fromDomain(c)))),
            )
        }
        pushTombstones(courseTombs, localCourseTombs, mergedIds, ::coursesDocName, ::deletedDocName, purgeCourseTombs)
        mergedAgenda.forEach { e ->
            addIdWrites(
                listOf(updateWrite(agendaDocName(uid, e.id), RemoteAgenda.toFields(RemoteAgenda.fromDomain(e)))),
            )
        }
        pushTombstones(agendaTombs, localAgendaTombs, mergedAgendaIds, ::agendaDocName, ::deletedAgendaDocName, purgeAgendaTombs)
        flush()

        chunks.forEach { chunk ->
            gateway.firestore(
                "${dbBase()}:commit",
                "POST",
                JSONObject().put("writes", JSONArray(chunk)),
                token,
            )
        }

        // 云端墓碑与活文档的删除写都已随上面的 commit 送达,到这里才回收本地墓碑行
        if (purgeCourseTombs.isNotEmpty()) deletedDao.deleteByIds(purgeCourseTombs)
        if (purgeAgendaTombs.isNotEmpty()) deletedAgendaDao.deleteByIds(purgeAgendaTombs)

        Result.success(Unit)
    }

    /**
     * 拉取集合全部文档,返回 [{id, fields}]。
     *
     * Firestore REST 单次响应有 pageSize 上限,必须按 `nextPageToken` 翻页 —— 否则课程/日程
     * 超过一页时只拿到前缀,新设备上会出现「课表不完整」,而后续的本地清理又据此删掉多余行。
     */
    private suspend fun pullDocuments(colPath: String, token: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var pageToken: String? = null
        do {
            val sep = if (colPath.contains('?')) '&' else '?'
            val url = buildString {
                append(colPath)
                append(sep)
                append("pageSize=300")
                if (!pageToken.isNullOrBlank()) {
                    append("&pageToken=")
                    append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
                }
            }
            val resp = gateway.firestore(url, "GET", null, token)
            val docs = resp.optJSONArray("documents")
            if (docs != null) {
                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    val fields = doc.optJSONObject("fields") ?: continue
                    out.add(JSONObject().put("id", doc.optString("name").substringAfterLast('/')).put("fields", fields))
                }
            }
            pageToken = resp.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
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
            JSONObject().put("writes", JSONArray().put(updateWrite(settingsDocName(uid), fields))),
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
            themeMode = enumOrDefault(str("themeMode"), ThemeMode.SYSTEM),
            accentHex = str("accentHex").ifBlank { Defaults.ACCENT_HEX },
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

    /** 按 id 合并:updatedAt 后者胜(课程与日程共用)。 */
    private fun <T> mergeById(
        local: List<T>,
        remote: List<T>,
        id: (T) -> String,
        updatedAt: (T) -> Long,
    ): List<T> {
        val byId = HashMap<String, T>()
        local.forEach { byId[id(it)] = it }
        remote.forEach { r ->
            val l = byId[id(r)]
            if (l == null || updatedAt(r) > updatedAt(l)) byId[id(r)] = r
        }
        return byId.values.toList()
    }
}
