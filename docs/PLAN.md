# 余课 · 持续演进计划与执行追踪 (Evolution Plan & Tracker)

> **Antigravity 跨会话追踪说明 / Multi-Session Continuity Protocol**：
> 本文件为项目的唯一进行中计划追踪源（Source of Truth）。
> 当用户在 Google Antigravity 中开启新对话或切换会话时，AI 助手**必须首先读取此文件**，识别当前最新的完成状态（`[x]` 与 `[ ]`）、已发布的版本号和待执行的下一阶段，并在此基础上继续执行与更新本计划。
> 
> **每次完成一个阶段的硬性规则**：
> 1. 先修改 `app/build.gradle.kts` 中的 `versionCode` (+1) 与 `versionName`；
> 2. 更新 `CHANGELOG.md`，添加新版本的变更内容；
> 3. 更新本文件（`docs/PLAN.md`）中的阶段状态为 `[x]`，并记录对应 commit hash；
> 4. 执行编译校验确保无报错；
> 5. 执行 `git add` 并 `git commit`。

---

## 阶段进度总览 (Roadmap Milestones - 最新发布版本 3.13.3 / 97)

- [x] **Phase 1: 周次快速选择网格面板 (Week Quick Picker)**
  - 目标：点击课表顶栏「第 N 周」弹出 1~N 周的网格选择器，支持直观展示当前周、周日期区间与课程密度，点击 1-tap 快速跳周。
  - 状态：✅ 已完成 (3.8.0 / 88)

- [x] **Phase 2: 工作日与周末视图切换 (5天/7天/自动折叠周末)**
  - 目标：增加周末显示设置（显示全部 7 天 / 仅显示 5 天 / 周末无课自动折叠），折叠后工作日列宽提升 40%，大幅改善排版呼吸感。
  - 状态：✅ 已完成 (3.8.0 / 88)

- [x] **Phase 3: 课程防撞车与重叠冲突检测 (Course Conflict Warning)**
  - 目标：新建/编辑课程表单及 AI 助手操作时，实时比对同星期、重叠节次/时间与生效周次，展示友好的冲突提示，避免排错课。
  - 状态：✅ 已完成 (3.8.0 / 88)

- [x] **Phase 4: 本地全量数据离线备份与恢复 (.classtable.json)**
  - 目标：在设置页提供免登录的 JSON 全量数据导出与导入，支持无网/无账号用户安全备份与换机迁移。
  - 状态：✅ 已完成 (3.8.0 / 88)

- [x] **Phase 5: iCalendar (.ics) 日历导出与手表生态同步**
  - 目标：一键将全学期课程导出为标准 RFC 5545 `.ics` 日历文件，打通系统日历与各类智能手表/手环生态。
  - 状态：✅ 已完成 (3.8.0 / 88)

- [x] **Phase 6: Yohaku 美学课表长图导出 (Schedule Image Share)**
  - 目标：生成高清极简纸感课表长图（PNG），保留思源宋体与和色质感，支持手机锁屏壁纸与打印。
  - 状态：✅ 已完成 (3.8.0 / 88)

- [x] **Phase 7: 大学生日程规划与 Apple HIG 交互规范升级 (Daily Flow & HIG)**
  - 目标：时间线触感打卡与划线淡化（Strikethrough & Dim）、日视图全景时间轴与空堂感知（$\ge 60$分钟智能识别）、周视图日程微圆点（Micro Dots）、课程详情挂载任务与课前提醒联动待办备忘、Room v11 数据库无损迁移 (`MIGRATION_10_11`)、全量课程与待办 RFC 5545 日历互通。
  - 状态：✅ 已完成 (3.9.0 / 89 - Commit: e0cf8b1)

- [x] **Phase 8: 日程规划系统级闭环与稳定性加固 (Systemic Stability & Flow Fixes)**
  - 目标：日视图日程跳转与空堂参数修复、新建/编辑表单关联课程选择落地、防止已打卡事项误触发闹钟提醒、课前提醒实装待办备忘、离线备份与恢复补全 `courseId` 与 `completed` 字段、小组件过滤已完成事项、日视图跨天时刻与上课中高亮校准、周视图表头双指示条、Room v12 数据库索引迁移 (`MIGRATION_11_12`)。
  - 状态：✅ 已完成 (3.9.1 / 90 - Commit: 520edd6)

- [x] **Phase 9: 美学排版优化、日程倒计时独立、雨课堂通知闭环与长图全面屏重构 (Aesthetics & Notification & Wallpaper Exporter Refinements)**
  - 目标：1800×3900 高清 9:19.5 现代竖屏手机全面屏长图引擎、地点与教师自适应排版保障 100% 呈现、长图解耦 ICS 导出二选一、雨课堂新公告空班级基线修复与前台即时高优先级推送、日程与倒计时独立表单与实时倒数预览、日程周条 36×36dp 与课程排期 4 列方块等宽几何规整、周次选择网格去除「今」角标。
  - 状态：✅ 已完成 (3.10.0 / 91 - Commit: 5d162fb)

- [x] **Phase 10: 安全防护、云同步一致性、后台可靠性与架构工程化加固 (Security, Sync Consistency & Architectural Hardening)**
  - 目标：AI API 密钥 Keystore AES-GCM 隔离存储与备份排除、SecurePrefs 坏密钥自愈重试、Netlify 反代白名单与防重放、Firestore 安全规则最小权限收紧、调休安排与多学期表云同步与离线灾备全覆盖、ReminderPlanner Mutex 并发互斥与排程事件防过度触发、桌面图标后台安全延迟切换、WakeLock 10s 延展、SemVer 2.0 预发布版本比较重构、教务导入 WebView 多层弹窗支持与静态调用优化、统一 FirestoreFields/HttpUtils 工具库、Room exportSchema 快照、纯 Kotlin 核心领域逻辑 JVM 单元测试全覆盖。
  - 状态：✅ 已完成 (3.11.0 / 92 - Commit: 054fb1f)

- [x] **Phase 11: Apple HIG 体验重构、人体工学加固与艺术长图引擎升级 (HIG Ergonomics, Touch Safety, State Preservation & Wallpaper Engine v2)**
  - 目标：Apple HIG 触控热区安全组件 `YohakuCheckCircle`（44pt+ 热区防误触）、外观设置主色色板规范对齐、课表天气入口热区与微触感、课程详情页编辑上提与删除物理隔离、周视图大标题右侧「导出长图」胶囊入口（方案 A）、日视图空堂智能带入表单起止时间、日程表单全字段 `rememberSaveable` 状态持久化与阻塞提示、多选态系统返回拦截、周日视图已结课程时序淡化、Wallpaper Engine v2 1800×3900 大字号排版系统重构、和纸胶带装饰与纸感微浮雕微墨投影。
  - 状态：✅ 已完成 (3.12.0 / 93 - Commit: 5458099)

- [x] **Phase 12: 课表长按交互与预选节次、长图排版引擎重构、主题视觉对齐与无 Emoji 规范**
  - 目标：周/日视图网格与卡片长按感应唤起「新建课程」并路由预填星期与节次；彻底重构长图排版引擎，禁用伪粗体、引入黄金字阶与呼吸感行距、副信息改用清晰 Sans-serif 字体与 `TextUtils.ellipsize` 像素级防溢出；日程选中日期契合当前主题色；导出与回到今天按钮尺寸收敛；已结课程淡彩轻量变淡；全应用无 Emoji 极简打磨。
  - 状态：✅ 已完成 (3.13.0 / 94 - Commit: 9df64cd)

- [x] **Phase 13: 周视图已结课程隐形缺陷根治与动效生命周期加固 (WeekGrid Slot Reuse & Past Course Visibility Fix)**
  - 目标：彻底修复周视图 Compose 槽位复用导致 `appear.value` 永久卡在 0f（100% 透明隐形但能被点击）的 Bug；入场动效绑定 `course.id` Key 并引入 `snapTo(1f)` 安全兜底；已结课程统一增加实体精致微边框 `Modifier.border(1.dp, colors.line, shape)` 并微调透明度至 85%，确保轮廓分明、清晰可见。
  - 状态：✅ 已完成 (3.13.1 / 95 - Commit: 18ecae7)

- [x] **Phase 14: 移除周网格课程块 0f 透明度入场动效与复合 Key 升级 (Eliminate Animatable(0f) Trap & Multi-day Key Fix)**
  - 目标：从源头彻底拔除周视图课程块中的 `Animatable(0f)` 入场动效，直接赋予确定透明度（未结课 1.0f / 已结课 0.78f），物理消除任何因协程取消导致的透明度卡在 0f 的隐形 Bug；课程块 Key 升级为 `(day, course.id, top)` 复合唯一键，解决同一课程在周内多天复用时的键碰撞。
  - 状态：✅ 已完成 (3.13.2 / 96)

- [x] **Phase 15: 官网与 README 视觉艺术化升级与概念图体系 (Artistic Landing Page & README Yohaku Overhaul)**
  - 目标：将余白（Yohaku）美学、东方纸感与和色设计哲学全面融入官方网站与 README；生成并装配 3 张宽屏高清艺术概念图（宽屏晨光意境横幅、AI 智能助手概念图、和风书桌与小组件生活化场景）；重构 README 视觉层次（特性矩阵、和色罗盘、实机展台）；官网新增 AI 助手交互卡片展示、灵动胶囊动态模拟、和色调色盘实时切换全站强调色交互；更新 FAQ 补全课程改色、AI 密钥与胶囊配置指引。
  - 状态：✅ 已完成 (3.13.3 / 97 - Commit: e9217ef)

---

## 详细实施记录 (Execution Log)

### Phase 1: 周次快速选择网格面板 (Week Quick Picker)
* **执行时间**：2026-10-29
* **涉及核心文件**：
  - `app/src/main/java/com/kxin/classtable/ui/timetable/TimetableScreen.kt`
  - `app/src/main/java/com/kxin/classtable/ui/timetable/WeekPickerDialog.kt`（或内置弹窗组件）
  - `CHANGELOG.md`
  - `app/build.gradle.kts`
* **验收标准**：
  1. 课表大标题与周次区域支持点击，带有轻微触感（`haptics.tick()`）与点击态反馈；
  2. 弹窗展现 1 至 `weekCount`（通常为 1~20 或 24）周的紧凑网格；
  3. 当前真实周（`realWeek`）有特殊标识，当前浏览周（`displayedWeek`）有选中高亮；
  4. 每周卡片展示周次号、简短日期范围（如 `9/14–9/20`）及课程状态；
  5. 点击任意周后弹窗平滑关闭，主课表无缝平移至目标周；
  6. 适配折叠屏与宽屏，弹窗限宽居中。
* **Commit**：*待提交*
