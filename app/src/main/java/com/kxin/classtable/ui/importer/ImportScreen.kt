package com.kxin.classtable.ui.importer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.data.importer.AdapterEntry
import com.kxin.classtable.data.importer.SchoolEntry
import com.kxin.classtable.data.importer.WarehouseIndex
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import com.kxin.classtable.ui.navigateToTab
import com.kxin.classtable.ui.settings.SettingsHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.LocalDate
import java.util.Locale

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
    // 读取与解析(index.json / adapters.json)放到 IO 线程:在 remember 里同步做会在进入导入页时卡主线程。
    var indexReload by remember { mutableIntStateOf(0) }
    var schools by remember { mutableStateOf<List<SchoolEntry>>(emptyList()) }
    var adapters by remember { mutableStateOf<List<AdapterEntry>>(emptyList()) }
    var indexError by remember { mutableStateOf(false) }
    var indexLoading by remember { mutableStateOf(true) }
    LaunchedEffect(indexReload) {
        indexLoading = true
        val (schoolsResult, adaptersResult) = withContext(Dispatchers.IO) {
            WarehouseIndex.loadSchools(context) to WarehouseIndex.loadAdapters(context)
        }
        schools = schoolsResult.getOrDefault(emptyList())
        adapters = adaptersResult.getOrDefault(emptyList())
        indexError = schoolsResult.isFailure || adaptersResult.isFailure || schools.isEmpty()
        indexLoading = false
    }
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
    val currentPeriodTimes by viewModel.currentPeriodTimes.collectAsStateWithLifecycle()
    // 脚本能给作息就**强制覆盖**当前作息(用户要求:适配器自带的时间就是这所学校的真相),
    // 因此在确认页不再提供「要不要应用」的勾选,只把它写在明面上告知。
    // 课程的时间点在写入时会被钉住,所以覆盖作息不会把已导入课程的时刻带跑。
    val previewPeriods = remember(detectedPeriods, currentPeriodTimes) {
        Schedule.parsePeriods(detectedPeriods ?: currentPeriodTimes)
    }

    val holder = remember { WebViewHolder() }
    val bridge = remember {
        ImportBridge(
            // 弹窗(新窗口)里也会跑适配脚本,回调要落在用户当前看到的那个 WebView 上
            webViewProvider = { holder.active },
            mainHandler = Handler(Looper.getMainLooper()),
            onToast = { viewModel.onToast(it) },
            onCoursesJson = { viewModel.onCoursesJson(it) },
            onPresetTimeSlotsJson = { viewModel.onPresetTimeSlots(it) },
            onCourseConfigJson = { viewModel.onCourseConfig(it) },
            onDone = { viewModel.onDone() },
        )
    }

    // 离开导入页(或组件销毁)时销毁 WebView 并解绑桥:此前只清弹窗,主 WebView 会一直
    // 持有 Activity context 直到进程结束,且销毁后桥仍指着死页面。
    DisposableEffect(Unit) {
        onDispose {
            bridge.bindWebView(null)
            holder.destroy()
        }
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
        YohakuDialog(
            onDismissRequest = { pendingSchool = null },
            title = school.name,
            actions = {
                YohakuDialogAction(text = "取消", onClick = { pendingSchool = null })
            },
            content = {
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
        )
    }

    // 弹窗 2:适配器详情(描述/作者/提示),通用教务需在此填教务网址,再确定导入
    detailAdapter?.let { adapter ->
        val urlRequired = adapter.needsManualUrl
        YohakuDialog(
            onDismissRequest = { detailAdapter = null },
            title = adapter.adapterName,
            actions = {
                val normalizedUrl = normalizeImportUrl(customUrl)
                val ok = !urlRequired || isValidImportUrl(normalizedUrl)
                YohakuDialogAction(text = "取消", onClick = { detailAdapter = null })
                YohakuDialogAction(
                    text = "确定导入",
                    accent = true,
                    enabled = ok,
                    onClick = {
                        if (urlRequired) customUrl = normalizedUrl
                        selectedAdapter = adapter
                        detailAdapter = null
                        step = 2
                    },
                )
            },
            content = {
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
        // 步骤进度:三段条按当前步填充(颜色缓动)。比一行纯文字更早看出「到哪一步、还剩几步」。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (1..3).forEach { i ->
                val reached = i <= step
                val barColor by animateColorAsState(
                    targetValue = if (reached) colors.accent else colors.neutral3,
                    animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
                    label = "stepBar",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(barColor, RoundedCornerShape(2.dp)),
                )
            }
            Text(
                text = "第 $step / 3 步",
                style = YohakuType.label12,
                color = colors.neutral7,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        if (indexLoading) {
            // 索引在后台加载:给一行轻提示,而不是留白(留白看起来像页面坏了)
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "载入中…", style = YohakuType.copy14, color = colors.neutral6)
            }
        } else if (indexError) {
            IndexErrorPanel(
                onSync = { nav.navigate(SettingsHub.IMPORT.route) },
                onRetry = { indexReload++ },
            )
        } else AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                // 步骤是同一条流程上的前后移动:横向共享轴,退场整幅滑出、入场整幅滑入
                val forward = targetState > initialState
                val spec = YohakuMotion.tween<IntOffset>(YohakuMotion.durSlow, YohakuMotion.easeOut)
                slideInHorizontally(animationSpec = spec) { if (forward) it else -it } togetherWith
                    slideOutHorizontally(animationSpec = spec) { if (forward) -it else it }
            },
            label = "importStep",
        ) { s -> when (s) {
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
            )
            2 -> selectedAdapter?.let { adapter ->
                val importUrl = adapter.importUrl.ifBlank { normalizeImportUrl(customUrl) }
                StepLogin(
                    adapter = adapter,
                    importUrl = importUrl,
                    holder = holder,
                    bridge = bridge,
                    onReload = {
                        // 刷新当前页(含新窗口页面);尚未加载出 URL 时回到入口地址
                        val active = holder.active
                        if (active.url.isNullOrBlank()) {
                            active.loadUrl(importUrl)
                        } else {
                            active.reload()
                        }
                    },
                    onRun = {
                        val script = WarehouseIndex.readScript(context, adapter.folder, adapter.jsPath)
                        if (script == null) {
                            viewModel.onToast("适配脚本缺失: ${adapter.jsPath},请到设置页「适配器同步」更新")
                        } else {
                            // 目标取「当前可见页面」:课表在 window.open 出来的新窗口里时,必须注入到那里。
                            // 官方同样如此(popupWebView ?: webView),并且**先把桥绑定到这一页**
                            // (bindWebView)再注入脚本 —— 弹窗与主页面是两个独立的 JS 文档,
                            // 桥的回调/resolve 必须回到脚本所在的文档,否则 Promise 永远等不到结果。
                            val target = holder.active
                            // 注入脚本前先刷新桥对象:SSO 页面可能已经在本上下文里重写过文档,
                            // 旧对象会被判为「非注入」,导致脚本里所有桥调用失败。
                            target.installJavascriptBridge(bridge)
                            bridge.bindWebView(target)
                            // 垫片 + 适配脚本原样注入(脚本不能被 IIFE 包裹,否则它的顶层
                            // 校验函数等声明进不了全局作用域,showPrompt 按名调用会 not defined)
                            target.evaluateJavascript(
                                ImportBridge.buildInjection(script),
                            ) { }
                        }
                    },
                )
            }
            3 -> StepConfirm(
                courses = parsedCourses,
                periods = previewPeriods,
                detectedPeriods = detectedPeriods,
                detectedSemester = detectedSemester,
                onImport = { viewModel.importAll(applyDetectedConfig = true) },
                onApplyDetectedOnly = { viewModel.applyDetectedOnly() },
                onSkip = { nav.popBackStack() },
            )
        } }
    }
}
