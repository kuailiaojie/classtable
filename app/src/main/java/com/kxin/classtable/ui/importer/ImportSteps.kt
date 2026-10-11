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

@Composable
internal fun StepSchool(
    schools: List<SchoolEntry>,
    adapters: List<AdapterEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SchoolEntry) -> Unit,
) {
    val colors = LocalYohakuColors.current
    // 搜索是逐键触发的重活:全部派生值按 (schools, query) 缓存,只在输入变化时重算一次,
    // 而不是每次重组都重跑 filter / sort / groupBy。
    val filtered = remember(schools, query) {
        schools.filter {
            query.isBlank() || it.name.contains(query) || it.id.contains(query.uppercase())
        }
    }
    val groups = remember(filtered) {
        filtered
            .sortedBy { it.initial.ifBlank { "Z#" } }
            .groupBy { school ->
                val c = school.initial.firstOrNull()?.uppercaseChar()
                if (c != null && c in 'A'..'Z') c else '#'
            }
    }
    val letters = remember(groups) { groups.keys.sorted() }
    val flat = remember(groups, letters) {
        letters.flatMap { letter ->
            buildList {
                add(letter to null)
                groups[letter]!!.forEach { add(letter to it) }
            }
        }
    }
    // 每个字母首次出现的下标:单次遍历算出(此前对每个字母各扫一遍 flat,是 O(L×N))
    val letterIndex = remember(flat) {
        val map = HashMap<Char, Int>()
        flat.forEachIndexed { index, (letter, _) -> map.putIfAbsent(letter, index) }
        map
    }
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
                            .yohakuTouchTarget()
                            .clickable { scope.launch { listState.scrollToItem(letterIndex[letter] ?: 0) } }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun StepLogin(
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
    // 渲染进程被系统回收(内存紧张/内核崩溃)时必须重建 WebView,否则页面会永久白屏
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var restoreUrl by remember { mutableStateOf<String?>(null) }
    // window.open / target=_blank 打开的页面
    var popup by remember { mutableStateOf<WebView?>(null) }

    fun syncNavState(view: WebView?) {
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    /** 关闭新窗口页面。以 holder.popup 为准,避免闭包读到过期的 compose 值。 */
    fun closePopup() {
        val view = holder.popup ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.destroy() }
        holder.popup = null
        popup = null
        syncNavState(holder.webView)
    }

    // 离开本步时收掉弹窗 WebView,不留下占内存的页面
    DisposableEffect(Unit) { onDispose { closePopup() } }

    if (popup != null) {
        BackHandler { closePopup() }
    }

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
            // 渲染进程回收后 webViewGeneration 自增,AndroidView 会重新执行 factory 建新页面
            key(webViewGeneration) {
                AndroidView(
                    factory = { ctx ->
                        // 同一适配器复用 WebView(返回上一步再进入不丢登录态),换适配器才重建。
                        // 渲染进程回收时 adapterKey 已被清空,同样会走到重建分支。
                        if (holder.adapterKey == adapter.adapterId) {
                            holder.webView
                        } else {
                            val created = WebView(ctx)
                            configureImportWebView(
                                webView = created,
                                context = ctx,
                                isPopup = false,
                                bridge = bridge,
                                desktopUa = ImportBridge.desktopUserAgent(ctx),
                                onPageStart = { loadError = null },
                                onError = { loadError = it },
                                onNavState = { view ->
                                    if (view === holder.popup || holder.popup == null) syncNavState(view)
                                },
                                onCreatePopup = { child ->
                                    holder.popup = child
                                    popup = child
                                    syncNavState(child)
                                },
                                onClosePopup = { closePopup() },
                                onRendererGone = { dead ->
                                    restoreUrl = restoreUrl ?: dead.url
                                    (dead.parent as? ViewGroup)?.removeView(dead)
                                    runCatching { dead.destroy() }
                                    holder.popup?.let { p ->
                                        (p.parent as? ViewGroup)?.removeView(p)
                                        runCatching { p.destroy() }
                                    }
                                    holder.popup = null
                                    popup = null
                                    // 清掉适配器标记:强制下面重新建一个 WebView
                                    holder.adapterKey = null
                                    // 桥不能继续指向已销毁的页面,否则后续桥调用只会静默超时
                                    bridge.bindWebView(null)
                                    webViewGeneration++
                                },
                            )
                            created.loadUrl(restoreUrl ?: importUrl)
                            restoreUrl = null
                            holder.webView = created
                            holder.adapterKey = adapter.adapterId
                            // 桥默认指向主页面;点「执行导入」时会再绑定到当时的可见页(可能是弹窗)
                            bridge.bindWebView(created)
                            created
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 新窗口页面覆盖层:多数教务系统把课表开在 window.open 出来的窗口里
            popup?.let { popupView ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.paper),
                ) {
                    AndroidView(factory = { popupView }, modifier = Modifier.fillMaxSize())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "新窗口",
                            style = YohakuType.label12,
                            color = colors.neutral7,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "关闭",
                            style = YohakuType.copy13,
                            color = colors.accent,
                            modifier = Modifier
                                .yohakuTouchTarget()
                                .clickable { closePopup() }
                                .padding(8.dp),
                        )
                    }
                }
            }
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
                    .yohakuTouchTarget(minWidth = 40.dp)
                    .clickable(enabled = canGoBack) { holder.active.goBack() }
                    .padding(horizontal = 6.dp),
            )
            Text(
                text = "›",
                style = YohakuType.title20,
                color = if (canGoForward) colors.neutral9 else colors.neutral5,
                modifier = Modifier
                    .yohakuTouchTarget(minWidth = 40.dp)
                    .clickable(enabled = canGoForward) { holder.active.goForward() }
                    .padding(horizontal = 6.dp),
            )
            Text(
                text = "刷新",
                style = YohakuType.copy13,
                color = colors.neutral9,
                modifier = Modifier
                    .yohakuTouchTarget()
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
internal fun StepConfirm(
    courses: List<Course>,
    periods: List<Schedule.Period>,
    detectedPeriods: String?,
    detectedSemester: Pair<Int, Long>?,
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
                // 不再给「要不要应用」的勾选:适配器带了作息就是这所学校的作息,直接覆盖。
                // 这里只把「会覆盖什么」写在明面上,不静默。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = "将按脚本识别到的作息 / 学期覆盖当前设置",
                        style = YohakuType.copy13,
                        color = colors.accent,
                    )
                    if (detectedPeriods != null) {
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
                    Text(
                        text = "导入后课程的时刻会按这张作息固定,之后再改作息不会移动它们。",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                    )
                }
            } else {
                Text(
                    text = "脚本未提供作息时间与开学日期,沿用当前设置。",
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
                        // 与上方作息行同一张表:否则同一门课会显示两个时间
                        text = Schedule.courseTimeText(course, periods),
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
                    .yohakuTouchTarget()
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

internal fun weekSummary(course: Course): String = course.weeksText()


/** 适配器索引不可用(缓存损坏且内置缺失)时的可操作错误面板,替代此前的静默空列表。 */
@Composable
internal fun IndexErrorPanel(onSync: () -> Unit, onRetry: () -> Unit) {
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
                .yohakuTouchTarget()
                .clickable(onClick = onRetry)
                .padding(8.dp),
        )
    }
}