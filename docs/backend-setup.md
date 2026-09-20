# 后端部署与配置

> 面向开发者的说明。产品介绍见 [README](../README.md)。
> 应用默认以访客本地模式运行,不部署后端也能使用全部课表功能;下文是账号同步、推送与在线更新的接入方式。

## Firebase 项目

- 项目 ID:`classtable-4a7d0`;Android 包名 `com.kxin.classtable`(`google-services.json` 已就位)。
- 安全规则见 `firestore.rules`,`firebase.json` 已配置。

```bash
firebase login --no-localhost
firebase use classtable-4a7d0
firebase deploy --only auth          # 启用 Email/Password
# 在 Console 创建 Firestore 数据库(建议 Standard,区域 asia-east1)
firebase deploy --only firestore:rules
```

## Netlify 反代

Android 的 Firebase 官方 SDK 硬编码 Google 域名,大陆无法直连,因此认证与 Firestore 请求统一经自建 Netlify Function 转发(`netlify/functions/proxy.mjs`)。

1. Netlify 控制台 → Add new site → 连接本仓库(或单独部署 `netlify/functions`)。
2. 部署完成后得到站点地址 `https://<site>.netlify.app`(建议绑定自有域名,`netlify.app` 在大陆可达性一般)。
3. 把站点地址写入 `app/build.gradle.kts`(均经 BuildConfig 注入,换站点只改这两处):
   - `FIREBASE_PROXY_URL` — 反代地址(`https://<site>/.netlify/functions/proxy`);
   - `SITE_BASE_URL` — 站点根(`https://<site>`,用于适配器 bundle 与更新接口)。
4. 重新构建并安装 App。

`netlify.toml` 已配置 `[build] command = "node tools/build-netlify.mjs"` 与 `publish = "netlify/static"`,部署时会自动生成 `<site>/warehouse/bundle.json`(适配器同步源)。

免费额度为 12.5 万次请求 / 月,登录 + 同步场景绰有余裕。

## 在线更新接口

「设置 → 检查更新」由反代提供两个接口,客户端不直连 GitHub:

| 路径 | 作用 |
|---|---|
| `GET /version` | 服务端代查 GitHub `releases/latest`,返回 `{versionName, notes, releaseUrl, apkUrl, publishedAt}` |
| `GET /apk` | 流式代理最新 release 的 `app-release.apk`(绝不会发 debug 包) |

如需提升 GitHub API 限流额度,可在 Netlify 环境变量设置 `GITHUB_TOKEN`(可选)。

客户端的自动检查受「设置 → 自动检查更新」开关与 24 小时节流约束,只在联网时进行,发现新版本只发通知提示,不自动下载。

## 推送(Live Updates)

服务端通过 `POST <site>/.netlify/functions/proxy/push` 向设备发送 FCM data 消息,客户端按 `messageType` 分发:

| messageType | 用途 | 客户端行为 |
|---|---|---|
| `course_reminder` | 上课提醒(服务端补充通道) | 渲染提醒;通知 id 与本地闹钟相同(`课程 + 日期` 的 hash)→ 双通道自动去重 |
| `course_changed` | 调课 / 停课 / 换教室 | 提示 + 自动云同步 + 重排提醒闹钟 |
| `marketing` | 活动 / 宣传 | 通用通知 |

一次性配置:

1. Firebase Console → 项目设置 → 服务账号 → 生成新私钥并下载 JSON。
2. Netlify → Site settings → Environment variables 添加 `SERVICE_ACCOUNT`(JSON 完整内容)与 `PUSH_API_KEY`(自定义管理密钥)。两者均为机密信息,仅存放在 Netlify 环境变量,不得提交到代码库。
3. 重新部署。

调用示例:

```bash
curl -X POST "https://<site>/.netlify/functions/proxy/push" \
  -H "X-Push-Key: <PUSH_API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "uid": "<用户uid>",
    "messageType": "course_changed",
    "title": "课表已更新",
    "body": "周一 10:00 高数换教室到 A203",
    "data": { "courseId": "<courseId>" }
  }'
```

> 如需服务端定时扫描课表并自动推送上课提醒,可扩展 Cloud Functions 定时触发器(Pub/Sub schedule),属于后续增强项。

## 相关文档

- 签名配置:[release-signing.md](release-signing.md)
- 整体架构与数据模型:[architecture.md](architecture.md)
- 教务导入系统:[import-system.md](import-system.md)
