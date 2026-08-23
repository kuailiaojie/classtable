# 自建后端设计:Cloudflare Workers + D1

> 状态:方案设计(待确认后实施)
> 目标:替换 Firebase Auth/Firestore,彻底摆脱大陆访问 Google 的网络问题。

## 1. 架构

```
App(Android)
  ├─ 本地 Room 数据库(离线优先,真相在本地)
  ├─ AuthRepository ──HTTPS──> CF Workers API(自定义域名) ──> D1
  └─ SyncRepository ──HTTPS──> CF Workers API(自定义域名) ──> D1
```

- 全量同步:单用户课程数据 < 100KB,推拉全量最简单可靠。
- 认证:自建 Email/密码 + 自签 JWT(HS256),密钥存 Worker 环境变量。
- 无 GMS 设备(华为)友好:FCM/Analytics 本来无效,一并去掉,零损失。

## 2. D1 表结构(schema.sql)

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
  "weekType": "EVERY_WEEK", "weekStart": 1, "weekEnd": 16,
  "semesterId": "default", "updatedAt": 1700000000000,
  "customStartMinute": null, "customEndMinute": null, "note": ""
}
```

## 3. API 设计

Base URL:`https://<你的域名>/api`(域名托管到 Cloudflare,配合自选 IP)

| 方法 | 路径 | 请求体 | 成功响应 | 错误 |
|---|---|---|---|---|
| POST | /api/register | `{email, password}` | `{token}` | 409 邮箱已存在 / 400 参数错 / 400 密码过短 |
| POST | /api/login | `{email, password}` | `{token}` | 401 账号或密码错误 |
| POST | /api/reset | `{email}` | `{ok:true}`(始终成功,防枚举) | 400 |
| GET | /api/courses | —(Bearer token) | `{courses:[{id,data,updatedAt}], deleted:[{id,updatedAt}]}` | 401 |
| PUT | /api/sync | `{courses:[...], deleted:[...]}`(Bearer token) | 合并后的全量 `{courses, deleted}` | 401 |

认证:`Authorization: Bearer <JWT>`;JWT payload `{uid, exp}`,有效期 30 天,HS256 签名,密钥 = 环境变量 `JWT_SECRET`。

## 4. 同步协议(updatedAt 后者胜 + 墓碑)

客户端(沿用现有 SyncRepository.merge 思路,搬到 HTTP):

1. 登录成功 → `PUT /api/sync`,上传本地全部课程 + 本地墓碑
2. 服务端合并:每个 id 取 `updated_at` 较大者;`deleted.updated_at >= course.updated_at` 的课程视为已删
3. 服务端返回合并后全量(含全量墓碑)
4. 客户端覆盖写 Room(现有 syncNow 第 3 步逻辑),并清理多余行
5. 幂等可重试;单用户单设备为主,最后写者胜足够

## 5. 安全

- 密码:WebCrypto `PBKDF2-HMAC-SHA256`,10 万次迭代 + 随机盐,格式 `pbkdf2:100000:<salt_hex>:<hash_hex>`,不存明文
- JWT:HS256,`JWT_SECRET` 用 `openssl rand -hex 32` 生成,只存 Worker 环境变量,不进代码库
- 每用户只能读写自己 `uid` 的数据(服务端强制从 token 取 uid,不信任客户端传的 uid)

## 6. App 端改造清单

| 文件 | 改动 |
|---|---|
| `data/AuthRepository.kt` | 重写:HttpURLConnection 调 /api/register/login/reset;token+email 存 DataStore;currentUser Flow 基于本地登录态 |
| `data/SyncRepository.kt` | 重写:GET /api/courses + PUT /api/sync;Course ↔ JSON(org.json,Android 内置,零新依赖) |
| `data/SettingsDataStore.kt` | 加 `auth_token` / `auth_email` key |
| `data/RemoteCourse.kt` | 删除(Firestore DTO 不再需要,改用 Course JSON) |
| `data/FcmTokens.kt` / `data/FcmMessagingService.kt` / `data/Analytics.kt` | 删除(无 GMS,原本无效) |
| `data/CourseRepository.kt` / `ClasstableApp.kt` | 去掉 FCM/分析调用;保留同步重试 + 网络恢复补同步 |
| `app/build.gradle.kts` / `libs.versions.toml` | 去 firebase-auth/firestore/analytics/messaging + google-services 插件;不加新依赖(用 HttpURLConnection) |
| `app/google-services.json` | 不再需要(可留着不碍事,也可删) |
| `ui/account/AccountScreen.kt` 等 UI | **不动**(Repository 已隔离 Firebase 细节) |

网络层:HttpURLConnection + `Dispatchers.IO`,15 秒超时,沿用现有重试逻辑(认证 3 次 / 同步 3 次 / 网络恢复补同步)。

## 7. 部署步骤(用户操作,有网络)

1. 域名 NS 转入 Cloudflare(免费;或用 CNAME 方案)
2. CF 控制台:创建 Worker(`classtable-api`)、创建 D1 数据库(`classtable`),Worker 绑定 D1
3. 设置环境变量 `JWT_SECRET`(`openssl rand -hex 32` 生成)
4. 建表:执行 schema.sql(控制台 D1 → 控制台查询,或 `wrangler d1 execute classtable --file schema.sql`)
5. 部署 Worker 代码(`wrangler deploy` 或控制台粘贴)
6. Worker 设置 → 触发器 → 自定义域:绑定你的域名子域(如 `api.你的域名.com`)
7. 可选优化:自选 IP(DNS 解析到 Cloudflare 香港/日本优选 IP),大陆访问更稳
8. 手机 App 端:设置里加「服务器地址」输入 `https://api.你的域名.com`(默认内置,可改)

## 8. 工作量与交付

- Workers 端:约 350 行 TypeScript(认证 + 同步 + 建表)
- App 端:重写 2 个 Repository(~250 行)+ 删 Firebase 相关(~150 行)+ DTO(~100 行)
- 交付物:Worker 代码 + schema.sql + App 端改动 + 部署文档
- 注意:我这边沙箱封锁外网,无法连 CF 测试,Worker 代码需要你部署后联调,报错贴我

## 9. 风险与回退

| 风险 | 应对 |
|---|---|
| CF 自定义域名大陆可达性波动 | 自选 IP + 重试机制;可达性整体远好于 Google |
| 自建认证安全性 | PBKDF2 + JWT 标准实现;密钥不进代码库 |
| 现有 Firebase 数据 | 刚注册基本为空,不迁移 |
| 改造出问题 | Repository 层隔离,随时可 git 回退到 Firebase 版 |
