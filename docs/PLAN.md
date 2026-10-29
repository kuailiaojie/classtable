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

## 阶段进度总览 (Roadmap Milestones - 统一发布版本 3.8.0 / 88)

- [x] **Phase 1: 周次快速选择网格面板 (Week Quick Picker)**
  - 目标：点击课表顶栏「第 N 周」弹出 1~N 周的网格选择器，支持直观展示当前周、周日期区间与课程密度，点击 1-tap 快速跳周。
  - 状态：✅ 已完成

- [x] **Phase 2: 工作日与周末视图切换 (5天/7天/自动折叠周末)**
  - 目标：增加周末显示设置（显示全部 7 天 / 仅显示 5 天 / 周末无课自动折叠），折叠后工作日列宽提升 40%，大幅改善排版呼吸感。
  - 状态：✅ 已完成

- [x] **Phase 3: 课程防撞车与重叠冲突检测 (Course Conflict Warning)**
  - 目标：新建/编辑课程表单及 AI 助手操作时，实时比对同星期、重叠节次/时间与生效周次，展示友好的冲突提示，避免排错课。
  - 状态：✅ 已完成

- [x] **Phase 4: 本地全量数据离线备份与恢复 (.classtable.json)**
  - 目标：在设置页提供免登录的 JSON 全量数据导出与导入，支持无网/无账号用户安全备份与换机迁移。
  - 状态：✅ 已完成

- [x] **Phase 5: iCalendar (.ics) 日历导出与手表生态同步**
  - 目标：一键将全学期课程导出为标准 RFC 5545 `.ics` 日历文件，打通系统日历与各类智能手表/手环生态。
  - 状态：✅ 已完成

- [x] **Phase 6: Yohaku 美学课表长图导出 (Schedule Image Share)**
  - 目标：生成高清极简纸感课表长图（PNG），保留思源宋体与和色质感，支持手机锁屏壁纸与打印。
  - 状态：✅ 已完成

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
