package com.kxin.classtable.ui.importer

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.data.importer.AdapterEntry
import com.kxin.classtable.data.importer.SchoolEntry
import com.kxin.classtable.data.importer.WarehouseIndex
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import com.kxin.classtable.ui.navigateToTab
import kotlinx.coroutines.launch
import java.net.URL
import java.time.LocalDate

/**
 * 教务导入(3 步):选学校(首字母索引,已隐藏开发者自检工具)
 * → 选适配器并阅读说明(描述/作者/提示,通用教务在此填网址)→ 确认导入
 * → WebView 登录 + 注入适配脚本 → 确认导入。
 * 另提供「手动表格导入」「AI 图片导入」备选入口。
 */
@Composable
fun ImportScreen(
    nav: NavHostController,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current

    // 索引加载:缓存优先(设置页「适配器同步」的产物),回退内置 assets。
    // 两者皆失败时不再静默返回空列表,而是给出可操作的错误面板。
    var indexReload by remember { mutableIntStateOf(0) }
    val schoolsResult = remember(indexReload) { WarehouseIndex.loadSchools(context) }
    val adaptersResult = remember(indexReload) { WarehouseIndex.loadAdapters(context) }
    val schools = schoolsResult.getOrDefault(emptyList())
    val adapters = adaptersResult.getOrDefault(emptyList())
    val indexError = schoolsResult.isFailure || adaptersResult.isFailure || schools.isEmpty()
    val visibleSchools = remember(schools) {
        schools.filter { it.folder !in WarehouseIndex.SELF_CHECK_FOLDERS }
    }

    var step by rememberSaveable { mutableIntStateOf(1) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSchool by rememberSaveable { mutableStateOf<SchoolEntry?>(null) }
    var selectedAdapter by rememberSaveable { mutableStateOf<AdapterEntry?>(null) }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var pendingSchool by remember { mutableStateOf<SchoolEntry?>(null) }
    var detailAdapter by remember { mutableStateOf<AdapterEntry?>(null) }

    val parsedCourses by viewModel.parsedCourses.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toast.collectAsStateWithLifecycle()
    val detectedPeriods by viewModel.detectedPeriods.collectAsStateWithLifecycle()
    val detectedSemester by viewModel.detectedSemester.collectAsStateWithLifecycle()
    // 脚本识别到的作息/学期默认应用,但由用户在这里确认(之前是静默覆盖本地作息)
    var applyDetected by rememberSaveable { mutableStateOf(true) }

    val holder = remember { WebViewHolder() }
    val bridge = remember {
        ImportBridge(
            webViewProvider = { holder.webView },
            mainHandler = Handler(Looper.getMainLooper()),
            onToast = { viewModel.onToast(it) },
            onCoursesJson = { viewModel.onCoursesJson(it) },
            onPresetTimeSlotsJson = { viewModel.onPresetTimeSlots(it) },
            onCourseConfigJson = { viewModel.onCourseConfig(it) },
            onDone = { viewModel.onDone() },
        )
    }

    LaunchedEffect(done) {
        if (done) {
            step = 3
            viewModel.consumeDone()
        }
    }
    LaunchedEffect(imported) { if (imported) nav.popBackStack() }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // 弹窗 1:选适配器(一校多适配器 / 通用教务都从这里进入)
    pendingSchool?.let { school ->
        val options = adapters.filter { it.folder == school.folder }
        AlertDialog(
            onDismissRequest = { pendingSchool = null },
            title = { Text(school.name, style = YohakuType.title20) },
            text = {
                Column {
                    Text(
                        text = if (options.size > 1) "该校有 ${options.size} 个导入方案,请选择" else "选择导入方案",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                    Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                    options.forEach { option ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSchool = school
                                    detailAdapter = option
                                    pendingSchool = null
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(text = option.adapterName, style = YohakuType.copy15, color = colors.neutral9)
                            Row {
                                Text(
                                    text = "作者 ${option.maintainer.ifBlank { "未知" }}",
                                    style = YohakuType.label12,
                                    color = colors.neutral7,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = WarehouseIndex.categoryLabel(option.category),
                                    style = YohakuType.label12,
                                    color = colors.neutral6,
                                )
                            }
                            if (option.description.isNotBlank()) {
                                Text(
                                    text = option.description,
                                    style = YohakuType.label12,
                                    color = colors.neutral6,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.neutral3),
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    // 弹窗 2:适配器详情(描述/作者/提示),通用教务需在此填教务网址,再确定导入
    detailAdapter?.let { adapter ->
        val urlRequired = adapter.needsManualUrl
        AlertDialog(
            onDismissRequest = { detailAdapter = null },
            title = { Text(adapter.adapterName, style = YohakuType.title20) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "导入前请先阅读该适配器的说明:",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (adapter.description.isNotBlank()) {
                        Text(text = adapter.description, style = YohakuType.copy14, color = colors.neutral9)
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Text(
                        text = "作者: ${adapter.maintainer.ifBlank { "未知" }} · ${WarehouseIndex.categoryLabel(adapter.category)}",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                    if (adapter.importUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "入口: ${adapter.importUrl}",
                            style = YohakuType.timeMono,
                            color = colors.neutral7,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (urlRequired) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "该适配器为通用教务方案,请填写你学校教务系统的网址:",
                            style = YohakuType.label12,
                            color = colors.error,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        YohakuTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            placeholder = "https://jw.xxx.edu.cn",
                            isError = customUrl.isNotBlank() &&
                                !isValidImportUrl(normalizeImportUrl(customUrl)),
                        )
                    }
                }
            },
            confirmButton = {
                val normalizedUrl = normalizeImportUrl(customUrl)
                val ok = !urlRequired || isValidImportUrl(normalizedUrl)
                Text(
                    text = "确定导入",
                    style = YohakuType.copy14,
                    color = if (ok) colors.accent else colors.neutral5,
                    modifier = Modifier
                        .clickable(enabled = ok) {
                            if (urlRequired) customUrl = normalizedUrl
                            selectedAdapter = adapter
                            detailAdapter = null
                            step = 2
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "取消",
                    style = YohakuType.copy14,
                    color = colors.neutral7,
                    modifier = Modifier
                        .clickable { detailAdapter = null }
                        .padding(8.dp),
                )
            },
        )
    }

    // 系统返回:第 2/3 步回到上一步;第 1 步退出导入页(弹出失败则兜底回周视图)
    if (step > 1) {
        BackHandler { step-- }
    } else {
        BackHandler {
            if (!nav.popBackStack()) nav.navigateToTab("week")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(
            title = "教务导入",
            onBack = {
                if (step > 1) {
                    step--
                } else if (!nav.popBackStack()) {
                    nav.navigateToTab("week")
                }
            },
        )
        Text(
            text = "第 $step 步 / 共 3 步",
            style = YohakuType.label12,
            color = colors.neutral7,
            modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding),
        )
        if (indexError) {
            IndexErrorPanel(
                onSync = { nav.navigate("adapter_sync") },
                onRetry = { indexReload++ },
            )
        } else when (step) {
            1 -> StepSchool(
                schools = visibleSchools,
                adapters = adapters,
                query = query,
                onQueryChange = { query = it },
                onSelect = { school ->
                    val options = adapters.filter { it.folder == school.folder }
                    if (options.size == 1) {
                        selectedSchool = school
                        detailAdapter = options[0]
                    } else {
                        pendingSchool = school
                    }
                },
                onManual = { nav.navigate("import_manual") },
                onAi = { nav.navigate("import_ai") },
            )
            2 -> selectedAdapter?.let { adapter ->
                val importUrl = adapter.importUrl.ifBlank { normalizeImportUrl(customUrl) }
                StepLogin(
                    adapter = adapter,
                    importUrl = importUrl,
                    holder = holder,
                    bridge = bridge,
                    onReload = {
                        // 刷新当前页;尚未加载出 URL 时回到入口地址
                        if (holder.webView.url.isNullOrBlank()) {
                            holder.webView.loadUrl(importUrl)
                        } else {
                            holder.webView.reload()
                        }
                    },
                    onRun = {
                        val script = WarehouseIndex.readScript(context, adapter.folder, adapter.jsPath)
                        if (script == null) {
                            viewModel.onToast("适配脚本缺失: ${adapter.jsPath},请到设置页「适配器同步」更新")
                        } else {
                            // 垫片 + 包裹后的脚本一次注入:同步异常经桥上报,不再无声失败
                            holder.webView.evaluateJavascript(
                                ImportBridge.SHIM_JS + "\n" + ImportBridge.wrapScript(script),
                            ) { }
                        }
                    },
                )
            }
            3 -> StepConfirm(
                courses = parsedCourses,
                detectedPeriods = detectedPeriods,
                detectedSemester = detectedSemester,
                applyDetected = applyDetected,
                onApplyDetectedChange = { applyDetected = it },
                onImport = { viewModel.importAll(applyDetected) },
                onApplyDetectedOnly = { viewModel.applyDetectedOnly() },
                onSkip = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun StepSchool(
    schools: List<SchoolEntry>,
    adapters: List<AdapterEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SchoolEntry) -> Unit,
    onManual: () -> Unit,
    onAi: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val filtered = schools.filter {
        query.isBlank() || it.name.contains(query) || it.id.contains(query.uppercase())
    }
    val groups = filtered
        .sortedBy { it.initial.ifBlank { "Z#" } }
        .groupBy { school ->
            val c = school.initial.firstOrNull()?.uppercaseChar()
            if (c != null && c in 'A'..'Z') c else '#'
        }
    val letters = groups.keys.sorted()
    val flat = letters.flatMap { letter ->
        buildList {
            add(letter to null)
            groups[letter]!!.forEach { add(letter to it) }
        }
    }
    val letterIndex = letters.associateWith { letter -> flat.indexOfFirst { it.first == letter } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                YohakuTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "搜索学校(名称 / 缩写)",
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            }
            if (query.isBlank()) {
                Row(
                    modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "手动表格导入",
                        style = YohakuType.copy13,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable(onClick = onManual)
                            .padding(vertical = 4.dp)
                            .padding(end = 20.dp),
                    )
                    Text(
                        text = "AI 图片导入",
                        style = YohakuType.copy13,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable(onClick = onAi)
                            .padding(vertical = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = YohakuDimens.screenPadding),
            ) {
                items(flat, key = { it.second?.id ?: "h-${it.first}" }) { (letter, school) ->
                    if (school == null) {
                        Text(
                            text = letter.toString(),
                            style = YohakuType.label12,
                            color = colors.neutral6,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    } else {
                        val schoolAdapters = adapters.filter { it.folder == school.folder }
                        val subtitle = when {
                            school.folder in WarehouseIndex.GENERIC_JIAOWU_FOLDERS ->
                                "通用教务 · 需填写教务网址"
                            schoolAdapters.size > 1 -> "${schoolAdapters.size} 个导入方案"
                            schoolAdapters.size == 1 -> schoolAdapters[0].adapterName
                            else -> ""
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(school) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = school.name, style = YohakuType.copy15, color = colors.neutral9)
                                if (subtitle.isNotEmpty()) {
                                    Text(
                                        text = subtitle,
                                        style = YohakuType.label12,
                                        color = if (school.folder in WarehouseIndex.GENERIC_JIAOWU_FOLDERS) {
                                            colors.accent
                                        } else {
                                            colors.neutral7
                                        },
                                    )
                                }
                            }
                            Text(text = "›", style = YohakuType.copy15, color = colors.neutral6)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.neutral3),
                        )
                    }
                }
            }
        }
        if (query.isBlank() && letters.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                letters.forEach { letter ->
                    Text(
                        text = letter.toString(),
                        style = YohakuType.label12,
                        color = colors.neutral7,
                        modifier = Modifier
                            .clickable { scope.launch { listState.scrollToItem(letterIndex[letter] ?: 0) } }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepLogin(
    adapter: AdapterEntry,
    importUrl: String,
    holder: WebViewHolder,
    bridge: ImportBridge,
    onReload: () -> Unit,
    onRun: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 压缩头部:适配器名 + 网址一行,提示一行,把更多空间留给网页
        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = adapter.adapterName,
                    style = YohakuType.copy15,
                    color = colors.neutral9,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = importUrl,
                    style = YohakuType.timeMono,
                    color = colors.neutral7,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "登录后进入课表页,再点「执行导入」· 账号密码仅本机 WebView 使用",
                style = YohakuType.label12,
                color = colors.neutral7,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            AndroidView(
                factory = { ctx ->
                    // 同一适配器复用 WebView(返回上一步再进入不丢登录态),换适配器才重建。
                    // adapterKey 与 webView 始终成对赋值,故用 key 判定即可。
                    if (holder.adapterKey == adapter.adapterId) {
                        holder.webView
                    } else {
                        val created = WebView(ctx)
                        created.settings.javaScriptEnabled = true
                        created.settings.domStorageEnabled = true
                        created.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // 桌面 UA:适配脚本按桌面 DOM 编写,移动 UA 会拿到移动版页面
                        created.settings.userAgentString = ImportBridge.DESKTOP_UA
                        created.webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                loadError = null
                                // 页面一开始就注入垫片,保证适配脚本执行前 AndroidBridge* 已就绪
                                view?.evaluateJavascript(ImportBridge.SHIM_JS, null)
                            }

                            @Suppress("DEPRECATION")
                            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                super.onReceivedError(view, errorCode, description, failingUrl)
                                loadError = friendlyLoadError(errorCode, description)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    loadError = friendlyLoadError(error?.errorCode ?: -1, error?.description?.toString())
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (request?.isForMainFrame == true) {
                                    loadError = "服务器返回错误 ${errorResponse?.statusCode ?: "未知"}"
                                }
                            }
                        }
                        created.addJavascriptInterface(bridge, "AndroidBridgeNative")
                        created.loadUrl(importUrl)
                        holder.webView = created
                        holder.adapterKey = adapter.adapterId
                        created
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 加载失败覆盖层:白屏时给出原因,而不是无声空白
            loadError?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.paper),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "页面加载失败",
                        style = YohakuType.copy15,
                        color = colors.error,
                    )
                    Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                    Text(
                        text = message,
                        style = YohakuType.label12,
                        color = colors.neutral7,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                    Text(
                        text = "请检查网络或网址,点下方「刷新」重试",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹",
                style = YohakuType.title20,
                color = if (canGoBack) colors.neutral9 else colors.neutral5,
                modifier = Modifier
                    .clickable(enabled = canGoBack) { holder.webView.goBack() }
                    .padding(horizontal = 6.dp),
            )
            Text(
                text = "›",
                style = YohakuType.title20,
                color = if (canGoForward) colors.neutral9 else colors.neutral5,
                modifier = Modifier
                    .clickable(enabled = canGoForward) { holder.webView.goForward() }
                    .padding(horizontal = 6.dp),
            )
            Text(
                text = "刷新",
                style = YohakuType.copy13,
                color = colors.neutral9,
                modifier = Modifier
                    .clickable(onClick = onReload)
                    .padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
            YohakuButton(
                text = "执行导入",
                onClick = onRun,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StepConfirm(
    courses: List<Course>,
    detectedPeriods: String?,
    detectedSemester: Pair<Int, Long>?,
    applyDetected: Boolean,
    onApplyDetectedChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onApplyDetectedOnly: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val hasDetected = detectedPeriods != null || detectedSemester != null
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            Text(
                text = if (courses.isEmpty()) "未解析到课程,请返回重试" else "解析到 ${courses.size} 门课,请确认",
                style = YohakuType.copy13,
                color = if (courses.isEmpty()) colors.error else colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            if (hasDetected) {
                // 脚本给的作息/学期在这里显式确认,不再静默覆盖用户已设好的作息
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onApplyDetectedChange(!applyDetected) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = "${if (applyDetected) "☑" else "☐"} 一并应用脚本识别到的作息/学期",
                        style = YohakuType.copy13,
                        color = colors.accent,
                    )
                    detectedPeriods?.let { spec ->
                        val periods = Schedule.parsePeriods(spec)
                        Text(
                            text = "作息:${periods.size} 节 · " +
                                periods.take(2).joinToString(" / ") {
                                    Schedule.timeRangeText(it.start, it.end)
                                } +
                                if (periods.size > 2) " …" else "",
                            style = YohakuType.label12,
                            color = colors.neutral7,
                        )
                    }
                    detectedSemester?.let { (weeks, startDay) ->
                        val start = if (startDay > 0L) {
                            val d = LocalDate.ofEpochDay(startDay)
                            "${d.year}/${d.monthValue}/${d.dayOfMonth}"
                        } else {
                            "脚本未提供"
                        }
                        Text(
                            text = "学期:开学 $start · 共 $weeks 周",
                            style = YohakuType.label12,
                            color = colors.neutral7,
                        )
                    }
                }
            } else {
                Text(
                    text = "脚本未提供作息时间与开学日期。",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = YohakuDimens.screenPadding),
        ) {
            items(courses, key = { it.id }) { course ->
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = Schedule.courseTimeText(course),
                        style = YohakuType.timeMono,
                        color = colors.neutral7,
                        modifier = Modifier.width(100.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = course.name, style = YohakuType.copy14, color = colors.neutral10)
                        val meta = listOf(
                            Course.weekdaysText(course),
                            course.teacher,
                            course.location,
                            weekSummary(course),
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                        Text(text = meta, style = YohakuType.label12, color = colors.neutral7)
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "跳过",
                style = YohakuType.copy13,
                color = colors.neutral7,
                modifier = Modifier
                    .clickable(onClick = onSkip)
                    .padding(end = 24.dp),
            )
            if (courses.isEmpty() && hasDetected) {
                // 没有课程但识别到了作息/学期:仍然允许把这段时间表存下来
                YohakuButton(
                    text = "应用作息/学期",
                    onClick = onApplyDetectedOnly,
                    modifier = Modifier.weight(1f),
                )
            } else {
                YohakuButton(
                    text = "导入",
                    onClick = onImport,
                    enabled = courses.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun weekSummary(course: Course): String = when (course.weekType) {
    WeekType.EVERY_WEEK -> "每周"
    WeekType.ODD_WEEK -> "单周"
    WeekType.EVEN_WEEK -> "双周"
    WeekType.CUSTOM -> "第${course.weekStart}-${course.weekEnd}周"
}

/** WebView 错误码 → 用户可读的中文原因(避免白屏无提示)。 */
private fun friendlyLoadError(errorCode: Int, raw: String?): String = when (errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP -> "无法解析域名,请确认教务网址是否正确"
    WebViewClient.ERROR_CONNECT -> "无法连接到服务器,教务系统可能未开放外网访问"
    WebViewClient.ERROR_TIMEOUT -> "连接超时,请检查网络后重试"
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "SSL 证书校验失败,该站点证书可能不受信任"
    // WebViewClient.ERROR_CLEARTEXT_NOT_PERMITTED(API 26 起,值为 -29,SDK 无公开常量)
    -29 -> "系统禁止访问 http 明文页面,请使用 https 地址或联系开发者放行"
    WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "不支持的网址协议"
    WebViewClient.ERROR_BAD_URL -> "网址格式不正确"
    else -> raw?.takeIf { it.isNotBlank() } ?: "未知错误(错误码 $errorCode)"
}

/** 补全协议:用户常直接填 jw.xxx.edu.cn,统一补 https://。 */
private fun normalizeImportUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

/** 仅校验能否解析出主机名,避免明显无效的网址进入 WebView。 */
private fun isValidImportUrl(url: String): Boolean {
    if (url.isBlank()) return false
    return !runCatching { URL(url).host }.getOrNull().isNullOrBlank()
}

/** 适配器索引不可用(缓存损坏且内置缺失)时的可操作错误面板,替代此前的静默空列表。 */
@Composable
private fun IndexErrorPanel(onSync: () -> Unit, onRetry: () -> Unit) {
    val colors = LocalYohakuColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = YohakuDimens.screenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "适配器数据不可用", style = YohakuType.copy15, color = colors.error)
        Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
        Text(
            text = "未能读取适配器索引。可同步云端适配器,或重试使用内置数据。",
            style = YohakuType.label12,
            color = colors.neutral7,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
        YohakuButton(
            text = "去同步适配器",
            onClick = onSync,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
        Text(
            text = "重试",
            style = YohakuType.copy13,
            color = colors.accent,
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(8.dp),
        )
    }
}
