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
   `data.records` / 顶层数组,字段名也做多候选匹配(见 `parseAnnouncements`)。结构不认识时返回
   空列表,不抛异常。

### 怎么把真实端点抓出来

Debug 构建在登录页注入了一段抓包探针(`ui/yuketang/YuketangLoginScreen.kt` 的 `PROBE_JS`):
包装 `window.fetch` 与 `XMLHttpRequest.prototype.open`,把每个请求的 URL 与 method 经
`AndroidYuketangNative.reportApi` 报给原生,打到 logcat:

```bash
adb logcat -s YuketangProbe
```

在真机上登录(设置 → 雨课堂 → 登录雨课堂),然后进入某课程的「公告」页面,日志里出现的
`/…/announcement…` 那一条就是目标端点。拿到后:

1. 把它作为第一条写进 `ANNOUNCEMENT_PATHS`(其余候选可删);
2. 用该接口的真实响应结构核对 `parseAnnouncements` 的键名与时间字段,必要时收紧;
3. 更新本文档。

## 本机数据

- `yuketang_bindings`:课程 ↔ 雨课堂班级(本机专有,不进云同步;课程表本身会同步,所以绑定
  单独建表,免得被 Firestore 的 pull 覆盖清掉)。
- `announcements`:已拉取的公告缓存。课程详情页与**课前提醒**都只读这份缓存——提醒由精确闹钟
  触发,那一刻不能联网。

退出登录(Menu → 退出登录)会清掉会话与公告缓存,保留绑定关系。
