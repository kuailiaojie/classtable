# 雨课堂(changjiang.yuketang.cn)接口使用备忘

> 实现位置:`data/yuketang/`。参考来源是
> [`DonAzufre/rain-class-reviewer` 的 `references/yuketang-api.md`](https://github.com/DonAzufre/rain-class-reviewer/blob/main/references/yuketang-api.md),
> 本文只记录**本应用实际用到**的部分与本地抓包结论。平台无公开文档,接口可能随时变动。

## 认证

会话来自应用内 WebView 的登录(用户自行扫码 / 账密)。`sessionid` 是 **HttpOnly**,网页脚本
`document.cookie` 读不到,只能由原生 `CookieManager.getCookie(origin)` 取,再加密存本机
(`EncryptedSharedPreferences`,文件 `yuketang_session`)。

| Cookie | 说明 |
|---|---|
| `sessionid` | 必需;无此值即视为未登录 |
| `csrftoken` | 建议携带,同时作为 `X-CSRFToken` 请求头 |
| `uv_id` / `university_id` | 站点 / 学校 id,按实际值原样带回,不硬编码 |
| `xtbz` | 通常 `ykt` |

登录态有效期约两周;失效时接口返回 `errcode` 非 0 或明确「未登录」文案,客户端映射为
`NotLoggedInException`(与「网络不通」区分:后者抛 `IOException`)。

## 请求头

`Cookie`、`X-CSRFToken`、`uv-id`、`university-id`、`xtbz: ykt`、`xt-agent: web`、`x-client: web`、
`classroom-id`(部分接口)、`Referer`、桌面 Chrome `User-Agent`
(由 `data/WebUserAgent.desktop(context)` 按本机 WebView 内核版本生成,不写死版本号)。

## 已实现端点

| 用途 | 方法 | 路径 |
|---|---|---|
| 校验登录态 / 课程班级列表 | GET | `/v2/api/web/courses/list?identity=2` |
| 课程公告 | GET | 见下 |

`courses/list` 的响应关键字段:`data.list[].classroom_id`、`.name`(班级名)、
`.course.name`(课程名)、`.teacher.name`。

## 公告端点:待抓包确认

参考文档**没有**收录公告接口,公开项目里也没有可靠实现,所以当前实现是「候选路径依次探测 +
抓到即固化」:

1. `YuketangClient.ANNOUNCEMENT_PATHS` 按可能性排序列出若干候选路径;
2. 逐个请求,第一个返回业务成功(`errcode == 0`)的路径会在进程内缓存(`resolvedAnnouncementPath`),
   之后不再重复探测;404 会继续试下一个候选;网络错误直接放弃(不是端点的问题);
3. 响应解析是**宽容式**的:列表可能藏在 `data.list` / `data.announcements` / `data.notices` /
   `data.records` / 顶层数组,字段名也做多候选匹配(见 `parseAnnouncements`)。**单条端点的**响应
   结构不认识时按空列表处理(端点仍算可用);候选端点**全部**不可用时则抛出异常,由调用方区分
   「没公告」与「接口找错了」。

### 怎么把真实端点抓出来

Debug 构建在登录页注入了一段抓包探针(`ui/yuketang/YuketangLoginScreen.kt` 的 `PROBE_JS`):
包装 `window.fetch` 与 `XMLHttpRequest.prototype.open`,把每个请求的 URL 与 method 经
`AndroidYuketangNative.reportApi` 报给原生,打到 logcat:

```bash
adb logcat -s YuketangProbe YuketangSync YuketangClient
```

在真机上登录(设置 → 雨课堂 → 登录雨课堂),然后进入某课程的「公告」页面,日志里出现的
`/…/announcement…` 那一条就是目标端点。拿到后:

1. 把它作为第一条写进 `ANNOUNCEMENT_PATHS`(其余候选可删);
2. 用该接口的真实响应结构核对 `parseAnnouncements` 的键名与时间字段,必要时收紧;
3. 更新本文档。

**不发版也能验证**:「设置 → 雨课堂 → 高级 → 公告接口」可以手填路径(留空 = 自动探测),
填完点「立即刷新公告」即可。

### 拉取失败的排查线索

接口候选全部不可用时客户端**抛出并把每次尝试写进日志**,而不是悄悄返回空列表 —— 否则
「这门课没有公告」与「接口找错了」无法区分。日志 tag:

| tag | 内容 |
|---|---|
| `YuketangProbe` | 登录页抓包探针:网页发出的每个请求 URL |
| `YuketangClient` | 逐候选端点的尝试结果、可用端点、原始响应片段(400 字符) |
| `YuketangSync` | 逐班级拉取结果、新公告标题、是否被判为「上课提醒」、同步汇总 |

## 「上课提醒」的过滤

雨课堂自己也会在课前/开课时推「上课提醒 / 开始签到」这类消息,而课表本来就有课前提醒 ——
重复推一次没有意义。`YuketangNoticeFilter` 按**文案**把它们识别出来,从**新公告通知**与
**课前提醒**里排除(课程详情的时间轴仍照实显示)。

- 只能按文案识别:接口没有可靠的类型字段。
- 词表刻意保守,认不出的当普通公告 —— 宁可漏掉一条提醒,也不误杀老师写的真公告
  (如「下节课带计算器」)。
- 过滤在**读取时**做(不在 SQL 里、也不落库成列),所以调词表即时生效、旧数据不必回填。

## 本机数据

- `yuketang_bindings`:课程 ↔ 雨课堂班级(本机专有,不进云同步;课程表本身会同步,所以绑定
  单独建表,免得被 Firestore 的 pull 覆盖清掉)。
- `announcements`:已拉取的公告缓存。课程详情页与**课前提醒**都只读这份缓存——提醒由精确闹钟
  触发,那一刻不能联网。

退出登录(Menu → 退出登录)会清掉会话与公告缓存,保留绑定关系。
