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

登录态有效期约两周;失效时接口返回业务错误码或明确「未登录」文案,客户端映射为
`NotLoggedInException`(与「网络不通」区分:后者抛 `IOException`)。

## 请求头

`Cookie`、`X-CSRFToken`、`uv-id`、`university-id`、`xtbz: ykt`、`xt-agent: web`、`x-client: web`、
`classroom-id`(部分接口)、`Referer`、桌面 Chrome `User-Agent`
(由 `data/WebUserAgent.desktop(context)` 按本机 WebView 内核版本生成,不写死版本号)。

## 已实现端点

| 用途 | 方法 | 路径 |
|---|---|---|
| 校验登录态 / 课程班级列表 | GET | `/v2/api/web/courses/list?identity=2` |
| 课程公告 | GET | `/v/discussion/v2/announcements/?cid=&limit=&offset=&type=9` |

`courses/list` 的响应关键字段:`data.list[].classroom_id`、`.name`(班级名)、
`.course.name`(课程名)、`.teacher.name`。

## 公告

### 端点(已抓包确认)

```http
GET /v/discussion/v2/announcements/?content=&cid={classroomId}&limit=30&offset=0&type=9
```

参考文档**没有**收录公告接口,这一条是 2026-09 在长江雨课堂网页版上抓包确认的
(课程页 → 「公告」标签页)。

两个反直觉之处:

1. **不在 `/v2/api/web/` 下,而在 `/v/discussion/v2/` 下** —— 雨课堂的「公告」在服务端就是
   `topic_type == 9` 的**讨论主题**,和讨论区共用一组接口。
2. **错误约定是 `{code, msg, success}`**,不是 `/v2/api/web/` 那套 `errcode/errmsg`。客户端
   三套都认(`errcode` / `code` / `error_code` + `success`)。

参数:`cid` 班级 id、`limit`/`offset` 分页、`type=9` 公告、`content` 关键词(空 = 不过滤)。
分页翻到 3 页为止(公告是「看最新几条」的场景)。

### 响应结构

```json
{ "msg": "", "code": 0,
  "data": { "count": 2, "has_old_notice": false, "previous": null,
    "results": [{
      "id": 9149234,
      "topic_type": 9,
      "topic_name": "听力自主任务1",
      "content": { "text": "微信小程序:听力随身练", "app_text": "…", "upload_images": ["…"] },
      "publish_time": 1790172932000,
      "create_time": "2026-09-23T14:15:32.937881Z",
      "app_publish_time": "2026-09-23 22:15",
      "user_info": { "name": "徐芳", "nickname": "徐芳" },
      "publisher_name": null,
      "classroom_id": 26770861,
      "is_read": true, "is_top": 0, "read_num": "34/64", "chapter_id": null
    }]}}
```

字段映射(`YuketangClient.parseAnnouncements`):

| 模型字段 | 来源 |
|---|---|
| `id` | `id`(缺失时退化成 `classroomId:时间:标题哈希`) |
| `title` | `topic_name` |
| `content` | `content.text` |
| `createdAtMillis` | `publish_time`(epoch 毫秒);缺失才退回 `create_time`(ISO8601) |
| `publisher` | `user_info.name`,再退 `publisher_name` |

### 抓包方法(留档)

浏览器**只用来登录和拿 Cookie**;数据只用应用自己的 HTTP 客户端取。
确认这条端点用的就是「打开课程页 → 点『公告』→ 读请求 URL」这一步(只读 URL,不读响应体)。

Debug 构建在登录页注入抓包探针(`ui/yuketang/YuketangLoginScreen.kt` 的 `PROBE_JS`),
把网页发出的请求 URL 报到 logcat:

```bash
adb logcat -s YuketangProbe YuketangSync YuketangClient
```

### 接口漂移时的逃生口

「设置 → 雨课堂 → 高级 → 公告接口」可以手填路径替换内置值(留空 = 内置
`/v/discussion/v2/announcements/`),填完点「立即刷新公告」即可验证,不必等发版。
查询参数由应用拼,填路径即可。

### 排查线索

日志 tag:

| tag | 内容 |
|---|---|
| `YuketangProbe` | 登录页抓包探针:网页发出的每个请求 URL |
| `YuketangClient` | 请求的 URL、原始响应片段(400 字符) |
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
  单独建表,免得被 Firestore 的 pull 覆盖清掉)。**一条 App 课程对应一条绑定**,多条课程记录
  可以指向同一个班级。
- `announcements`:已拉取的公告缓存,**按雨课堂班级归属**(不是按 App 课程)。同一门课在课表里
  可能有多条记录并绑到同一个班级,挂在课程 id 上会因主键冲突互相顶掉,只有一条记录看得到公告。
  课程详情页与**课前提醒**都读这份缓存——提醒由精确闹钟触发,那一刻不能联网,所以读缓存前要
  先用绑定表把课程 id 换成班级 id。

退出登录(设置 → 雨课堂 → 退出登录)会清掉会话与公告缓存,保留绑定关系。
