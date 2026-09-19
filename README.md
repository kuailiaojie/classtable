# Classtable · Yohaku 极简课程表

> 面向中国在校大学生的 Android 极简效率型课程表应用。以 **Yohaku 设计哲学**为视觉核心——一抹 accent、三档中性、余者尽留为白——刻意避开多彩贴纸与液态玻璃等装饰性风格,回归纸质课表的秩序与留白。

## 目录

- [项目简介](#项目简介)
- [设计理念](#设计理念)
- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [架构概览](#架构概览)
- [教务导入系统](#教务导入系统)
- [数据模型与同步](#数据模型与同步)
- [目录结构](#目录结构)
- [部署与配置](#部署与配置)
- [已知限制](#已知限制)
- [路线图](#路线图)
- [许可](#许可)

---

## 项目简介

Classtable 是一款为国内大学生设计的课程表应用,覆盖「课表查看 → 课程导入 → 上课提醒 → 多端同步」的完整闭环:

- **课表查看**:周视图与日视图两种形态,单日逐节网格、课程卡绝对定位,空堂留白。
- **课程导入**:支持教务系统一键导入(146 所学校 / 156 个适配器)、手动表格粘贴、Excel 文件导入与 AI 图片识别。
- **上课提醒**:本地精确闹钟为主、FCM 实时推送为辅的双通道提醒,并对国产 ROM 后台清理做了针对性防护。
- **账号同步**:Firebase Email/Password 认证 + Firestore 云同步,未登录时以访客本地模式使用,登录后自动合并。

后端面向中国大陆网络环境做了特殊设计:认证与同步请求全部经由自建 Netlify 反代中间层转发,客户端不再直连被墙的 Google 域名。

## 设计理念

应用遵循 **Yohaku(余白)设计哲学**:

- **一抹 accent**:全应用仅使用单一强调色(默认梅色 `#C56473`,另有 5 色可选),用于当前课程、选中态等少量关键元素。
- **三档中性**:浅色 / 深色两套中性暖纸面色板,层次靠明度而非颜色区分。
- **余者尽留为白**:空堂不渲染任何装饰,以留白本身表达「无课」。
- **衬线纸感**:内置思源宋体 Medium 与 JetBrains Mono Regular,营造纸质课表的翻阅感。

该定位面向「效率工具」人群,对标同类效率应用的克制美学,而非追求视觉热闹。

## 功能特性

### 课表核心

- **周视图**:一次一屏展示一天,左右滑动切换周一到周日;顶部星期胶囊可点击直达;「今天」按钮跳转真实当前周。
- **逐节网格**:每天按节次纵向排列,左侧标注「第 N 节 + 起止时间」,每节固定等高;课程卡绝对定位横穿——普通课横穿其节次区间,自定义时间课程横穿其起止时间覆盖的全部节行。
- **日视图**:纯列表形态,卡片间距即空堂表达;每 30 秒动态刷新「正在上课 · 剩余 N 分钟 / 距下一节 N 分钟」。
- **课程管理与详情**:底部导航「课程」入口,详情页展示本学期每次课的排期日期(基于学期起始日推算),支持编辑与删除。
- **课程表单**:整页表单,星期可多选(一周多天上课)、节次/周次以 chip 选择、支持自定义周次、备注选填;时间支持「按节次」与「自定义时间」两种模式,后者适用于晚间讲座、临时加课等非作息场景。

### 提醒与可靠性

- **上课提醒通知**:每节课开始前触发通知,可配置提前量(准点 / 5 / 10 / 15 / 30 / 60 分钟);通知内容触发时动态计算(课程名、剩余分钟、开始时间、地点、教师),点击直达课程详情。
- **精确闹钟预排**:未来 14 天闹钟预先排定,开机、改时区自动重排;Android 12+ 未授予精确闹钟权限时自动降级。
- **国产 ROM 后台保护**:设置页「提醒可靠性」区块自动识别 ROM(小米 / OPPO / vivo / 华为 / 荣耀 / 魅族),一键引导开启通知权限、精确闹钟、电池白名单与自启动;WorkManager 每 12 小时自愈重排全部闹钟,FCM 消息到达时顺带重排,防止 ROM 清理导致提醒丢失。
- **首次启动权限引导**:首次启动弹出权限清单(通知 / 精确闹钟 / 电池白名单 / 自启动),可逐个引导开启或选择「稍后再说」;设置页可随时重新进入。

### 课程导入

- **教务系统导入**:接入开源教务适配仓库 shiguang_warehouse,覆盖正方、强智、青果、URP、超星等系统的 146 所学校 / 156 个适配器。选校后先阅读适配器说明(描述 / 作者 / 操作提示)再确认导入;WebView 登录(账号密码仅存于本机会话)后注入适配脚本,脚本识别到的**作息时间与开学日期**会在确认页列出,由你决定是否覆盖本地设置,课程按周次自动归类(每周 / 单周 / 双周 / 自定义)。
- **手动导入**:粘贴 CSV / TSV 表格数据,或直接导入 Excel 文件(.xlsx / .csv / .tsv),自动识别表头与 UTF-8 / GBK 编码;表格第 8、9 列可填开始 / 结束时间(HH:MM),生成自定义时间课程。
- **AI 图片导入**:多供应商——Google Gemini 或任意 OpenAI 兼容服务(DeepSeek、通义千问、Kimi、智谱 GLM 等),在设置页配置供应商、密钥、Base URL 与模型后,上传课表图片即可识别课程与作息并自动应用。
- **作息一键同步**:教务 / AI 图片 / 手动表格导入识别到作息时,会在确认页展示并等你确认后写入;手动导入可直接粘贴「08:00-08:50」格式行同步作息。

### 账号、同步与稳定性

- **账号**:Firebase Email/Password,支持密码重置邮件;未登录为访客本地模式。
- **同步**:Room 本地优先 + Firestore,`updatedAt` 后者胜,删除墓碑传播防止数据复活。
- **数据埋点与崩溃**:Firebase Analytics(课程增删 / 导入 / 登录 / 提醒等事件)+ Crashlytics(崩溃自动上报,携带版本自定义 key),崩溃报告走官方通道,大陆无网络时本地缓存、恢复后补传。
- **实时推送(Live Updates)**:服务端经反代 `/push` 接口定向推送三类消息——`course_reminder` 上课提醒(与本地闹钟同一通知 id,双通道自动去重)、`course_changed` 课表变更(提示 + 自动同步 + 重排闹钟)、`marketing` 营销活动。
- **桌面小组件**:Glance 实现的 1×1「下节课」与 4×2「今日课表」(当前课 accent 高亮,跟随动态作息)。
- **边缘到边缘**:纸面底色铺满状态栏与导航栏区域,栏内图标深浅随主题切换。

### 设置

- 主题:浅色 / 深色 / 跟随系统。
- 强调色:5 色可选。
- 作息时间子页:每节独立设置开始 + 结束时间,可自由增删节次,不限于 12 节;支持按「开始时间 + 单节时长 + 课间 + 节数」自动生成,并在保存前校验节次顺序与时间重叠。
- 学期周次子页:日期选择器设置学期起始日与总周数。
- AI 密钥:多供应商配置。
- 适配器同步:从自建站点拉取最新学校索引与教务适配脚本,落地后导入页优先用云端数据,失败自动回退内置。
- 检查更新:检查新版本、应用内下载 APK 并拉起系统安装器;启动时按 24 小时节流静默检查。

## 技术栈

| 分类 | 选型 |
|---|---|
| 语言 | Kotlin 2.1.0 |
| UI | Jetpack Compose(BOM 2024.12.01),自建 Yohaku 设计系统,不使用 Material3 默认外观 |
| 构建 | AGP 8.7.3 / Gradle 8.11.1 / KSP |
| 架构 | MVVM + Hilt 2.54(依赖注入) |
| 本地存储 | Room 2.7.0、DataStore 1.1.1、SecurityCrypto 1.1.0 |
| 后台任务 | WorkManager 2.10.0 |
| 小组件 | Glance 1.1.1 |
| 后端 | Firebase(Auth / Firestore / Analytics / Crashlytics / FCM,BOM 33.7.0)经 Netlify Functions 反代 |
| 网络 | HttpURLConnection + REST(认证与同步不依赖 Firebase SDK) |

## 环境要求

| 项 | 要求 |
|---|---|
| IDE | Android Studio(含 JDK 17+,SDK 可自动下载) |
| SDK | compileSdk 35 / targetSdk 35 / minSdk 26(Android 8.0+) |
| 网络 | 首次 Sync 需联网下载依赖 |

## 快速开始

```bash
# 1. 使用 Android Studio 打开仓库根目录(File > Open),等待首次 Sync 完成
# 2. 连接模拟器或真机,运行 app 模块
# 或使用命令行构建(仓库已提交 Gradle Wrapper):
./gradlew assembleDebug          # 调试版 APK(可直接安装)
./gradlew assembleRelease        # 发布版 APK(未配置签名时产出未签名 APK)
```

> Release 签名配置(GitHub Actions Secrets / 本地构建)见 [`docs/release-signing.md`](docs/release-signing.md)。

> Release 构建开启 R8 混淆 + 资源压缩,且只打 `arm64-v8a` / `armeabi-v7a`(x86 模拟器请用 debug 包);内置思源宋体已子集化到 GB2312 全字集(14.1MB → 3.3MB),需要重新生成时执行 `python tools/subset-font.py`。

> 应用默认以访客本地模式运行,无需任何后端配置即可体验完整课表功能。账号同步与推送需按下文[部署与配置](#部署与配置)完成后端接入。

## 架构概览

```
┌──────────────────────┐        ┌──────────────────────┐        ┌──────────────────┐
│   Android App        │ HTTPS  │  Netlify Functions    │ HTTPS  │  Firebase        │
│  ┌────────────────┐  │ ─────▶ │  proxy.mjs 反代中间层  │ ─────▶ │  Auth / Firestore│
│  │ Room 本地优先   │  │        │  /auth/*  /firestore/*│        │  (安全规则照常评估)│
│  │ (离线可用)      │  │        └──────────────────────┘        └──────────────────┘
│  └────────────────┘  │
│  Crashlytics/Analytics│ ─── 官方通道直连(不经反代)
│  FCM Messaging        │ ◀── 服务端 /push 定向推送
└──────────────────────┘
```

- **客户端**:本地优先。Room 持久化课程数据,未登录 = 访客本地模式;登录后 pull → 合并(updatedAt 后者胜)→ 应用墓碑 → push。
- **反代中间层**:Android 的 Firebase Auth/Firestore 官方 SDK 硬编码 Google 域名,大陆无法直连。本项目移除 `firebase-auth` / `firebase-firestore` SDK 依赖,改用 REST 实现,全部请求经自建 Netlify Function 转发(方法 / query / body / Authorization ID token 原样透传)。`firebase-analytics` / `firebase-messaging` / `firebase-crashlytics` 保留官方 SDK。同步由实时监听改为按需 pull / push(App 启动、登录、网络恢复时触发),对课程表场景无感知差异。
- **推送通道**:本地精确闹钟是准点提醒主力(离线可用),FCM 为实时增强通道。

## 教务导入系统

### 数据流

```
warehouse/                 # 上游仓库快照(源:保留 YAML,便于比对与重新生成)
  index/root_index.yaml
  resources/<SCHOOL>/{adapters.yaml, <script>.js}
        │  node tools/yaml2json.mjs   → 预编译为下方 JSON,App 运行时不解析 YAML
        ▼
assets/warehouse/
  index.json       # 146 所学校索引
  adapters.json    # 156 个适配器配置
  resources/<SCHOOL>/<script>.js   # 适配脚本(注入 WebView 执行)
```

### 适配脚本契约

脚本注入即自执行,通过 JS 桥与 App 交互:

- `AndroidBridge.showToast` / `AndroidBridge.notifyTaskCompletion`
- `AndroidBridgePromise.showAlert` / `showConfirmDialog` / `showPrompt` / `showSingleSelection` / `saveImportedCourses` / `savePresetTimeSlots` / `saveCourseConfig`(v2 别名 `shiguangBridge*`)

桥垫片会把每个方法的实参**补齐到固定个数**再追加 callbackId,以兼容各适配器 3 参 / 4 参的不同写法(形参错位会让 Promise 一直挂到 60s 超时);`showPrompt` 的校验函数名会在确认后于页面内执行,`showSingleSelection` 会预选 `defaultIndex`。

### 更新仓库

重新下载 shiguang_warehouse 到 `warehouse/` 后运行 `node tools/yaml2json.mjs` 重新生成 assets 索引,`node tools/build-netlify.mjs` 再把 assets 打包为 `netlify/static/warehouse/bundle.json`(Netlify 部署时自动执行)。App 端「设置 → 适配器同步」即可拉取该 bundle 覆盖内置数据。

## 数据模型与同步

| 层 | 存储 | 说明 |
|---|---|---|
| 本地 | Room:`courses` + `deleted_courses`(墓碑)+ `semesters` | 离线优先,真相在本地 |
| 本地 | DataStore `settings` | 主题 / accent / 当前周 / 作息 / 学期 |
| 远端 | Firestore:`users/{uid}/courses/{courseId}` + `users/{uid}/deleted/{courseId}` | 云同步 |

同步策略:本地优先;登录后 pull → 合并(`updatedAt` 后者胜)→ 应用墓碑 → push;删除以墓碑传播,防止已删课程在其它设备复活。

## 目录结构

```
app/src/main/java/com/kxin/classtable/
  design/      # Yohaku 设计系统(色板 / 字阶 / 间距 / 组件 / 内置字体)
  domain/      # Course / Semester / Schedule(节次↔时间换算、学期周推导)
  data/        # Room + DataStore + Firebase 同步 + import(WarehouseIndex / ImportParser)
  di/          # Hilt 依赖注入模块
  notify/      # 本地闹钟 / 提醒通知
  ui/          # 周视图 日视图 表单 导入(3 步 + JS 桥) 设置(+ 子页) 账号
  widget/      # Glance:1×1 下节课 + 4×2 今日课表
netlify/functions/proxy.mjs   # 后端反代(认证 / Firestore / 推送 / 版本 / APK)
netlify/static/               # 构建期生成:warehouse/bundle.json(适配器同步源,CDN 分发)
tools/yaml2json.mjs           # 教务仓库 YAML → assets JSON 预编译
tools/build-netlify.mjs       # 适配器 assets → Netlify 静态 bundle
.github/workflows/build-apk.yml   # GitHub Actions:自动构建并上传 APK
gradlew / gradlew.bat             # Gradle Wrapper 启动脚本
```

## 部署与配置

### Firebase 项目

- 项目 ID:`classtable-4a7d0`;Android 应用包名 `com.kxin.classtable`(google-services.json 已就位)。
- 安全规则见 `firestore.rules`,`firebase.json` 已配置。

```bash
firebase login --no-localhost
firebase use classtable-4a7d0
firebase deploy --only auth          # 启用 Email/Password
# 在 Console 创建 Firestore 数据库(建议 Standard,区域 asia-east1)
firebase deploy --only firestore:rules
```

### Netlify 反代部署

1. Netlify 控制台 → Add new site → 连接本仓库(或单独部署 `netlify/functions`)。
2. 部署完成后得到站点地址 `https://<site>.netlify.app`(建议绑定自有域名,`netlify.app` 域名在大陆可达性一般)。
3. 将站点地址写入 `app/build.gradle.kts`(均经 BuildConfig 注入,换站点只改这两处):
   - `FIREBASE_PROXY_URL` — 反代地址(`https://<site>/.netlify/functions/proxy`);
   - `SITE_BASE_URL` — 站点根(`https://<site>`,用于适配器 bundle 与更新接口)。
4. 重新构建并安装 App。

> 适配器同步依赖 Netlify 的静态发布目录:`netlify.toml` 已配置 `[build] command = "node tools/build-netlify.mjs"` 与 `publish = "netlify/static"`,部署时自动生成 `<site>/warehouse/bundle.json`。
> 「检查更新」由反代 `/version` 服务端代查 GitHub Release、`/apk` 流式代理安装包(客户端不直连 GitHub);如需提升 GitHub API 限流额度,可在 Netlify 环境变量设置 `GITHUB_TOKEN`(可选)。

免费额度为 12.5 万次请求 / 月,登录 + 同步场景绰有余裕。

### Live Updates 推送配置

服务端通过 `POST <site>/.netlify/functions/proxy/push` 向用户设备发送 FCM data 消息,客户端按 `messageType` 分发:

| messageType | 用途 | 客户端行为 |
|---|---|---|
| `course_reminder` | 上课提醒(服务端补充通道) | 渲染提醒;通知 id 与本地闹钟相同(`courseId.hashCode()`)→ 双通道自动去重 |
| `course_changed` | 调课 / 停课 / 换教室 | 提示 + 自动云同步 + 重排闹钟 |
| `marketing` | 活动 / 宣传 | 通用通知 |

一次性配置步骤:

1. Firebase Console → 项目设置 → 服务账号 → 生成新私钥并下载 JSON。
2. Netlify → Site settings → Environment variables 添加 `SERVICE_ACCOUNT`(JSON 完整内容)与 `PUSH_API_KEY`(自定义管理密钥)。两者均为机密信息,仅存放于 Netlify 环境变量,不得提交到代码库。
3. 重新部署。

调用示例(服务端):

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

> 如需服务端定时扫描课表自动推送上课提醒,可扩展 Cloud Functions 定时触发器(Pub/Sub schedule),作为后续增强项。

## 已知限制

- 离散周次(如 1, 4, 7)近似为 `min..max` 的连续周处理。
- 自定义时间课程(`isCustomTime`)暂不参与作息时间换算的起止对齐。
- 首次构建如遇 IDE 报错,按提示修复即可(依赖均为稳定版本)。

## 路线图

- [ ] 离散周次精确支持(课程模型增加 `weeks` 列表)。
- [ ] 自定义时间课程起止时间精确对齐。
- [ ] 服务端定时扫描课表并推送上课提醒(Cloud Functions 定时触发器)。
- [ ] 自建后端方案(Cloudflare Workers + D1)评估与实施——见 `docs/workers-backend.md`。

## 许可

本项目基于 MIT 协议开源(见 [LICENSE](LICENSE))。第三方教务适配数据(shiguang_warehouse 子模块)遵循其自有许可(见 `warehouse/LICENSE`)。

---

*Yohaku 极简课程表 · 为专注留白的人设计*
