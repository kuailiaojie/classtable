# AppFunctions 对接说明

把「余课」的课表能力以 **AppFunctions**(Android 平台 API + Jetpack 库)的形式交给**设备端智能体**
调用 —— 相当于把应用变成一个设备端 MCP 服务器。本文记录能力清单、代理选型约束、自检办法与当前状态。

## 前提

- AppFunctions 是 Android 16(API 36)起提供的**实验性**平台能力;与 Gemini 的集成自 2026-05 起面向
  可信测试者私测。
- 调用方(代理 / 其他应用 / Gemini)必须持有 `android.permission.EXECUTE_APP_FUNCTIONS`,才能发现并执行函数。
- 应用侧不必判断设备是否支持:Jetpack 库会处理;低版本或未注册时,系统不会调用本服务。

## 暴露了什么

函数定义在 `app/src/main/java/com/kxin/classtable/appfunctions/ClasstableAppFunctions.kt`(服务)与同文件里的
`@AppFunctionSerializable` DTO 中。底层复用站内助手同一套领域逻辑(`data/AiPlanner.kt` 与各仓库),
所以「系统代理」与「站内助手」两条入口行为一致。

| 函数 | 作用 | 关键参数 |
|---|---|---|
| `getTodayCourses()` | 今天要上的课(按教学周与调休推算) | — |
| `searchCourses(query?, weekday?)` | 按课程名 / 教师 / 地点 / 星期定位课程,返回 `courseId` | `query`、`weekday`(1..7) |
| `getWeekSchedule(week?)` | 某一周的课程 | `week`(缺省为当前周) |
| `getPeriods()` | 作息:每节的起止时间 | — |
| `getSemesterInfo()` | 当前教学周 / 总周数 / 开学日期 | — |
| `addCourse(name, weekday, startSection, endSection, weeks?, location?, teacher?)` | 新建课程 | 节次须落在作息范围内 |
| `addAgendaItem(title, date, startTime?, endTime?, category?, location?)` | 新建日程 / 倒计时 | 日期 `yyyy-MM-dd`、时刻 `HH:MM` |
| `deleteCourse(courseId)` | 删除课程 | `courseId` 须来自查询结果 |

## 代理选型约束

这些同时写在 `res/xml/app_metadata.xml` 的应用级元数据里 —— 代理发现函数时会读到:

- **先查再改**:删除 / 操作前先用 `searchCourses` 或 `getWeekSchedule` 拿到真实 `courseId`,不要臆造 ID。
- **节次有边界**:`addCourse` 的 `startSection` / `endSection` 必须落在作息范围内(可先调 `getPeriods` 确认);
  越界会返回 `AppFunctionInvalidArgumentException`。
- **格式固定**:日期一律 `yyyy-MM-dd`,时刻一律 24 小时 `HH:MM`;`weeks` 是**字符串数组**(如 `["1","3","5"]`)。
- **只动本机**:读写都作用于用户本机课表,不涉及账号与云端。

## 生成物与清单契约

KSP 依据注解生成代码与资产,清单按官方契约指向它们:

| 生成物 | 位置 |
|---|---|
| 具体服务类 `ClasstableAppFunctionService` | `build/generated/ksp/.../appfunctions/` |
| 函数描述 XML `classtable_app_function_service.xml` | 生成到 **assets**(文件名来自 `@AppFunctionServiceEntryPoint(appFunctionXmlFileName = …)`) |
| schema `app_functions_schema.xsd` | 由库随 AAR 提供,合并进 assets |

`AndroidManifest.xml` 里服务声明要点:

```xml
<service
    android:name="com.kxin.classtable.appfunctions.ClasstableAppFunctionService"
    android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
    android:exported="true"
    tools:targetApi="36">
    <property android:name="android.app.appfunctions.schema" android:value="app_functions_schema.xsd" />
    <property android:name="android.app.appfunctions.v2" android:value="classtable_app_function_service.xml" />
    <intent-filter>
        <action android:name="android.app.appfunctions.AppFunctionService" />
    </intent-filter>
</service>
```

应用级再加一条:`<property android:name="android.app.appfunctions.app_metadata" android:resource="@xml/app_metadata" />`。

## 自检(需要 Android 16 真机)

```bash
adb shell cmd app_function list-app-functions
adb shell cmd app_function list-app-functions --package com.kxin.classtable
```

注册不上时按顺序排查:系统版本是否 ≥ 16;是否存在持有 `EXECUTE_APP_FUNCTIONS` 的可调用方;
应用元数据与 schema 资产是否已随包。(实验阶段只有有限的应用与系统代理能跑通整条流水线。)

## 升级 alpha 时的坑

- **注解改过名**:`1.0.0-alpha13` 把 `@AppFunction` 重命名为 `@AppFunctionDeclaration`(官方文档片段仍是旧名)。
  用错名字时 KSP 不报「找不到注解」,而是报 `Class must have at least one AppFunction`,很容易误判。
- **参数类型受限**:只支持标量、`java.time.*`、`Uri`、`ByteArray`、**字符串列表**与 `@AppFunctionSerializable` 类型;
  `List<Int>` 不支持 —— 所以周次以字符串数组传入、在实现里再解析。
- 与助手侧的 **function calling** 是两回事:那边是「我们的 App 调用 LLM 的工具」(`data/AiFunctions.kt`),
  这里是「把 App 的能力暴露给设备端代理」。两者共用领域逻辑,但方向相反。

## 当前状态

- 代码与生成物均已就绪,`assembleDebug` 通过。
- 尚未做**设备端验收**;Gemini 侧集成仍在私测,需要 EAP 名额才能真正端到端跑通。
