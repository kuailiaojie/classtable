# 课程表(Yohaku 极简课程表)

面向中国在校大学生的极简效率型课程表 App。视觉遵循 **Yohaku 设计哲学**(一抹 accent、三档中性、余者尽留为白),刻意不走多彩贴纸/液态玻璃路线。

## 项目状态

| 阶段 | 状态 |
|---|---|
| 0 概念理解 | ✅ 通过 |
| 1 设计稿(6 界面) | ✅ 通过(accent 梅 #C56473) |
| 2 技术方案 | ✅ 通过 |
| 3 编码 | ✅ 源码完成,素材已全部内置(**待首次构建验证**) |

## 已实现

- **Yohaku 设计系统**(design 包):三档中性暖纸面色板(浅/深色)、accent 梅、**内置思源宋体 Medium + JetBrains Mono Regular**(res/font)、下划线输入框、chip、卡片、按钮
- **周视图**:**一屏一天、左右滑动切换周一到周日**(七天不挤一屏),顶部星期胶囊可点击直达;每天为**逐节网格**,左侧标「第 N 节 + 起止时间(几点到几点)」,**每节固定等高整整齐齐**,课程卡**绝对定位横穿**:普通课横穿其节次区间、自定义时间课程横穿其起止时间覆盖的所有节行,空堂纯留白、正在上的课 4px 左边条、周导航 + 「今天」跳真实当前周;格子显示课程名 + 时间 + 地点,点按弹出完整信息,可跳课程详情
- **日视图**:纯列表、卡片间留白即空堂、当前课 accent
- **课程表单**:整页、衬线课程名输入、**星期可多选**(一周多天上课)、节次/周次 chip、自定义周次、**备注(选填)**;**时间可切换「按节次 / 自定义时间」**(非作息课程如晚间讲座、临时加课,填 HH:MM 起止即可,不随作息变化)
- **边缘到边缘**:纸面底色铺满状态栏与导航栏区域,栏内图标深浅跟随主题
- **课程列表 + 详情**:底部导航「课程」;详情含**具体上课日期排期**(基于学期起始日列出每次课的日期,如 9/14 周一),可编辑/删除
- **课程提醒通知**:每节课开始前发送通知(设置页可开关、可设提前量:准点/5/10/15/30/60 分钟);通知内容在触发时**动态计算**(课程名/剩余分钟/开始时间/地点/教师),点击直达课程详情;未来 14 天精确闹钟预排,开机/改时区自动重排,Android 12+ 无精确闹钟权限时自动降级
- **日视图实时动态**:每 30 秒刷新「正在上课 · 还有 N 分钟下课 / 距下一节还有 N 分钟」
- **教务导入(真实接入 shiguang_warehouse)**:146 所学校 / 156 个适配器(正方/强智/青果/URP/超星等)
  - 选学校 → **选适配器**(一校多适配器可区分:作者/分类/描述)→ **阅读适配器说明(描述/作者/操作提示)后再确定导入**
  - **通用教务**(URP/正方/青果/超星通用方案)需手动填写教务系统网址后导入;开发者自检工具(通用工具与服务)已从列表隐藏
  - WebView 登录(账号密码仅本机会话)→ 注入适配脚本(JS 桥:AndroidBridge*/shiguangBridge*)
  - 自动应用脚本产出的**作息时间**与**开学日期**;课程按周次自动归类(每周/单/双/自定义)
- **手动导入**:粘贴表格数据(CSV/TSV),或**直接从 Excel 文件导入(.xlsx/.csv/.tsv)**,自动识别表头、UTF-8/GBK 编码;第 8、9 列可填开始/结束时间(HH:MM)生成自定义时间课程
- **AI 图片导入**:多供应商——Gemini 或任意 **OpenAI 兼容**服务(DeepSeek/通义千问/Kimi/智谱 GLM 等),设置页可配供应商/密钥/Base URL/模型;识别课程与作息并自动应用
- **设置**:主题(浅/深/跟随系统)、5 和色 accent、作息时间子页(**每节独立填开始+结束时间**,可自由增删节,不限于 12 节)、学期周次子页(日期选择器)、AI 密钥(多供应商)
- **作息一键同步**:教务系统 / AI 图片 / 手动表格导入识别到作息时自动应用(手动导入可粘贴"08:00-08:50"行,导入时一并同步)
- **账号**:Firebase Email/Password,访客本地模式,登录后自动同步;登录后可发送密码重置邮件
- **同步**:Room 本地优先 + Firestore;updatedAt 后者胜;删除墓碑传播(防复活)
- **Firebase**:Email/Password 认证 + Firestore 同步 + **Analytics**(课程增删/导入/登录/提醒等事件)+ **Crashlytics**(崩溃自动上报,带版本自定义 key)+ **FCM 推送**(令牌自动同步到 Firestore,支持服务端定向推送课程提醒)。认证与同步经 **Netlify 反代中间层**访问(大陆可用,见下)
- **Glance 小组件**:1×1 下节课、4×2 今日课表(当前课 accent、跟随动态作息)

## 环境要求

- Android Studio(含 JDK 17+,会自动下载 Android SDK)
- compileSdk 35 / targetSdk 35 / minSdk 26
- Kotlin 2.1.0 + Compose BOM 2024.12.01 + AGP 8.7.3 + Gradle 8.11.1

## 打开与构建

1. 用 Android Studio 打开本目录(`File > Open`)。
2. gradle-wrapper.jar 缺失时 AS 会提示 Fix;首次 Sync 下载依赖(需网络)。
3. 运行到模拟器/真机。

> 已知:源码未在本机编译过,首次构建若有报错按 IDE 提示修即可。

## 教务导入数据流

```
assets/warehouse/
  index.json     # 146 校索引(由 tools/yaml2json.mjs 预编译自仓库 root_index.yaml)
  adapters.json  # 156 适配器配置(同上,自各校 adapters.yaml)
  resources/<SCHOOL>/<script>.js   # 适配脚本(注入 WebView 执行)
```

- 更新仓库:重新下载 shiguang_warehouse 到 `warehouse/` 后运行 `node tools/yaml2json.mjs` 重新生成索引。
- 脚本契约:注入即自执行;`AndroidBridge.showToast/notifyTaskCompletion` + `AndroidBridgePromise.showAlert/showPrompt/showSingleSelection/saveImportedCourses/savePresetTimeSlots/saveCourseConfig`(v2 别名 shiguangBridge*)。
- 已知限制:离散周次(如 1,4,7)近似为 min..max 的连续周;自定义时间课程(isCustomTime)暂忽略起止时间。

## Firebase(classtable-4a7d0)

Android App 已注册:`com.kxin.classtable`(google-services.json 已就位)。

```bash
firebase login --no-localhost
firebase use classtable-4a7d0
firebase deploy --only auth          # 启用 Email/Password
# Console 创建 Firestore(建议 Standard,asia-east1)
firebase deploy --only firestore:rules
```

安全规则见 `firestore.rules`;`firebase.json` 已配置。

### 大陆访问(Netlify 反代中间层)

Android 的 Firebase Auth/Firestore SDK 硬编码 Google 域名,大陆无法直连。本项目已改为 **REST 版认证/同步,全部请求经自建 Netlify Function 反代转发**,客户端不再触碰被墙域名:

```
Android App ──HTTPS──▶ Netlify 反代(你的站点) ──▶ Firebase Auth / Firestore
```

- 反代代码:`netlify/functions/proxy.mjs`(`/auth/*` → identitytoolkit.googleapis.com,`/firestore/*` → firestore.googleapis.com,方法/query/body/**Authorization(ID token)** 原样透传,Firestore 安全规则照常评估)
- **部署步骤**:
  1. Netlify 控制台 → `Add new site` → 连接本仓库(或拖拽 `netlify/functions` 上传为 Functions)
  2. 部署完成后得到站点地址 `https://<site>.netlify.app`(**建议绑定自有域名**,netlify.app 在大陆可达性一般)
  3. 把地址写进 `app/build.gradle.kts` 的 `FIREBASE_PROXY_URL`(当前已配置为 `https://classtablek.netlify.app/.netlify/functions/proxy`;换部署站点只改这一行,经 BuildConfig 注入)
  4. 重新构建安装 App
- 说明:已移除 `firebase-auth` / `firebase-firestore` SDK 依赖(改用 REST);`firebase-analytics` / `firebase-messaging` / `firebase-crashlytics` 保留。同步由「实时监听」变为**按需 pull/push**(App 启动 / 登录 / 网络恢复时),对课程表场景无感知差异。崩溃报告走 Crashlytics 官方通道(不经过反代),大陆无网络时会本地缓存、恢复后补传。
- 免费额度 12.5 万次请求/月,登录 + 同步绰绰有余。

## 数据模型

- 本地:Room `courses` + `deleted_courses`(墓碑)+ `semesters` + DataStore `settings`(主题/accent/当前周/作息/学期)
- 远端:Firestore `users/{uid}/courses/{courseId}` + `users/{uid}/deleted/{courseId}`
- 同步:本地优先;登录后 pull → 合并(updatedAt 后者胜)→ 应用墓碑 → push;未登录 = 访客本地模式

## 目录结构

```
app/src/main/java/com/kxin/classtable/
  design/      # Yohaku 设计系统(色板/字阶/间距/组件/内置字体)
  domain/      # Course/Semester/Schedule(节次↔时间换算、学期周推导)
  data/        # Room + DataStore + Firebase 同步 + import(WarehouseIndex/ImportParser)
  ui/          # 周视图 日视图 表单 导入(3步+JS桥) 设置(+子页) 账号
  widget/      # Glance:1×1 下节课 + 4×2 今日课表
tools/yaml2json.mjs   # 仓库 YAML → assets JSON 预编译
```

## 待办

- [ ] 首次构建验证
- [ ] Firebase 三步(Console/CLI,见上)
- [ ] 离散周次精确支持(课程模型增加 weeks 列表)
- [ ] 自定义时间课程(isCustomTime)支持
