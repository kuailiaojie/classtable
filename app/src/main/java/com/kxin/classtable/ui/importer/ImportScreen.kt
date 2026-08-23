package com.kxin.classtable.ui.importer

import android.os.Handler
import android.os.Looper
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
    val schools = remember { WarehouseIndex.loadSchools(context) }
    val adapters = remember { WarehouseIndex.loadAdapters(context) }
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
                        )
                    }
                }
            },
            confirmButton = {
                val ok = !urlRequired || customUrl.isNotBlank()
                Text(
                    text = "确定导入",
                    style = YohakuType.copy14,
                    color = if (ok) colors.accent else colors.neutral5,
                    modifier = Modifier
                        .clickable(enabled = ok) {
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
        when (step) {
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
                val importUrl = adapter.importUrl.ifBlank { customUrl.trim() }
                StepLogin(
                    adapter = adapter,
                    importUrl = importUrl,
                    holder = holder,
                    bridge = bridge,
                    onReload = { holder.webView.loadUrl(importUrl) },
                    onRun = {
                        val script = WarehouseIndex.readScript(context, adapter.folder, adapter.jsPath)
                        if (script == null) {
                            viewModel.onToast("适配脚本缺失: ${adapter.jsPath}")
                        } else {
                            holder.webView.evaluateJavascript(ImportBridge.SHIM_JS + "\n" + script, null)
                        }
                    },
                )
            }
            3 -> StepConfirm(
                courses = parsedCourses,
                onImport = { viewModel.importAll() },
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
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            Text(text = adapter.adapterName, style = YohakuType.title20, color = colors.neutral10)
            Text(
                text = "登录后进入课表页面,再点「执行导入」",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = importUrl,
                style = YohakuType.timeMono,
                color = colors.neutral7,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "账号密码仅在本机 WebView 会话中使用,不会上传到任何服务器。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            AndroidView(
                factory = { ctx ->
                    holder.webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webViewClient = WebViewClient()
                        addJavascriptInterface(bridge, "AndroidBridgeNative")
                        loadUrl(importUrl)
                    }
                    holder.webView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "重新加载",
                style = YohakuType.copy13,
                color = colors.neutral7,
                modifier = Modifier
                    .clickable(onClick = onReload)
                    .padding(end = 24.dp),
            )
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
    onImport: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            Text(
                text = if (courses.isEmpty()) "未解析到课程,请返回重试" else "解析到 ${courses.size} 门课,请确认",
                style = YohakuType.copy13,
                color = if (courses.isEmpty()) colors.error else colors.neutral7,
            )
            Text(
                text = "若脚本提供作息时间/开学日期,已自动应用。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = YohakuDimens.screenPadding),
        ) {
            items(courses, key = { it.id }) { course ->
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = Schedule.periodRange(startPeriod = course.startPeriod, endPeriod = course.endPeriod),
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
            YohakuButton(
                text = "导入",
                onClick = onImport,
                enabled = courses.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun weekSummary(course: Course): String = when (course.weekType) {
    WeekType.EVERY_WEEK -> "每周"
    WeekType.ODD_WEEK -> "单周"
    WeekType.EVEN_WEEK -> "双周"
    WeekType.CUSTOM -> "第${course.weekStart}-${course.weekEnd}周"
}
