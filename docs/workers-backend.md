# 自建后端设计方案:Cloudflare Workers + D1

> **状态**:方案设计(待评估确认后实施)
> **目标**:以 Cloudflare Workers + D1 替换 Firebase Auth / Firestore,彻底规避大陆访问 Google 的网络问题。

## 1. 背景与动机

当前应用经 Netlify 反代中间层访问 Firebase,虽已解决大陆可达性问题,但仍依赖第三方服务。本方案评估将认证与同步后端整体迁移至 Cloudflare Workers + D1 的可行性:

- 数据规模小:单用户课程数据 < 100KB,全量推拉最简单可靠。
- 认证自建:Email / 密码 + 自签 JWT(HS256),密钥存于 Worker 环境变量。
- 无 GMS 设备友好:华为等无 GMS 设备上 FCM / Analytics 本即无效,一并移除,零功能损失。

## 2. 架构

```
App(Android)
  ├─ 本地 Room 数据库(离线优先,本地为数据真相)
  ├─ AuthRepository ──HTTPS──> CF Workers API(自定义域名) ──> D1
  └─ SyncRepository ──HTTPS──> CF Workers API(自定义域名) ──> D1
```

## 3. D1 表结构(schema.sql)

```sql
CREATE TABLE IF NOT EXISTS users (
  id         TEXT PRIMARY KEY,          -- 随机 uid
  email      TEXT UNIQUE NOT NULL,
  pass_hash  TEXT NOT NULL,             -- 格式: pbkdf2:iterations:salt_hex:hash_hex
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS courses (
  uid        TEXT NOT NULL,
  id         TEXT NOT NULL,             -- 课程 UUID
  data       TEXT NOT NULL,             -- 课程完整 JSON
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (uid, id)
);
CREATE INDEX IF NOT EXISTS idx_courses_uid ON courses(uid);

CREATE TABLE IF NOT EXISTS deleted (
  uid        TEXT NOT NULL,
  id         TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (uid, id)
);
CREATE INDEX IF NOT EXISTS idx_deleted_uid ON deleted(uid);
```

课程 JSON(与 App 端 Course 字段一一对应):

```json
{
  "id": "...", "name": "高等数学(上)", "teacher": "张老师", "location": "教1-201",
  "weekday": 1, "weekdays": 3, "startPeriod": 1, "endPeriod": 2,
  "weekType": "EVERY_WEEK", "weekStart": 1, "weekEnd": 16, "weeks": "",
  "semesterId": "default", "updatedAt": 1700000000000,
  "customStartMinute": null, "customEndMinute": null, "note": ""
}
```

`weeks` 是 CSV 字符串(如 `"4,6,8"`),只在 `weekType = "CUSTOM"` 时表示精确周次;空字符串 = 沿用 `weekStart..weekEnd`。
单/双周(`ODD_WEEK` / `EVEN_WEEK`)的范围由 `weekStart..weekEnd` 限定,奇偶按学期绝对周次算。

## 4. API 设计

Base URL:`https://<你的域名>/api`(域名托管到 Cloudflare,可配合自选 IP)。

| 方法 | 路径 | 请求体 | 成功响应 | 错误 |
|---|---|---|---|---|
| POST | /api/register | `{email, password}` | `{token}` | 409 邮箱已存在 / 400 参数错 / 400 密码过短 |
| POST | /api/login | `{email, password}` | `{token}` | 401 账号或密码错误 |
| POST | /api/reset | `{email}` | `{ok:true}`(始终成功,防枚举) | 400 |
| GET | /api/courses | —(Bearer token) | `{courses:[{id,data,updatedAt}], deleted:[{id,updatedAt}]}` | 401 |
| PUT | /api/sync | `{courses:[...], deleted:[...]}`(Bearer token) | 合并后的全量 `{courses, deleted}` | 401 |

认证:`Authorization: Bearer <JWT>`;JWT payload `{uid, exp}`,有效期 30 天,HS256 签名,密钥 = 环境变量 `JWT_SECRET`。

## 5. 同步协议(updatedAt 后者胜 + 墓碑)

客户端沿用现有 `SyncRepository.merge` 思路,迁移到 HTTP:

1. 登录成功 → `PUT /api/sync`,上传本地全部课程与本地墓碑。
2. 服务端合并:每个 id 取 `updated_at` 较大者;`deleted.updated_at >= course.updated_at` 的课程视为已删除。
3. 服务端返回合并后全量(含全量墓碑)。
4. 客户端覆盖写 Room(沿用现有 `syncNow` 逻辑),并清理多余行。
5. 协议幂等可重试;以单用户单设备为主,最后写者胜已足够。

## 6. 安全设计

- **密码存储**:WebCrypto `PBKDF2-HMAC-SHA256`,10 万次迭代 + 随机盐,格式 `pbkdf2:100000:<salt_hex>:<hash_hex>`,不存明文。
- **JWT**:HS256,`JWT_SECRET` 以 `openssl rand -hex 32` 生成,仅存于 Worker 环境变量,不进入代码库。
- **数据隔离**:服务端强制从 token 取 uid,不信任客户端传入的 uid,每用户只能读写自己的数据。

## 7. App 端改造清单

| 文件 | 改动 |
|---|---|
| `data/AuthRepository.kt` | 重写:HttpURLConnection 调 /api/register/login/reset;token + email 存 DataStore;currentUser Flow 基于本地登录态 |
| `data/SyncRepository.kt` | 重写:GET /api/courses + PUT /api/sync;Course ↔ JSON(org.json,Android 内置,零新依赖) |
| `data/SettingsDataStore.kt` | 新增 `auth_token` / `auth_email` key |
| `data/RemoteCourse.kt` | 删除(Firestore DTO 不再需要,改用 Course JSON) |
| `data/FcmTokens.kt` / `data/FcmMessagingService.kt` / `data/Analytics.kt` | 删除(无 GMS,原本无效) |
| `data/CourseRepository.kt` / `ClasstableApp.kt` | 移除 FCM / 分析调用;保留同步重试 + 网络恢复补同步 |
| `app/build.gradle.kts` / `libs.versions.toml` | 移除 firebase-auth / firestore / analytics / messaging 及 google-services 插件;不新增依赖(用 HttpURLConnection) |
| `app/google-services.json` | 不再需要(可保留或删除) |
| `ui/account/AccountScreen.kt` 等 UI | **不动**(Repository 已隔离 Firebase 细节) |

网络层:HttpURLConnection + `Dispatchers.IO`,15 秒超时,沿用现有重试逻辑(认证 3 次 / 同步 3 次 / 网络恢复补同步)。

## 8. 部署步骤

1. 将域名 NS 转入 Cloudflare(免费;或采用 CNAME 方案)。
2. CF 控制台:创建 Worker(`classtable-api`)与 D1 数据库(`classtable`),Worker 绑定 D1。
3. 设置环境变量 `JWT_SECRET`(以 `openssl rand -hex 32` 生成)。
4. 建表:执行 schema.sql(控制台 D1 查询,或 `wrangler d1 execute classtable --file schema.sql`)。
5. 部署 Worker 代码(`wrangler deploy` 或控制台粘贴)。
6. Worker 设置 → 触发器 → 自定义域:绑定自有域名子域(如 `api.你的域名.com`)。
7. 可选优化:自选 IP(DNS 解析到 Cloudflare 香港 / 日本优选 IP),提升大陆访问稳定性。
8. App 端:设置中新增「服务器地址」输入项,默认内置 `https://api.你的域名.com`,可修改。

## 9. 工作量与交付物

| 端 | 工作量 |
|---|---|
| Workers 端 | 约 350 行 TypeScript(认证 + 同步 + 建表) |
| App 端 | 重写 2 个 Repository(~250 行)+ 移除 Firebase 相关(~150 行)+ 删除 DTO(~100 行) |

交付物:Worker 代码 + schema.sql + App 端改动 + 部署文档。

> 注意:本机开发环境沙箱封锁外网,无法连接 Cloudflare 实测;Worker 代码需部署后联调,发现的问题将按部署环境反馈处理。

## 10. 风险与回退

| 风险 | 应对 |
|---|---|
| CF 自定义域名大陆可达性波动 | 自选 IP + 客户端重试机制;整体可达性远优于 Google 域名 |
| 自建认证安全性 | PBKDF2 + JWT 标准实现;密钥不进代码库 |
| 现有 Firebase 数据迁移 | 账号体系早期、数据量小,不迁移 |
| 改造回归 | Repository 层已隔离 Firebase 细节,可随时通过 git 回退至 Firebase 版本 |
