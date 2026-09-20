# 架构与技术栈

> 面向开发者的说明。产品介绍见 [README](../README.md)。

## 技术栈

| 分类 | 选型 |
|---|---|
| 语言 | Kotlin 2.1.0 |
| UI | Jetpack Compose(BOM 2024.12.01),自建 Yohaku 设计系统,不沿用 Material3 默认外观 |
| 构建 | AGP 8.7.3 / KSP(`2.1.0-1.0.29`)/ Gradle Wrapper(已提交) |
| 架构 | MVVM + Hilt 2.54 |
| 本地存储 | Room 2.7.0、DataStore 1.1.1、SecurityCrypto 1.1.0 |
| 后台任务 | WorkManager 2.10.0 |
| 小组件 | Glance 1.1.1 |
| 后端 | Firebase(Auth / Firestore / Analytics / Crashlytics / FCM,BOM 33.7.0)经 Netlify Functions 反代 |
| 网络 | HttpURLConnection + REST(认证与同步不依赖 Firebase SDK) |

## 环境要求

| 项 | 要求 |
|---|---|
| IDE | Android Studio(含 JDK 17+) |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 26(Android 8.0+) |
| 网络 | 首次 Sync 需联网下载依赖 |

## 构建

```bash
./gradlew assembleDebug      # 调试版 APK(可直接安装)
./gradlew assembleRelease    # 发布版(未配置签名时产出未签名 APK)
```

- Release 构建开启 R8 混淆 + 资源压缩,且只打 `arm64-v8a` / `armeabi-v7a`(x86 模拟器请用 debug 包)。
- `JavascriptInterface` 方法是按名字反射调用的,keep 规则见 `app/proguard-rules.pro`。
- 内置思源宋体已子集化到 GB2312 全字集(14.1MB → 3.3MB);需要重新生成时执行 `python tools/subset-font.py`。
- 无外网时可用 `-PfirebaseCrashlyticsMappingFileUploadEnabled=false` 跳过 Crashlytics 符号上传。
- Release 签名配置见 [release-signing.md](release-signing.md);后端配置见 [backend-setup.md](backend-setup.md)。

应用默认以访客本地模式运行,不需要任何后端配置即可体验完整课表功能。

## 整体架构

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
- **提醒通道**:本地精确闹钟是准点提醒主力(离线可用),FCM 为实时增强通道。排程实现见 `notify/ReminderPlanner.kt`:按「排程签名 + 已排台账」管理 8 天滚动窗口,并排一个次日 00:05 的自续期闹钟;实时活动由前台服务持有,payload 持久化以便进程被杀后恢复。

## 目录结构

```
app/src/main/java/com/kxin/classtable/
  design/      # Yohaku 设计系统(色板 / 字阶 / 间距 / 组件 / 自绘弹窗与日期选择器)
  domain/      # Course / Semester / Schedule(节次↔时间换算、学期周推导)
  data/        # Room + DataStore + 同步 + 更新检查 + import(WarehouseIndex / ImportParser)
  di/          # Hilt 依赖注入模块
  notify/      # 提醒排程 / 通知构建 / 实时活动服务 / 开机重排
  ui/          # 周视图 日视图 课程 表单 导入(3 步 + JS 桥) 设置(+ 子页) 账号
  widget/      # Glance:1×1 下节课 + 4×2 今日课表
netlify/functions/proxy.mjs   # 后端反代(认证 / Firestore / 推送 / 版本 / APK)
netlify/static/               # 构建期生成:warehouse/bundle.json(适配器同步源,CDN 分发)
tools/yaml2json.mjs           # 教务仓库 YAML → assets JSON 预编译
tools/build-netlify.mjs       # 适配器 assets → Netlify 静态 bundle
.github/workflows/build-apk.yml   # GitHub Actions:自动构建并上传 APK
```

## 数据模型与同步

| 层 | 存储 | 说明 |
|---|---|---|
| 本地 | Room:`courses` + `deleted_courses`(墓碑) | 离线优先,真相在本地 |
| 本地 | DataStore `settings` | 主题 / 强调色 / 作息 / 学期 / 提醒设置 / 更新状态 |
| 远端 | Firestore:`users/{uid}/courses/{courseId}` + `users/{uid}/deleted/{courseId}` | 云同步 |

同步策略:本地优先;登录后 pull → 合并(`updatedAt` 后者胜)→ 应用墓碑 → push;删除以墓碑传播,防止已删课程在其它设备复活。

课程字段:`id / name / teacher / location / weekday / weekdays(位掩码)/ startPeriod / endPeriod / weekType / weekStart / weekEnd / customStartMinute / customEndMinute / note / updatedAt`。作息与学期单独存在设置里,课程只引用节次序号,因此改作息会整体平移所有课程的时间。
