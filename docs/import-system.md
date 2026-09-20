# 教务导入系统

> 面向开发者的说明。产品介绍见 [README](../README.md)。

导入流程分三步:选择学校与适配方案 → 在应用内 WebView 登录教务系统并执行适配脚本 → 确认导入(课程 / 作息 / 开学日期)。脚本运行在页面里,通过 JS 桥把结果交给 App。

## 数据流

```
warehouse/                 # 上游适配仓库快照(保留 YAML,便于比对与重新生成)
  index/root_index.yaml
  resources/<SCHOOL>/{adapters.yaml, <script>.js}
        │  node tools/yaml2json.mjs   → 预编译为下方 JSON,App 运行时不解析 YAML
        ▼
assets/warehouse/
  index.json       # 学校索引
  adapters.json    # 适配器配置
  resources/<SCHOOL>/<script>.js   # 适配脚本(注入 WebView 执行)
```

## 适配脚本契约

脚本注入即自执行,通过 JS 桥与 App 交互:

- `AndroidBridge.showToast` / `AndroidBridge.notifyTaskCompletion`
- `AndroidBridgePromise.showAlert` / `showPrompt` / `showSingleSelection` / `saveImportedCourses` / `savePresetTimeSlots` / `saveCourseConfig`(兼容别名 `shiguangBridge*`)

实现要点(`ui/importer/ImportBridge.kt`):

- 垫片把每个方法的实参**补齐到固定个数**再追加 callbackId,兼容适配器 3 参 / 4 参的不同写法(形参错位会让 Promise 挂到超时)。
- 调用注入方法**必须把注入对象本身作为 `this`**(`apply(AndroidBridgeNative, args)`)。写成 `apply(null, …)` 时非严格模式下 `this` 会变成全局对象,WebView 会直接抛 `Java bridge method can't be invoked on a non-injected object`,适配器只能拿到 `null` 并误判成「用户取消」。
- 直连失败时垫片会退回**网页控制台通道**(`console.log('__SHIGUANG_BRIDGE__' + JSON)`)把调用送到原生,由 `onConsoleMessage` 解析后再用 `evaluateJavascript` 回填;这条通道不依赖注入对象是否有效。
- `showPrompt` 的校验函数在页面全局作用域按名执行,因此**适配脚本必须原样执行、不能被 IIFE 包裹**(否则顶层 `function` 声明进不了全局,校验永远不通过)。
- 页面每次加载都会重新注册桥对象:SSO 页面(深信服 aTrust / CAS)可能在同一个 JS 上下文里重写文档,旧注入对象会失效。

## 更新适配仓库

上游适配仓库(GitHub `XingHeYuZhuan/shiguang_warehouse`,国内镜像 `gitee.com/XingHeYuZhuan-gh/shiguang_warehouse`):

```bash
git clone --depth 1 https://gitee.com/XingHeYuZhuan-gh/shiguang_warehouse /tmp/shiguang
cp -r /tmp/shiguang/resources/* warehouse/resources/     # 适配脚本 + adapters.yaml
cp /tmp/shiguang/index/root_index.yaml warehouse/index/
cp /tmp/shiguang/README.md /tmp/shiguang/LICENSE warehouse/
node tools/yaml2json.mjs        # 重新生成 assets/warehouse/{index,adapters}.json
node tools/build-netlify.mjs    # 打包 netlify/static/warehouse/bundle.json
node tools/verify-warehouse.mjs # 校验数据自洽(每校必有适配器 / 脚本存在 / id 唯一 / 无未引用脚本)
```

`assets/warehouse/resources/**` 只需放 `.js`(运行时只读脚本);YAML 只留在 `warehouse/` 源目录用于比对。

注意:

- 上游索引已升级到**协议 v2**,但 `root_index.yaml` / `adapters.yaml` 的字段与本项目一致(`id`/`name`/`initial`/`resource_folder` 与 `adapter_id`/`adapter_name`/`asset_js_path`/`import_url`/`category`/`maintainer`/`description`),`tools/yaml2json.mjs` 可直接预编译,App 端无需改动。
- 各校 YAML 的**列表项缩进不一致**(有的写在第 0 列),预编译器已按任意缩进解析——早期实现会因此丢掉整所学校的适配器。
- App 端「设置 → 适配器同步」可拉取线上 bundle 覆盖内置数据,无需等待发版;失败会自动回退内置数据。
