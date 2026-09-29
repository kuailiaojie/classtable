# 动效规范

余课的动画只有一套语言,取自 **GSAP 的运动原则**——不是它的 API(那是 JS 库,跑不进 Compose),
而是它组织动画的方式:统一缓动、克制的时长、`timeline` 编排、`stagger` 错峰、只动合成层,
以及 `prefers-reduced-motion` 的等价处理。

两端各有一份实现:

| 端 | 入口 | 说明 |
|---|---|---|
| Android | [`design/YohakuMotion.kt`](../app/src/main/java/com/kxin/classtable/design/YohakuMotion.kt) + [`design/YohakuTimeline.kt`](../app/src/main/java/com/kxin/classtable/design/YohakuTimeline.kt) | token 与时间线原语,全应用动画的唯一数值来源 |
| 官网 | [`netlify/site/assets/motion.js`](../netlify/site/assets/motion.js) | 真正的 GSAP(GSAP + ScrollTrigger + ScrollToPlugin,本地 vendoring) |

---

## Android

### 1 · 铁律

- **数值只从 `YohakuMotion` 取**。任何 `duration` / `easing` / `stagger` 字面量都算 bug。
- **只动合成层**(`alpha` / `translationX/Y` / `scale` / `rotation`),不碰 `width`/`height`/`top`/`left`。
- **减动效不做残缺动画**:由系统的动画缩放统一裁决,见第 4 节。

### 2 · Token

| 名称 | 值 | 用途 |
|---|---|---|
| `durFast` | 180ms | 按压反馈、选中态这类即时响应 |
| `durBase` | 280ms | 通用入场 / 状态切换 |
| `durSlow` | 460ms | 页面转场、面板滑入 |
| `durXSlow` | 700ms | 开屏品牌条生长 |
| `durPulse` | 1400ms | 「正在上」呼吸条的一次往返(半程 = 700ms) |
| `stagger` | 40ms | 默认错峰步长(列表项) |
| `staggerTight` | 22ms | 一屏塞得下的网格(周视图课程块) |
| `staggerLoose` | 60ms | 条目少、要逐一看清(权限引导) |
| `headerParallaxPx` | 56px | 周视图表头视差(注意单位是 **px**,直接给 `graphicsLayer`) |
| `easeLinear` | 匀速 | GSAP `ease: "none"`,进度驱动 |
| `easeOut` | `CubicBezierEasing(.215,.61,.355,1)` | GSAP `power2.out` |
| `easeInOut` | `CubicBezierEasing(.645,.045,.355,1)` | GSAP `power2.inOut` |
| `easeExpoOut` | `CubicBezierEasing(.19,1,.22,1)` | GSAP `expo.out`,强调性入场 |
| `easeBackOut` | `CubicBezierEasing(.34,1.56,.64,1)` | GSAP `back.out`,弹窗 / 面板「出现」 |
| `gentleSpring()` | 0.85 / MediumLow | 布局位置、尺寸这类变化 |
| `snappySpring()` | 0.75 / Medium | 选中指示器、位移 |
| `bouncySpring()` | 0.55 / Medium | 手势释放、开关 |

辅助:`tween(durationMs, easing, delayMs)`、`staggerDelay(index, stepMs)`、`fadeScaleIn()` / `fadeScaleOut()`。

### 3 · 时间线:`YohakuTimeline`

对应 `gsap.timeline()` + position 参数。与其把动画串成一堆 `delay()`,不如声明「这一步多长、从哪开始」。

```kotlin
LaunchedEffect(Unit) {
    motionTimeline {
        step(YohakuMotion.durXSlow, "0")    { d -> reveal.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeLinear)) }
        step(YohakuMotion.durSlow, "620")   { d -> sheen.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeInOut)) }
        step(YohakuMotion.durSlow, "880")   { d -> title.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeExpoOut)) }
        step(0, "1900")                     { onExitStart() }

        // 错峰:7 步依次错开 60ms(GSAP 的 stagger)
        stagger(count = 7, stepMs = YohakuMotion.staggerLoose, durationMs = YohakuMotion.durSlow) { i, d ->
            progresses[i].animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeExpoOut))
        }
    }
}
```

`position` 语法与 GSAP 一致:

| 写法 | 含义 |
|---|---|
| `"0"` / `"1200"` | 绝对起点(毫秒) |
| `"+="n` / `"-="n` | 相对上一步**终点**再偏移 |
| `"<"` / `">"` | 上一步的**起点** / **终点**(缺省 `">"`,即依次相接) |
| `"<n"` / `">n"` | 在上一步起点 / 终点基础上再偏移 n 毫秒 |

### 4 · 减动效(等价于 GSAP 的 `prefers-reduced-motion`)

- Compose 的 `animate*AsState` / `Animatable` / `EnterTransition` 会自动读取系统的
  `MotionDurationScale`(设置里「移除动画」= 0),时长随之归零,无需额外判断。
- **`delay()` 不在此列**。所以 `YohakuTimeline.play()` 会读同一个 `MotionDurationScale`,
  把各步的**起点偏移**按同系数压缩,而时长原样交给 `animate*`——两边同系数,缩放后相对时序才不散。
  这样系统关掉动画时,开屏这类「编排」也会瞬时收场,而不是仍卡满 1.9s。

### 5 · 各处动画归属

| 文件 | 动画 |
|---|---|
| `design/YohakuBottomNav.kt` | 标签变色、选中圆点缩放、按压缩放 |
| `design/YohakuButton.kt` | 按压缩放 |
| `design/YohakuChip.kt` | 选中时底 / 边 / 字三色缓动 |
| `design/YohakuIndication.kt` | 自绘按压遮罩(替代涟漪) |
| `design/YohakuDialog.kt` | 入场过冲 + 淡入(`easeBackOut`) |
| `design/YohakuSegmentedControl.kt` | 指示块位移 + 文字变色;`progress` 非空时改由外部(可拖动来源)驱动,跟着手指走 |
| `design/YohakuSwitch.kt` | 轨道变色 + 滑块回弹(`bouncySpring`) |
| `design/EntranceTracker.kt` | 一次性入场守卫:同一个 key 在一次屏幕存活期内只播一次入场(翻页回收后回头不重播) |
| `ui/SplashOverlay.kt` | 开屏时间线(条生长 → 扫光 → 标题 / 副标题 → 收场) |
| `ui/BrandMark.kt` | 品牌条生长与扫光的绘制(受开屏时间线驱动) |
| `ui/timetable/WeekGrid.kt` | 「现在」线缓动、课程块错峰入场(受 `EntranceTracker` 去重)、当前课 accent 条**呼吸**(`durPulse` 无限动画,初值 1f) |
| `ui/timetable/DayList.kt` | 卡片错峰入场(受 `EntranceTracker` 去重)+ 列表增删重排 |
| `ui/timetable/TimetableScreen.kt` | 温度数字滚动、翻页、表头视差;**周 ↔ 日切换的横向共享轴转场** |
| `ui/agenda/CalendarStrip.kt` | 翻周、日期格选中 / 今天变色、月份文字交叉淡化 |
| `ui/agenda/AgendaTimeline.kt` | 条目错峰入场(受 `EntranceTracker` 去重)+ 列表重排 |
| `ui/agenda/AgendaScreen.kt` | 「日程 ↔ 倒计时」用可拖动 pager 承载,分段指示块由 `currentPageOffsetFraction` 驱动 |
| `ui/agenda/CountdownList.kt` | 条目错峰入场 + `animateItem` 增删 / 展开「已结束」、折叠箭头旋转 |
| `ui/courses/CoursesScreen.kt` | 多选进出(勾选圈滑入 / 行底色缓动)、底部操作条滑入 / 滑出 |
| `ui/permissions/PermissionRow.kt` | 状态点缩放 + 文字变色 |
| `ui/onboarding/OnboardingScreen.kt` | 整页 stagger 入场 + CTA 文案切换 |
| `ui/form/CourseFormScreen.kt` | 「按节次 ↔ 自定义时间」交叉淡化 + 轻微上移 |
| `ui/importer/ImportScreen.kt` | 步骤条颜色缓动 + 步骤间横向共享轴转场 |
| `MainActivity.kt` | 导航转场(根标签串行淡化 / 二级页首尾相接滑入滑出)、引导覆盖层出入场 |
| `design/YohakuTopBar.kt` | 返回 ‹ 的按压缩放 |

### 周 ↔ 日切换

与二级页导航同一条铁律:`AnimatedContent` 里两页**整幅**滑入 / 滑出(同 `durSlow` + `easeOut`),
边界线严格重合,任意时刻屏上只可能有一页的像素。顶栏固定不参与转场 —— 切换的是内容,不是页头。

### 触感

触感不在这套 token 里,但它属于「反馈明确」:见 `design/Haptics.kt`。只在**状态确凿改变**处调用
(选日期 / 切页签 / 拨开关 → 轻 tick;进入多选、勾选、破坏性确认 → 实一下),滚动与入场动画一律不给。


导航转场由**系统返回手势驱动**(targetSdk 36 起预测性返回默认开启且无法关闭):手指拖到哪,退场页就停在哪,
松手前随时能退回。所以 `navEnter` / `navExit` / `navPopEnter` / `navPopExit`(见 `MainActivity.kt`)只允许用
**可被拖动定位**的补间(slide + tween),别掺 `Animatable` 或动画协程这类写死的驱动 —— 否则返回手势会退化成
「先放手、再播一段动画」。

**铁律:一次转场只让一层在动。** `NavHost` 把退场页与入场页叠在同一个容器里,而这两层**谁画在上面由库决定、
并不保证** —— 预测性返回下尤其如此([Google Issue 345993681](https://issuetracker.google.com/issues/345993681):
手势进行中,被弹出的那一页有时会被画到另一页后面)。只要两层同时平移、或同时改 alpha,重叠的那一帧就会把**错的
那一页**画在上面,看起来就是「两页同时出现」——这正是此前几版一直没除根的「返回残影」。所以:二级页**推进时只有
入场页在动**(退场页原地不动,当背景),**返回时只有退场页在动**(被压住的页原地不动、原地露出来,用
`EnterTransition.None`)。这样无论库把哪一层画在上面,任意时刻屏上都只有一页的像素在变化。

由此两条禁令:**别让两页同时平移**(2.6.0 的分层视差、2.6.1 的「首尾相接」都出在这一条上),**别让两页同时改
alpha**(双重曝光)。根标签之间是唯一例外,但用的是**串行**淡化:旧页先淡出、新页等它走完再淡入,任意时刻只有
一页在变 alpha。

---

## 官网

`netlify/site/assets/motion.js` 用真正的 GSAP,全部动效装在 `gsap.matchMedia()` 里,
`prefers-reduced-motion: reduce` 时**一行都不注册**(保持静态页)。包含:

- Hero 入场时间线;
- 页头 1px 滚动进度条(`.scroll-progress`,由 JS 插入);
- 分区 `ScrollTrigger.batch()` 错峰渐显(`[data-reveal]`);
- Hero 手机视差、截图区钉住 + scrub(仅桌面);
- FAQ 展开淡入、锚点平滑滚动(ScrollToPlugin)。

降级:GSAP 未加载 → 直接返回、不改页面;`<head>` 内联脚本加的 `anim-ready` 有 2.5s 兜底定时器,
`motion.js` 没跑起来就撤掉,内容不会留白。

改动官网动效后记得按 `index.html` 尾部注释 **bump `?v=`**(`/assets/*` 是一年 immutable 缓存)。
