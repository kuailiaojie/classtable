# Windows 客户端 API 与多端同步文档

> 面向 Windows 客户端开发者。目标不是“能读到数据”，而是**和 Android 端读写同一份云数据时保持一致**：
> 新建的课能看到、改过的课不丢、删掉的课不复活、配置不会被新设备覆盖。
>
> 本文依据 Android 端实现整理：`data/AuthRepository.kt`、`data/SyncRepository.kt`、
> `data/RemoteCourse.kt`、`data/RemoteAgenda.kt`、`data/PartnerRepository.kt`、
> `data/FirebaseGateway.kt`、`netlify/functions/proxy.mjs`、`firestore.rules`。
> 当前 App 版本 3.14.0（versionCode 100）。

---

## 0. 一致性契约（先看这一节）

Windows 端只要遵守下面 11 条，就能和 Android 端稳定共存：

1. **本地优先，云端只做交换所。** 未登录时纯本地运行；登录后同步是“拉取 → 合并 → 落本地 → 推送”，
   不是“以云端为准直接覆盖本地”。
2. **每一份课程 / 日程都必须带 `updatedAt`**，单位是 **epoch 毫秒**（不是秒、不是 ISO 字符串）。
   任何一次编辑都要把它推进到 `max(本机当前毫秒, 旧值 + 1)`。
3. **冲突用 LWW（Last-Write-Wins）解决**：同一个 `id`，`updatedAt` 大的一方赢；
   **相等时本地赢**（Android 的合并函数只在远端严格更大时才替换）。
4. **删除必须写墓碑，不能只删活文档。** 活文档 `courses/{id}` 删除的同时，
   要在 `deleted/{id}` 写 `{updatedAt}`；否则下一次拉取会把课程“复活”。
5. **墓碑保留 180 天**。到期后要连同云端活文档和墓碑文档一起回收，否则 `deleted` 集合会无限增长。
6. **同步顺序固定**：先同步设置单文档，再拉取四套集合，然后合并、写本地、最后分批 `:commit` 推送。
   不要先推送再拉取。
7. **一次 `:commit` 最多 400 个写操作**（Firestore 硬上限 500）。超过就顺序分批；
   同一个 `id` 的“墓碑写入 + 活文档删除”必须落在同一批里。
8. **拉取必须翻页**。课程或日程超过 300 条时只拿第一页，会导致“课表不完整”，
   后续的本地清理还会据此删掉有效数据。
9. **写入是整文档替换。** `commit` 的 `update` 不带 `updateMask` 时会替换整个文档；
   Windows 端不要只写自己关心的字段（尤其设置文档，漏字段会被 Android 按默认值解析）。
   读取时请保留自己不认识的新字段，再原样写回。
10. **不要用服务端的 `createTime` / `updateTime` 做冲突判断。** 业务时间只看文档里的 `updatedAt`。
11. **应用远端数据时原样保存 `updatedAt`，不要重新打时间戳。** 只有用户在本机实际编辑时才推进时间戳；
    否则同步本身会不断把数据“变新”，造成两端来回覆盖。

---

## 1. 接入信息

| 项 | 值 |
|---|---|
| 反代 Base URL | `https://classtablek.netlify.app/.netlify/functions/proxy` |
| 静态站点 Base URL | `https://classtablek.netlify.app` |
| Firebase 项目 ID | `classtable-4a7d0` |
| Firebase Web API Key | `AIzaSyBxhhRE5gDw8_jUwghAWc7oC83MOGf9rPY`（公开值，不是机密） |
| 认证方式 | Email / Password |
| 传输 | HTTPS，请求与响应均为 UTF-8 JSON |
| 客户端超时参考 | 连接 15s，读取 20s |

> 反代地址是编译期常量。正式接入时建议把它做成 Windows 端的可配置项，默认填上面的地址，
> 便于自建站点或切换域名。API Key 本来就是公开值，安全依赖 Firestore 规则 + HTTPS。

### 1.1 路径映射

所有 Firebase 请求都经反代转发，客户端只访问站点域名：

| 客户端路径 | 上游 |
|---|---|
| `POST {base}/auth/v1/...` | `https://identitytoolkit.googleapis.com/v1/...` |
| `{base}/firestore/v1/projects/{projectId}/databases/(default)/documents/...` | `https://firestore.googleapis.com/v1/...` |

反代只放行以下路径；不在放行列表里的 Auth / Firestore 路径返回 `403`，完全未知的路径返回 `404`：

- Auth：`accounts:signUp`、`accounts:signInWithPassword`、`accounts:sendOobCode`、
  `token`、`accounts:delete`、`accounts:lookup`
- Firestore：`users/{uid}/...` 子树、`shares/{6 位提取码}`、`documents:commit`、`documents:batchGet`

**不支持** `:runQuery`、`:listen`、`:batchWrite`。也就是说：**没有实时监听，也不能服务端查询/过滤**，
必须整集合拉取后在本地筛选。

### 1.2 App Check 与 Windows

Android 用“APK 签名证书 SHA-256”换取 `X-Firebase-AppCheck` 头。Windows 端没有 Android 签名，
但项目已在 Firebase 控制台启用 App Check 强制校验，**不带头的请求会被直接拒绝**
（Auth 返回 401 `Firebase App Check token is invalid.`）。

因此反代的 `POST {base}/appcheck/token` 增加了桌面端分支，Windows 客户端按下面的格式换 token，
再把返回的 `token` 作为 `X-Firebase-AppCheck` 附在所有 Auth / Firestore 请求上：

```http
POST {base}/appcheck/token
Content-Type: application/json

{
  "platform": "desktop",
  "appKey": "<与反代 APP_CHECK_DESKTOP_KEY 一致的客户端标识>",
  "timestamp": 1800000000000
}
```

成功响应：`{ "token": "...", "expireTimeMillis": <epoch 毫秒> }`。token 约 30 分钟有效，
建议缓存并在剩余不足 5 分钟时刷新；**失败放行**（拿不到就不加头），不要把 App Check 失败当成登录失败。
桌面标识与 Android 签名指纹同级：只用于抬高“抄反代地址白嫖配额”的门槛，不是硬件级证明。
默认值编译在客户端 `CloudConfig.DesktopAppCheckKey` 与 `proxy.mjs` 里；轮换时在 Netlify 设置
`APP_CHECK_DESKTOP_KEY` 并同步发版客户端。

### 1.3 CORS

反代转发成功的响应带 `Access-Control-Allow-Origin: *`。但函数本身没有专门处理 `OPTIONS` 预检，
因此：

- 原生 HTTP 客户端（.NET `HttpClient`、WinHTTP、Rust reqwest 等）**没有任何问题**；
- 若在 WebView2 里用跨域 `fetch` 调反代，预检可能失败。建议由宿主进程代理请求，或让页面与反代同源。

### 1.4 重试约定

| 情况 | 处理 |
|---|---|
| 连接超时 / 断连 / `5xx` | 可重试，最多 3 次，间隔 2s |
| Auth 业务错误（`4xx` + 错误码） | 不重试，直接提示用户 |
| Firestore `401 / UNAUTHENTICATED` | 先刷新 ID Token，再重试一次 |
| Firestore `403 / PERMISSION_DENIED` | 不重试。通常是 uid 不匹配、路径越权或规则拒绝 |
| `409`（文档已存在）/ `429` | 不盲目重试；分享码冲突换码，限流则退避 |

---

## 2. 认证 API

### 2.1 注册

```http
POST {base}/auth/v1/accounts:signUp?key={API_KEY}
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "至少6位",
  "returnSecureToken": true
}
```

成功响应（字段为 camelCase）：

```json
{
  "kind": "identitytoolkit#SignupNewUserResponse",
  "localId": "<uid>",
  "email": "user@example.com",
  "displayName": "",
  "idToken": "<JWT>",
  "registered": true,
  "refreshToken": "<opaque>",
  "expiresIn": "3600"
}
```

`localId` 就是后续所有 `users/{uid}/...` 路径里的 uid。

### 2.2 登录

```http
POST {base}/auth/v1/accounts:signInWithPassword?key={API_KEY}
Content-Type: application/json

{ "email": "user@example.com", "password": "******", "returnSecureToken": true }
```

响应结构与注册相同。关键字段：`localId`、`idToken`、`refreshToken`、`expiresIn`（秒，字符串）。

### 2.3 刷新 ID Token

`idToken` 默认 1 小时有效。建议在到期前 **60 秒**主动刷新：

```http
POST {base}/auth/v1/token?key={API_KEY}
Content-Type: application/json

{ "grant_type": "refresh_token", "refresh_token": "<refreshToken>" }
```

响应字段是 **snake_case**（和登录接口不同，注意别解析错）：

```json
{
  "access_token": "<JWT>",
  "expires_in": "3600",
  "token_type": "Bearer",
  "refresh_token": "<opaque，可能轮换>",
  "id_token": "<JWT>",
  "user_id": "<uid>",
  "project_id": "classtable-4a7d0"
}
```

`refresh_token` 可能被轮换，务必用响应里的新值覆盖旧值。刷新失败（`INVALID_REFRESH_TOKEN` /
`TOKEN_EXPIRED`）时清空本地会话，回到未登录状态。

### 2.4 发送重置密码邮件

```http
POST {base}/auth/v1/accounts:sendOobCode?key={API_KEY}

{ "requestType": "PASSWORD_RESET", "email": "user@example.com" }
```

成功响应只含 `kind` 和 `email`，**不含 token**，不要按登录响应解析。
出于防枚举考虑，UI 可以无论成功失败都提示“如果邮箱已注册，重置邮件已发送”。

### 2.5 查询账号 / 注销账号（可选）

```http
POST {base}/auth/v1/accounts:lookup?key={API_KEY}
{ "idToken": "<JWT>" }

POST {base}/auth/v1/accounts:delete?key={API_KEY}
{ "idToken": "<JWT>" }
```

Android 端当前没有使用这两个接口；Windows 端如提供“注销账号”功能可以使用。

### 2.6 错误格式

身份平台即使参数/凭证错误，HTTP 也常返回 `400`，必须看 `error.message`：

```json
{
  "error": {
    "code": 400,
    "message": "INVALID_LOGIN_CREDENTIALS",
    "errors": [{ "message": "INVALID_LOGIN_CREDENTIALS", "domain": "global", "reason": "invalid" }]
  }
}
```

Android 端已映射的常见错误码：

| 错误码 | 含义 |
|---|---|
| `EMAIL_EXISTS` | 邮箱已注册 |
| `EMAIL_NOT_FOUND` | 邮箱未注册 |
| `INVALID_LOGIN_CREDENTIALS` / `INVALID_PASSWORD` | 邮箱或密码错误 |
| `INVALID_EMAIL` | 邮箱格式不正确 |
| `WEAK_PASSWORD` | 密码过弱（至少 6 位） |
| `USER_DISABLED` | 账号已禁用 |
| `TOO_MANY_ATTEMPTS_TRY_LATER` | 尝试次数过多 |
| `OPERATION_NOT_ALLOWED` | 该操作未启用（检查 Firebase 控制台是否开启 Email/Password） |
| `EXPIRED_OOB_CODE` / `INVALID_OOB_CODE` | 重置链接过期 / 无效 |
| `UNAUTHENTICATED` | 登录态失效，需要重新登录 |

### 2.7 会话保存

- `refreshToken` 是长期凭证，必须用 Windows 的 **DPAPI（`ProtectedData`）** 或凭据管理器加密保存。
- 保存内容建议：`uid`、`email`、`idToken`、`refreshToken`、`expiresAt`（epoch 毫秒）。
- 退出登录只清空会话，**不要删除本地课表**：Android 的退出登录同样只清会话，
  本地数据继续以访客模式可用，下次登录再按正常合并流程同步。

---

## 3. Firestore REST 访问约定

### 3.1 URL 构造

```
{PROXY_BASE}/firestore/v1/projects/{PROJECT_ID}/databases/(default)/documents/{path}
```

例如拉取课程集合：

```http
GET {PROXY_BASE}/firestore/v1/projects/classtable-4a7d0/databases/(default)/documents/users/{uid}/courses?pageSize=300
Authorization: Bearer {idToken}
```

所有 Firestore 请求都要带 `Authorization: Bearer {idToken}`。
请求体与响应体都是 JSON，`Content-Type: application/json`。

文档 id 作为路径段时必须 URL 编码（至少把 `/` 编码成 `%2F`，空格编码成 `%20`）。
课程 id 建议统一用 UUID，避免特殊字符。

### 3.2 字段值编码

Firestore REST 的值是带类型标签的对象，整数必须写成**字符串**：

```json
{
  "stringValue": "高等数学（上）",
  "integerValue": "1762300000000",
  "booleanValue": true
}
```

| 业务类型 | Firestore REST |
|---|---|
| 字符串 | `{"stringValue": "..."}` |
| 整数 / 时间戳 | `{"integerValue": "123"}` |
| 布尔 | `{"booleanValue": true}` |
| 可选字段 | 直接省略该键，不要写 `null` |

### 3.3 读取文档

```http
GET .../documents/users/{uid}/settings/config
```

响应：

```json
{
  "name": "projects/classtable-4a7d0/databases/(default)/documents/users/{uid}/settings/config",
  "fields": { "...": { "stringValue": "..." } },
  "createTime": "2026-10-01T00:00:00Z",
  "updateTime": "2026-10-01T00:00:00Z"
}
```

文档不存在时返回 `404`（`NOT_FOUND` / message 含 `not found`），按“空”处理。

### 3.4 拉取集合（必须翻页）

```http
GET .../documents/users/{uid}/courses?pageSize=300
```

响应：

```json
{
  "documents": [
    { "name": ".../users/{uid}/courses/{id}", "fields": { "...": {} } }
  ],
  "nextPageToken": "..."
}
```

如果有 `nextPageToken`，继续带 `pageToken={token}` 请求，直到为空。
课程、删除墓碑、日程、日程墓碑四个集合都要这样翻页。

### 3.5 批量写入 `:commit`

```http
POST {PROXY_BASE}/firestore/v1/projects/classtable-4a7d0/databases/(default)/documents:commit
Authorization: Bearer {idToken}
Content-Type: application/json

{
  "writes": [
    {
      "update": {
        "name": "projects/classtable-4a7d0/databases/(default)/documents/users/{uid}/courses/{id}",
        "fields": { "id": { "stringValue": "{id}" }, "...": {} }
      }
    },
    {
      "delete": "projects/classtable-4a7d0/databases/(default)/documents/users/{uid}/courses/{deletedId}"
    }
  ]
}
```

要点：

- `name` **不带** `/v1`，固定以 `projects/` 开头。
- `update` 不带 `updateMask` = **整文档替换**，必须写全所有字段。
- `delete` 的值就是完整资源名；文档不存在时是空操作，天然幂等。
- 单次最多 400 个写操作，按顺序分批提交。
- 成功响应含 `writeResults`、`commitTime`；无需处理 `writeResults` 内容。

### 3.6 单文档 PATCH / DELETE（可选）

反代也允许对 `users/{uid}/...` 下发 `PATCH` / `DELETE`。
`PATCH` 只合并请求里出现的字段（Android 只用来写 `devices`）。课程 / 日程 / 设置仍建议统一走
`:commit`，保持与 Android 相同的整文档语义。

### 3.7 Firestore 错误

```json
{
  "error": {
    "code": 403,
    "message": "Missing or insufficient permissions.",
    "status": "PERMISSION_DENIED"
  }
}
```

| `status` / message | 含义 | 处理 |
|---|---|---|
| `PERMISSION_DENIED` | uid 不匹配 / 未登录 / 访问了未放行路径 | 不要重试，检查 uid 与 token |
| `NOT_FOUND` | 文档不存在 | 按空处理（设置文档、分享码查不到） |
| `ALREADY_EXISTS` / 含 `already exists` | 写入前置条件失败（分享码撞车） | 换一个提取码重试 |
| `INVALID_ARGUMENT` | 字段类型/路径错误 | 修 bug，不要重试 |
| `RESOURCE_EXHAUSTED` / `429` | 配额或限流 | 退避后重试，减少同步频率 |

> 反代自身失败时返回 `{ "error": { "code": 502, "message": "upstream error: ..." } }`，
> 这类错误通常是网络/上游问题，可以重试。

### 3.8 安全规则的字段数上限

`firestore.rules` 会检查写入字段数量，超了直接拒绝：

| 集合 | 最多字段 |
|---|---|
| `users/{uid}/courses/{id}` | 30 |
| `users/{uid}/agenda/{id}` | 30 |
| `users/{uid}/settings/config` | 30 |
| `users/{uid}/deleted/{id}` | 10 |
| `users/{uid}/deleted_agenda/{id}` | 10 |
| `users/{uid}/devices/{id}` | 10 |
| `users/{uid}/semesters/{id}` | 15 |
| `shares/{code}` | 12 |

本文给出的字段集都在上限内，但**不要往这些文档里随手加调试字段**。
读取时保留未知字段是为了向前兼容，写回时也要确保字段总数没有超过上限。

---

## 4. 云数据模型

### 4.0 先明确：哪些数据会跨端一致

| 数据 | 是否同步 | 位置 | 说明 |
|---|---|---|---|
| 课程 | ✅ | `users/{uid}/courses` | 主课表，Windows 必须读写这里 |
| 课程删除墓碑 | ✅ | `users/{uid}/deleted` | 不写会导致课程复活 |
| 日程 / 倒计时 | ✅ | `users/{uid}/agenda` | 与课程同一套 LWW |
| 日程删除墓碑 | ✅ | `users/{uid}/deleted_agenda` | |
| 同步设置 | ✅ | `users/{uid}/settings/config` | 主题、强调色、当前周、作息、学期起止、AI 提供商/地址/模型、提醒开关与提前量、调休 |
| 学期列表 | ❌ | 本机 | 只通过设置里的 `semesterStartDay` / `semesterWeekCount` 传递 |
| 伴侣 / 好友课表 | ❌ | 本机 | 通过 6 位提取码交换，不并入主课表 |
| 雨课堂会话、公告、课程绑定 | ❌ | 本机 | 与账号无关的本机数据 |
| AI 密钥、助手长期记忆 | ❌ | 本机 | 密钥不落云端 |
| 图标、引导标记、通知细项、更新偏好 | ❌ | 本机 | 每台设备自己的设置 |
| 设备推送 token | ✅（仅 Android） | `users/{uid}/devices` | Windows 不要写 |
| 分享文档 | ✅ | `shares/{code}` | 独立于用户树，按提取码访问 |

> 换句话说：**只有课程、日程、删除墓碑和那 13 个同步设置字段需要 Windows 端与 Android 严格对齐。**
> 其余数据要么是本机专属，要么走独立通道，不要为了“多端一致”把它们塞进主课表。

所有用户数据都在：

```
users/{uid}/
  courses/{courseId}          课程
  deleted/{courseId}          课程删除墓碑
  agenda/{agendaId}           日程 / 倒计时
  deleted_agenda/{agendaId}   日程删除墓碑
  settings/config             同步配置（单文档）
  devices/{deviceId}          Android FCM 设备（Windows 不需要写）
  semesters/{semesterId}      预留，当前 Android 未同步
```

### 4.1 课程 `users/{uid}/courses/{courseId}`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 必须与文档 id 完全一致 |
| `name` | string | 是 | 课程名 |
| `teacher` | string | 否 | 教师 |
| `location` | string | 否 | 地点 |
| `weekday` | int | 是 | 主星期，1=周一 … 7=周日 |
| `weekdays` | int | 是 | 星期位掩码，`bit(day-1)`；0 时 Android 按 `weekday` 推导 |
| `startPeriod` | int | 是 | 起始节次（1-based）；自定义时间课程为 0 |
| `endPeriod` | int | 是 | 结束节次（1-based） |
| `weekType` | string | 是 | `EVERY_WEEK` / `ODD_WEEK` / `EVEN_WEEK` / `CUSTOM` |
| `weekStart` | int | 是 | 周次范围起 |
| `weekEnd` | int | 是 | 周次范围止 |
| `weeks` | string | 是 | 精确周次 CSV，如 `"4,6,8"`；空串 = 沿用 `weekStart..weekEnd` |
| `semesterId` | string | 是 | 默认 `"default"`；伴侣课程用伴侣 id（本地概念） |
| `updatedAt` | int | 是 | epoch 毫秒，LWW 依据 |
| `customStartMinute` | int | 否 | 自定义开始分钟（自 0:00），与 `customEndMinute` 成对出现 |
| `customEndMinute` | int | 否 | 自定义结束分钟 |
| `note` | string | 否 | 备注 |
| `colorHex` | string | 否 | 用户指定颜色，`#RRGGBB` 或 `#AARRGGBB`；空串 = 自动配色 |
| `colorHue` | int | 否 | 自动配色色相 0..359；省略 = 按课程名现算 |

完整示例：

```json
{
  "name": "projects/classtable-4a7d0/databases/(default)/documents/users/UID/courses/6f1c2a9e-0000-4000-8000-000000000001",
  "fields": {
    "id":        { "stringValue": "6f1c2a9e-0000-4000-8000-000000000001" },
    "name":      { "stringValue": "高等数学（上）" },
    "teacher":   { "stringValue": "张老师" },
    "location":  { "stringValue": "教1-201" },
    "weekday":   { "integerValue": "1" },
    "weekdays":  { "integerValue": "5" },
    "startPeriod": { "integerValue": "1" },
    "endPeriod":   { "integerValue": "2" },
    "weekType":  { "stringValue": "EVERY_WEEK" },
    "weekStart": { "integerValue": "1" },
    "weekEnd":   { "integerValue": "16" },
    "weeks":     { "stringValue": "" },
    "semesterId": { "stringValue": "default" },
    "customStartMinute": { "integerValue": "480" },
    "customEndMinute":   { "integerValue": "590" },
    "note":      { "stringValue": "" },
    "colorHex":  { "stringValue": "" },
    "colorHue":  { "integerValue": "345" },
    "updatedAt": { "integerValue": "1762300000000" }
  }
}
```

语义细节（与 Android 显示逻辑对齐）：

- **`weekdays` 是权威值**。例如周一 + 周三 = `1 | 4 = 5`。`weekday` 建议取掩码中最小的一天，
  仅作为旧数据兼容与默认排序依据。
- **`EVERY_WEEK` 完全忽略范围**，任何周都显示。
- `ODD_WEEK` / `EVEN_WEEK` 在 `weekStart..weekEnd` 内按**学期绝对周次**判奇偶，
  所以“第 3-19 周的双周”是第 4、6、8…18 周。
- `CUSTOM` 优先用 `weeks` 精确集合；`weeks` 为空时退回 `weekStart..weekEnd`。
- `customStartMinute` / `customEndMinute` 是**数据**，不是派生值。Android 在新建 / 导入 / 编辑时会按
  当前作息把节次换算成分钟并钉住。Windows 端编辑课程时也应这样做，否则两端对
  “第 3 节”的展示可能因作息差异而不同。
- `startPeriod <= 0` 表示“自定时间课程”，此时以 `customStartMinute` / `customEndMinute` 为准。
- 课程颜色：`colorHex` 优先；否则用 `colorHue`；都没有时 Android 按课程名派生。
- 如果 `customStartMinute` / `customEndMinute` 都缺失，按 `periodTimes` 的对应节次换算显示时间；
  与 Android 一致。不要因为缺这两个字段就认为课程没有时间。

### 4.2 课程删除墓碑 `users/{uid}/deleted/{courseId}`

只放一个字段：

```json
{
  "name": "projects/classtable-4a7d0/databases/(default)/documents/users/UID/deleted/{courseId}",
  "fields": {
    "updatedAt": { "integerValue": "1762300000000" }
  }
}
```

- 文档 id 与课程 id 相同。
- 判定：`墓碑.updatedAt >= 课程.updatedAt` → 该课程已删除。
- 删除发生在另一台设备时，Windows 拉取到墓碑后必须删掉本地对应课程。
- 墓碑不要仅写本地：本地墓碑也要推送到云端，云端活文档同时删除。

### 4.3 日程 / 倒计时 `users/{uid}/agenda/{agendaId}`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 与文档 id 一致 |
| `title` | string | 是 | 标题 |
| `category` | string | 是 | `TODO` / `ACTIVITY` / `EXAM` / `HOMEWORK` / `OTHER` |
| `startAt` | int | 是 | 开始时间，epoch 毫秒 |
| `endAt` | int | 是 | 结束时间，epoch 毫秒 |
| `allDay` | bool | 是 | 全天；为 true 时时间部分按当天 0:00 |
| `location` | string | 否 | 地点 |
| `note` | string | 否 | 备注 |
| `priority` | string | 是 | `NONE` / `LOW` / `MEDIUM` / `HIGH` |
| `remindEnabled` | bool | 是 | 该条是否提醒 |
| `remindLeadMinutes` | int | 是 | 提前分钟数，默认 10 |
| `updatedAt` | int | 是 | epoch 毫秒 |
| `courseId` | string | 否 | 关联课程 id |
| `completed` | bool | 是 | 是否完成 |

分类决定它落在哪个页签（这是 Android 端的展示约定，Windows 端建议保持一致）：

- `TODO` / `ACTIVITY` / `OTHER` →「日程」
- `EXAM` / `HOMEWORK` →「倒计时」

### 4.4 日程删除墓碑 `users/{uid}/deleted_agenda/{agendaId}`

结构、判定、回收规则与课程墓碑完全一致：

```json
{ "fields": { "updatedAt": { "integerValue": "1762300000000" } } }
```

### 4.5 同步配置 `users/{uid}/settings/config`

单文档，整包 LWW。**写入时必须写全下面 13 个字段**，缺字段会被 Android 按默认值解析：

| 字段 | 类型 | Android 默认 | 说明 |
|---|---|---|---|
| `themeMode` | string | `SYSTEM` | `SYSTEM` / `LIGHT` / `DARK` |
| `accentHex` | string | `#C56473` | 强调色 |
| `currentWeek` | int | `1` | 用户当前查看的周 |
| `periodTimes` | string | 见下 | 作息表 |
| `semesterStartDay` | int | `0` | **epochDay**（不是毫秒）；0 = 未设置 |
| `semesterWeekCount` | int | `20` | 学期总周数 |
| `aiProvider` | string | `GEMINI` | `GEMINI` / `OPENAI_COMPAT` |
| `aiBaseUrl` | string | `""` | OpenAI 兼容服务地址 |
| `aiModel` | string | `""` | 模型名 |
| `notificationsEnabled` | bool | `true` | 课程提醒总开关 |
| `notifyLeadMinutes` | int | `10` | 课前提醒分钟数，0..180 |
| `scheduleAdjustments` | string | `""` | 调休 JSON 字符串，见下 |
| `updatedAt` | int | `0` | epoch 毫秒，设置 LWW 依据 |

`periodTimes` 默认值：

```
480-530,540-590,610-660,670-720,840-890,900-950,970-1020,1030-1080,1140-1190,1200-1250,1260-1310,1320-1370
```

格式是 `开始分钟-结束分钟` 的 CSV（自 0:00 起）；每天第 1 节对应第一个区间。
也兼容旧的“纯开始时间”格式（`480,540,610,...`），但新写入请统一成 `start-end`。

旧版本曾把下面这串**错误默认值**写进云端：

```
480-530,530-610,610-660,660-840,840-890,890-970,970-1020,1020-1140,1140-1190,1190-1240,1240-1290,1290-1340
```

Android 读到它会视为“从未设置”，回退到上面的新默认值。Windows 端请照做，不要把它当作用户自定义作息。

`scheduleAdjustments` 是 JSON **字符串**，内容是一个数组：

```json
[
  { "date": "2026-10-01" },
  { "date": "2026-10-11", "source": "2026-10-02", "label": "国庆调休" }
]
```

- `date`：生效的自然日（ISO `yyyy-MM-dd`）。
- `source`：省略 = 当天停课；存在 = 当天按 `source` 那一天的课表上课（不追链）。
- `label`：可选备注。

完整写入示例：

```json
{
  "fields": {
    "themeMode":            { "stringValue": "SYSTEM" },
    "accentHex":            { "stringValue": "#C56473" },
    "currentWeek":          { "integerValue": "5" },
    "periodTimes":          { "stringValue": "480-530,540-590,..." },
    "semesterStartDay":     { "integerValue": "20710" },
    "semesterWeekCount":    { "integerValue": "20" },
    "aiProvider":           { "stringValue": "GEMINI" },
    "aiBaseUrl":            { "stringValue": "" },
    "aiModel":              { "stringValue": "" },
    "notificationsEnabled": { "booleanValue": true },
    "notifyLeadMinutes":    { "integerValue": "10" },
    "scheduleAdjustments":  { "stringValue": "" },
    "updatedAt":            { "integerValue": "1762300000000" }
  }
}
```

**不参与云同步的字段**（Android 只存本机，Windows 也不要写进云端）：

AI 密钥（`aiApiKey`）、提醒形态（`notifyMode`）、明日课程预告与时刻、日程提醒开关、
自动检查更新相关、`onboardingDone`、`introSeenVersion`、雨课堂全部开关、图标与轮播设置、
助手长期记忆。

#### 设置同步规则（不要用“新设备默认值覆盖云端”）

Android 的三分支逻辑：

1. 远端不存在且本地 `updatedAt == 0`：什么都不做（全新设备）。
2. 远端不存在且本地改过：推送本地。
3. 远端存在：
   - `remote.updatedAt > local.updatedAt` → 应用远端（只覆盖同步字段）；
   - 否则 → 推送本地（相等也推，幂等）。

Windows 端照做即可。注意：设置是**整文档 LWW**，两个设备同时改不同设置项时，后写的一方
会覆盖整份文档，这是当前协议接受的取舍。因此 Windows 修改单个设置项时，建议先拉一次
`settings/config`，把自己的改动合并进远端整包，再以新的 `updatedAt` 推送，尽量少覆盖其他设备刚改的字段。

### 4.6 设备 `users/{uid}/devices/{deviceId}`（Android 专用）

Android 会把 Firebase Installation ID 写进这个集合，供 `/push` 定向发 FCM：

```json
{ "fields": { "token": { "stringValue": "<FID>" }, "updatedAt": { "integerValue": "..." } } }
```

Windows 客户端的推送体系与 FCM 不同，**不要复用这个集合**，也不要写入 Android 的 token。
如果需要 Windows 推送，应另起方案（例如自建 WebSocket / WNS），不要破坏现有约定。

### 4.7 学期 `users/{uid}/semesters/{semesterId}`（预留）

规则允许读写（≤15 字段），但**当前 Android 端没有同步这个集合**：
学期信息实际通过设置里的 `semesterStartDay` / `semesterWeekCount` 传递。
Windows 端不要依赖这个集合做一致性判断；如果写入，Android 端不会读取，也不会删除它。

### 4.8 分享 `shares/{code}`

见第 6 节。

---

## 5. 同步算法（Windows 端实现标准）

### 5.1 触发时机

Android 没有实时监听，按需同步。Windows 建议：

- 登录成功后立即同步一次；
- 应用启动 / 窗口重新获得焦点时；
- 本机发生课程、日程或设置修改后，做 **5–10 秒防抖**再同步；
- 网络恢复时；
- 用户手动点“立即同步”。

不要在每次键盘输入时同步；Netlify 免费额度 12.5 万次请求/月，且每次同步至少 5 个请求。

访客模式下创建的本地课程 / 日程也走同一套合并逻辑：登录后的第一次同步会把本地与云端合并
（按 `updatedAt` 取新），不会直接丢弃本地数据。

### 5.2 完整流程

```text
sync(uid, token):
    token = ensureFreshToken()

    # 1) 设置单文档（失败即整体失败，先做）
    remoteSettings = GET users/{uid}/settings/config   # 404 -> null
    localSettings  = loadLocalSettings()
    if remoteSettings == null:
        if localSettings.updatedAt > 0:
            PUSH_SETTINGS(localSettings)
    elif remoteSettings.updatedAt > localSettings.updatedAt:
        APPLY_SETTINGS(remoteSettings)                 # 只应用 13 个同步字段
    else:
        PUSH_SETTINGS(localSettings)                   # 相等也推，幂等

    # 2) 拉取四套集合（全部翻页）
    remoteCourses  = LIST users/{uid}/courses
    remoteDeleted  = LIST users/{uid}/deleted
    remoteAgenda   = LIST users/{uid}/agenda
    remoteDelAgenda= LIST users/{uid}/deleted_agenda

    # 3) 合并课程（LWW，本地先入表，远端严格更大才替换）
    merged = map()
    for c in localCourses: merged[c.id] = c
    for c in remoteCourses:
        if merged[c.id] == null or c.updatedAt > merged[c.id].updatedAt:
            merged[c.id] = c

    remoteTomb = map(remoteDeleted.id -> updatedAt)
    localTomb  = map(localDeleted.id  -> updatedAt)

    merged = merged.filter(c =>
        !(remoteTomb[c.id] >= c.updatedAt) &&
        !(localTomb[c.id]  >= c.updatedAt)
    )

    # 4) 日程同理（用 agenda / deleted_agenda）

    # 5) 覆盖写本地：先 upsert 合并结果，再删掉本地多余行
    replaceLocalCourses(merged)
    replaceLocalAgenda(mergedAgenda)

    # 6) 推送（分批，每批 <= 400 写）
    buildWrites:
        for c in merged:
            write(update courses/{c.id}, fullFields(c))
        for (id, ts) in unionTombs(localTomb, remoteTomb):   # 取两端较大值
            live = id in merged
            if ts < now - 180d:
                if !live: write(delete courses/{id})
                write(delete deleted/{id})
                pendingPurge.add(id)
            else:
                if localTomb.containsKey(id) && localTomb[id] >= ts:
                    write(update deleted/{id}, {updatedAt: ts})
                if !live:
                    write(delete courses/{id})
        # 日程同理
    for chunk in chunks(<=400):
        POST documents:commit

    # 7) 所有 commit 成功后才清理本地过期墓碑
    purgeLocalTombs(pendingPurge)
```

### 5.3 为什么必须这样

- **先拉后推**：如果先推，本地旧数据会覆盖其他设备的新数据。
- **墓碑和活文档删除同批**：如果先提交墓碑、再单独提交活文档删除，而第二批失败，
  本地墓碑可能被回收，云端就会留下“活文档无墓碑”的状态，课程复活。
- **按 id 聚合同批**：同一个 id 的 `deleted/{id}` 写入与 `courses/{id}` 删除必须一起成功或一起失败。
- **拉取翻页**：Android 端已经因为“只拿第一页”导致过新设备课表不完整；随后按不完整结果清理本地，
  反而删掉有效课程。
- **过期墓碑回收**：墓碑超过 180 天就失去压制意义；只保留会导致集合无限增长、同步越来越慢。

### 5.4 本地存储建议

SQLite（或任何本地库）至少保留五张表：

| 表 | 关键列 |
|---|---|
| `courses` | `id` 主键、业务字段、`updated_at` |
| `deleted_courses` | `id` 主键、`updated_at` |
| `agenda` | `id` 主键、业务字段、`updated_at` |
| `deleted_agenda` | `id` 主键、`updated_at` |
| `settings` | 单行，含 `updated_at` |

本地删除课程时：删 `courses` 行 + 插/更新 `deleted_courses` 行 + 触发同步。
**不要直接删云端活文档而不同时写墓碑。**

墓碑时间戳建议取 `max(当前毫秒, 被删课程的 updatedAt + 1)`。如果本机时钟落后于最后一次编辑，
直接用 `now` 可能小于课程时间戳，墓碑就压不住这门课，下次同步会把它拉回来。

### 5.5 时间戳与时钟偏移

协议里没有服务端时间戳，完全信任客户端时钟。为了减少冲突：

- 每次编辑：`newUpdatedAt = max(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), oldUpdatedAt + 1)`。
- 从旧版本 / 导入数据得到 `updatedAt == 0` 时，在首次编辑前补一个当前时间。
- 如果检测到本机时钟明显偏差（例如响应 `Date` 头与本机相差超过 5 分钟），同步前提示用户校准。
- 不要用秒级时间戳：两台设备同一秒内的修改会大量并列。

### 5.6 多端一致性验收清单

实现完成后至少覆盖这些场景：

| # | 场景 | 期望 |
|---|---|---|
| 1 | Windows 新建课程 → Android 同步 | Android 出现该课程 |
| 2 | Android 编辑课程 → Windows 同步 | Windows 显示新内容 |
| 3 | Windows 删除课程 → Android 同步 | Android 删除，重新同步不复活 |
| 4 | Android 删除课程 → Windows 同步 | Windows 删除，重新同步不复活 |
| 5 | 一端离线删除，另一端离线改名，先删后改同步 | 按 `updatedAt` 后者胜，结果符合时间顺序 |
| 6 | 课程超过 300 条 | 翻页后完整拉取，不丢数据 |
| 7 | 一次同步超过 400 个写 | 分批全部成功，最终云端完整 |
| 8 | 新设备登录，本地是默认设置 | 采纳云端设置，不用默认值覆盖 |
| 9 | 删除后重新创建同一 id，`updatedAt` 更大 | 课程保留，不被旧墓碑压掉 |
| 10 | 墓碑超过 180 天 | 云端活文档与墓碑都回收，本地墓碑清理 |
| 11 | 两设备同时编辑同一课程 | 时间戳大的一方保留（并列时以先拉取的一方为准，不崩） |
| 12 | 中途网络失败 | 下次同步可完整重试；不出现只写了一半的 id |

---

## 6. 课表分享（6 位提取码）

分享是登录用户之间的数据交换，和主课表同步相互独立。读取分享也要求已登录。

### 6.1 发布分享

生成 6 位提取码，写入 `shares/{code}`。码表刻意去掉了易混淆的 `0/O/1/I`：

```
23456789ABCDEFGHJKLMNPQRSTUVWXYZ
```

文档字段（≤12）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `creatorUid` | string | 必须是当前 uid（规则校验） |
| `ownerName` | string | 展示名，空时用“我的课表” |
| `type` | string | `COUPLE` / `FRIEND` |
| `colorHue` | int | 专属色相 0..359 |
| `payload` | string | 课表 JSON 字符串，见下 |
| `createdAt` | int | epoch 毫秒 |

写入时必须带前置条件，避免撞码覆盖别人的课表：

```json
{
  "writes": [
    {
      "update": {
        "name": "projects/classtable-4a7d0/databases/(default)/documents/shares/AB2CD3",
        "fields": {
          "creatorUid": { "stringValue": "UID" },
          "ownerName":  { "stringValue": "我的课表" },
          "type":       { "stringValue": "FRIEND" },
          "colorHue":   { "integerValue": "165" },
          "payload":    { "stringValue": "{\"v\":1,\"owner\":\"我的课表\",\"courses\":[]}" },
          "createdAt":  { "integerValue": "1762300000000" }
        }
      },
      "currentDocument": { "exists": false }
    }
  ]
}
```

撞码时返回 `ALREADY_EXISTS` / message 含 `already exists`，换一个码重试（Android 最多 5 次）。

### 6.2 读取分享

```http
GET .../documents/shares/{CODE}
Authorization: Bearer {idToken}
```

- 码必须已转大写，且匹配 `^[A-Z0-9]{6}$`。
- `404` → 提取码不存在或已失效。
- 字段缺失 / `payload` 为空 → 数据已失效。
- 规则允许任何已登录用户读取任意 `shares/{code}`，只有创建者能覆盖/删除。

### 6.3 `payload` 格式（v1）

`payload` 是**字符串**，内容为：

```json
{
  "v": 1,
  "owner": "小林",
  "type": "FRIEND",
  "hue": 165,
  "time": 1762300000000,
  "courses": [
    {
      "id": "uuid",
      "name": "大学物理",
      "loc": "理科楼 302",
      "tea": "李老师",
      "day": 2,
      "days": 2,
      "sp": 3,
      "ep": 4,
      "wt": "EVERY_WEEK",
      "ws": 1,
      "we": 16,
      "weeks": "",
      "csm": 610,
      "cem": 720,
      "note": ""
    }
  ]
}
```

| 键 | 含义 |
|---|---|
| `id` | 课程 id（接收端会加伴侣前缀，避免和本机课程冲突） |
| `name` / `loc` / `tea` | 课程名 / 地点 / 教师 |
| `day` | 主星期 1..7 |
| `days` | 星期位掩码 |
| `sp` / `ep` | 起止节次 |
| `wt` | 周类型 |
| `ws` / `we` | 周范围 |
| `weeks` | 精确周次 CSV |
| `csm` / `cem` | 自定义分钟（可选） |
| `note` | 备注 |

脱敏分享时课程名 `name` 会写成 `"忙碌(有课)"`，`loc` / `tea` / `note` 为空。

> 这是“好友课表”的独立通道：Android 把接收到的课程存在伴侣表里，**不会自动并入主课表**，
> 也不会通过主同步集合传播。Windows 端如需展示分享课表，请单独存储，不要写进
> `users/{uid}/courses`，否则会污染自己的主课表。

---

## 7. 其他可选接口

### 7.1 教务适配器 bundle

```http
GET {SITE_BASE_URL}/warehouse/bundle.json
```

无鉴权，体积可能数 MB，建议缓存并在“同步适配器”时手动更新。结构：

```json
{
  "schema": 1,
  "generatedAt": "2026-11-05T00:00:00.000Z",
  "schools": [
    { "id": "AEPU", "name": "安徽电气工程职业技术学院", "initial": "A", "resource_folder": "AEPU" }
  ],
  "adapters": [
    {
      "folder": "AEPU",
      "adapter_id": "AEPU_01",
      "adapter_name": "安徽电气工程职业技术学院教务系统",
      "category": "BACHELOR_AND_ASSOCIATE",
      "asset_js_path": "aepu.js",
      "import_url": "https://...",
      "maintainer": "Haooz",
      "description": "..."
    }
  ],
  "scripts": {
    "AEPU/aepu.js": "脚本源码"
  }
}
```

适配脚本依赖 Android WebView 的 JS Bridge（`AndroidBridge` / `AndroidBridgePromise`），
Windows 端若要复用需要自行提供等价的 WebView2 桥接，否则只能做“手动录入 / 表格导入”。

### 7.2 天气（可选）

```http
GET https://uapis.cn/api/v1/misc/weather?extended=true&forecast=true
Authorization: Bearer {WEATHER_API_KEY}   # 可选；无效时降级访客额度
```

响应字段（客户端实际读取）：

`weather`、`district` / `city`、`weather_icon`、`temperature`、`feels_like`、
`temp_max`、`temp_min`、`humidity`、`wind_direction`、`wind_power`、`aqi`、`aqi_category`、`uv`。

不传城市时按请求来源 IP 定位。Windows 端看到的是出口 IP 的城市，不是 GPS。

### 7.3 AI 助手（客户端直连，无中转）

服务端**没有** AI 代理接口。AI 调用由客户端直接访问用户配置的服务商：

| 供应商 | 默认地址 | 默认模型 |
|---|---|---|
| `GEMINI` | `https://generativelanguage.googleapis.com/v1beta` | `gemini-2.5-flash` |
| `OPENAI_COMPAT` | `https://api.openai.com/v1` | `gpt-4o` |

密钥只存本机，不参与同步。Windows 端如需实现相同的自然语言助手，需要自行复刻
`AiFunctions` / `AiPlanner` 的工具调用与“先预览、再落库”流程。

---

## 8. Android 专用 / Windows 不要调用的接口

| 接口 | 原因 |
|---|---|
| `POST /push` | 服务端发 FCM data 消息给 Android 设备，需要 `X-Push-Key` |
| `GET /version` | 返回 Android APK 的版本信息与架构包地址 |
| `GET /apk` | 流式下载 Android APK |
| `users/{uid}/devices/*` | Android FCM Installation ID |
| Firestore `:runQuery` / `:listen` / `:batchWrite` | 反代未放行 |
| Analytics / Crashlytics / Performance / Remote Config | Android 官方 SDK 能力，无跨端协议 |

Windows 端如需自动更新，请接入自己的发布渠道（GitHub Releases 的 Windows 资产或独立更新服务），
不要复用 `/version`。

---

## 9. C# 参考实现（.NET 8）

### 9.1 登录与刷新

```csharp
using System.Net.Http.Json;
using System.Text.Json;

public sealed record AuthSession(
    string Uid, string? Email, string IdToken, string RefreshToken, long ExpiresAt);

public sealed class AuthApi
{
    private readonly HttpClient _http = new();
    private readonly string _base;
    private readonly string _apiKey;

    public AuthApi(string proxyBase, string apiKey)
    {
        _base = proxyBase.TrimEnd('/');
        _apiKey = apiKey;
        _http.Timeout = TimeSpan.FromSeconds(20);
    }

    public async Task<AuthSession> SignInAsync(string email, string password, CancellationToken ct)
    {
        var url = $"{_base}/auth/v1/accounts:signInWithPassword?key={_apiKey}";
        var body = new { email, password, returnSecureToken = true };
        using var resp = await _http.PostAsJsonAsync(url, body, ct);
        var text = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode) throw new AuthException(ExtractCode(text));

        using var doc = JsonDocument.Parse(text);
        var root = doc.RootElement;
        var expiresIn = long.Parse(root.GetProperty("expiresIn").GetString() ?? "3600");
        return new AuthSession(
            root.GetProperty("localId").GetString()!,
            root.TryGetProperty("email", out var e) ? e.GetString() : null,
            root.GetProperty("idToken").GetString()!,
            root.GetProperty("refreshToken").GetString()!,
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + expiresIn * 1000);
    }

    public async Task<AuthSession> RefreshAsync(AuthSession s, CancellationToken ct)
    {
        var url = $"{_base}/auth/v1/token?key={_apiKey}";
        var body = new { grant_type = "refresh_token", refresh_token = s.RefreshToken };
        using var resp = await _http.PostAsJsonAsync(url, body, ct);
        var text = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode) throw new AuthException(ExtractCode(text));

        using var doc = JsonDocument.Parse(text);
        var root = doc.RootElement;
        var expiresIn = long.Parse(root.GetProperty("expires_in").GetString() ?? "3600");
        return s with
        {
            IdToken = root.GetProperty("id_token").GetString()!,
            RefreshToken = root.TryGetProperty("refresh_token", out var rt)
                ? rt.GetString()! : s.RefreshToken,
            ExpiresAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + expiresIn * 1000,
        };
    }

    private static string ExtractCode(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            return doc.RootElement.GetProperty("error").GetProperty("message").GetString() ?? "未知错误";
        }
        catch { return "未知错误"; }
    }
}

public sealed class AuthException(string code) : Exception(code);
```

### 9.2 Firestore 值编码与 commit

```csharp
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;

internal static class FirestoreValues
{
    public static JsonObject Str(string v) => new() { ["stringValue"] = v };
    public static JsonObject Int(long v) => new() { ["integerValue"] = v.ToString() };
    public static JsonObject Bool(bool v) => new() { ["booleanValue"] = v };
}

public sealed class FirestoreApi
{
    private readonly HttpClient _http = new();
    private readonly string _proxy;       // https://.../.netlify/functions/proxy
    private readonly string _projectId;   // classtable-4a7d0

    public FirestoreApi(string proxy, string projectId)
    {
        _proxy = proxy.TrimEnd('/');
        _projectId = projectId;
        _http.Timeout = TimeSpan.FromSeconds(30);
    }

    private string Documents =>
        $"{_proxy}/firestore/v1/projects/{_projectId}/databases/(default)/documents";

    private string DocName(string path) =>
        $"projects/{_projectId}/databases/(default)/documents/{path}";

    private HttpRequestMessage Request(HttpMethod method, string url, string token, JsonNode? body)
    {
        var req = new HttpRequestMessage(method, url);
        req.Headers.Authorization = new("Bearer", token);
        if (body != null) req.Content = JsonContent.Create(body);
        return req;
    }

    /// <summary>拉取整个集合，自动翻页。</summary>
    public async Task<List<JsonObject>> ListAsync(string collectionPath, string token, CancellationToken ct)
    {
        var result = new List<JsonObject>();
        string? pageToken = null;

        do
        {
            var url = $"{Documents}/{collectionPath}?pageSize=300";
            if (!string.IsNullOrEmpty(pageToken))
                url += $"&pageToken={Uri.EscapeDataString(pageToken)}";

            using var req = Request(HttpMethod.Get, url, token, null);
            using var resp = await _http.SendAsync(req, ct);
            var text = await resp.Content.ReadAsStringAsync(ct);
            if (!resp.IsSuccessStatusCode) throw new FirestoreException(text);

            using var doc = JsonDocument.Parse(text);
            if (doc.RootElement.TryGetProperty("documents", out var docs))
            {
                foreach (var d in docs.EnumerateArray())
                {
                    result.Add(new JsonObject
                    {
                        ["id"] = d.GetProperty("name").GetString()!.Split('/').Last(),
                        ["fields"] = JsonNode.Parse(d.GetProperty("fields").GetRawText())!,
                    });
                }
            }
            pageToken = doc.RootElement.TryGetProperty("nextPageToken", out var next)
                ? next.GetString() : null;
        } while (!string.IsNullOrEmpty(pageToken));

        return result;
    }

    /// <summary>一次 commit；调用方保证 writes.Count &lt;= 400。</summary>
    public async Task CommitAsync(string token, JsonArray writes, CancellationToken ct)
    {
        var url = $"{Documents}:commit";
        var body = new JsonObject { ["writes"] = writes };
        using var req = Request(HttpMethod.Post, url, token, body);
        using var resp = await _http.SendAsync(req, ct);
        var text = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode) throw new FirestoreException(text);
    }

    public JsonObject CourseUpdate(string uid, string courseId, JsonObject fields) => new()
    {
        ["update"] = new JsonObject
        {
            ["name"] = DocName($"users/{uid}/courses/{courseId}"),
            ["fields"] = fields,
        },
    };

    public JsonObject DeleteWrite(string uid, string collection, string id) => new()
    {
        ["delete"] = DocName($"users/{uid}/{collection}/{id}"),
    };
}
```

> 注意：`JsonObject` 里不要再包装成字符串；Firestore 要求 `integerValue` 的值本身是字符串，
> 但 REST 请求体仍是 JSON 对象。示例中的 `Int()` 已处理。

```csharp
public sealed class FirestoreException(string body) : Exception(body);
```

### 9.3 设置写入必须写全 13 个字段

```csharp
JsonObject BuildSettingsFields(AppSettings s) => new()
{
    ["themeMode"] = FirestoreValues.Str(s.ThemeMode),
    ["accentHex"] = FirestoreValues.Str(s.AccentHex),
    ["currentWeek"] = FirestoreValues.Int(s.CurrentWeek),
    ["periodTimes"] = FirestoreValues.Str(s.PeriodTimes),
    ["semesterStartDay"] = FirestoreValues.Int(s.SemesterStartDay),   // epochDay
    ["semesterWeekCount"] = FirestoreValues.Int(s.SemesterWeekCount),
    ["aiProvider"] = FirestoreValues.Str(s.AiProvider),
    ["aiBaseUrl"] = FirestoreValues.Str(s.AiBaseUrl),
    ["aiModel"] = FirestoreValues.Str(s.AiModel),
    ["notificationsEnabled"] = FirestoreValues.Bool(s.NotificationsEnabled),
    ["notifyLeadMinutes"] = FirestoreValues.Int(s.NotifyLeadMinutes),
    ["scheduleAdjustments"] = FirestoreValues.Str(s.ScheduleAdjustmentsJson),
    ["updatedAt"] = FirestoreValues.Int(s.UpdatedAt),
};
```

---

## 10. 排错清单

| 现象 | 优先检查 |
|---|---|
| 登录返回 `403 auth endpoint not allowed` | 路径拼错，必须是 `/auth/v1/accounts:...` |
| 同步返回 `403 firestore path not allowed` | 只能访问 `users/{uid}/...`、`shares/{6 位码}`、`:commit`、`:batchGet` |
| `PERMISSION_DENIED` | token 与 uid 是否匹配；是否在路径里用了别人的 uid |
| 新设备课表不完整 | 是否处理了 `nextPageToken` |
| 课程反复复活 | 删除时是否只删活文档、没写墓碑；墓碑 `updatedAt` 是否够新 |
| 云端课程删不掉 | 是否在 `:commit` 里同时下发了活文档 `delete` |
| 设置被重置成默认值 | 设置文档是否漏写字段（整文档替换） |
| 合并后时间不对 | `updatedAt` 是否误用了秒 / ISO 字符串 |
| 分享码生成总冲突 | 是否漏了 `currentDocument.exists=false`；码表是否包含易混淆字符 |
| 429 / 请求量过大 | 是否每次输入都同步；应做防抖与批量 commit |

---

## 11. 版本与兼容

- 本文对应 App 3.14.0（versionCode 100）的线上协议。
- 字段只增不改：新增字段时 Android 会忽略未知字段，Windows 读取时应保留未知字段并原样写回。
- 未知枚举值一律回退到安全默认：`EVERY_WEEK`、`TODO`、`NONE`、`SYSTEM`。
- 分享 payload 有显式版本号 `v: 1`；若未来升级，先判断 `v` 再解析。
- 不要重命名已有字段；不要改变 `updatedAt`、`semesterStartDay`、`periodTimes` 的单位。
- 反代地址与项目 ID 可能随部署变化，Windows 端不要把地址硬编码到无法更新的位置。

---

## 相关文档

- [backend-setup.md](backend-setup.md) — Firebase、Netlify 反代、App Check、推送、更新接口
- [architecture.md](architecture.md) — 整体架构与数据流
- [import-system.md](import-system.md) — 教务适配器数据流与脚本契约
- [yuketang-api.md](yuketang-api.md) — 雨课堂会话与公告接口（Android 实现参考）
