package com.kxin.classtable.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.data.DownloadState
import com.kxin.classtable.data.UpdateInfo
import com.kxin.classtable.data.UpdateRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMarkdown
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
) : ViewModel() {
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 下载状态由仓库持有:来回切页不会中断下载,也不会丢进度。 */
    val download: StateFlow<DownloadState> = repository.download

    fun check() = viewModelScope.launch {
        _state.value = UpdateState.Checking
        repository.markChecked()
        repository.clearDownloadState()
        repository.check()
            .onSuccess { info ->
                _state.value = if (info.isNewer) {
                    UpdateState.Available(info)
                } else {
                    UpdateState.UpToDate(info.currentVersion)
                }
            }
            .onFailure { _state.value = UpdateState.Failed(it.message ?: "检查更新失败") }
    }

    fun download(info: UpdateInfo) = repository.startDownload(info)

    fun cancelDownload() = repository.cancelDownload()

    fun retryDownload(info: UpdateInfo) {
        repository.clearDownloadState()
        repository.startDownload(info)
    }

    /** 启动时静默检查:受「自动检查更新」开关与 24h 节流约束;已忽略的版本不再打扰。 */
    fun autoCheck() = viewModelScope.launch {
        if (!repository.shouldAutoCheck()) return@launch
        repository.markChecked()
        repository.check().onSuccess { info ->
            if (info.isNewer && info.latestVersion != repository.dismissedVersion()) {
                _state.value = UpdateState.Available(info)
            }
        }
    }

    /** 忽略该版本:不再主动提示(手动检查仍会显示)。 */
    fun ignoreVersion(version: String) = viewModelScope.launch {
        repository.ignoreVersion(version)
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }
}

/** 检查更新页:查询自建反代的最新版本,应用内下载 APK(分段 + 校验)并拉起系统安装器。 */
@Composable
fun UpdateScreen(
    nav: NavHostController,
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()

    // 进入即检查一次
    LaunchedEffect(Unit) { viewModel.check() }

    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "检查更新", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "当前版本 v${viewModel.currentVersion}",
                style = YohakuType.copy15,
                color = colors.neutral9,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            when (val s = state) {
                is UpdateState.Idle -> Hint("准备检查更新…")
                is UpdateState.Checking -> Hint("正在检查更新…")
                is UpdateState.UpToDate -> Hint("已是最新版本。")
                is UpdateState.Failed -> Text(
                    text = "检查失败:${s.message}",
                    style = YohakuType.copy13,
                    color = colors.error,
                )
                is UpdateState.Available -> {
                    Text(
                        text = "发现新版本 v${s.info.latestVersion}",
                        style = YohakuType.copy16,
                        color = colors.accent,
                    )
                    if (s.info.apkSize > 0) {
                        Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                        Text(
                            text = "安装包 ${sizeText(s.info.apkSize)}",
                            style = YohakuType.label12,
                            color = colors.neutral7,
                        )
                    }
                    if (s.info.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                        Text(text = "更新说明", style = YohakuType.label12, color = colors.neutral7)
                        Spacer(modifier = Modifier.height(6.dp))
                        // 发布说明是 Markdown,按块渲染(标题 / 列表 / 加粗),而不是把标记原样显示
                        YohakuMarkdown(text = s.info.notes)
                    }
                }
            }

            when (val d = download) {
                is DownloadState.Running -> {
                    Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                    Text(
                        text = "正在下载 ${d.percent}%" +
                            if (d.total > 0) "(${sizeText(d.received)} / ${sizeText(d.total)})" else "",
                        style = YohakuType.copy13,
                        color = colors.neutral9,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "下载过程会自动校验完整性,中途切走也不会中断。",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                    )
                }
                is DownloadState.Ready -> {
                    Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                    Text(
                        text = "安装包已下载并校验通过(${sizeText(d.file.length())})。",
                        style = YohakuType.copy13,
                        color = colors.neutral9,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "点下方「安装」完成更新,系统会弹出安装确认。",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                    )
                }
                is DownloadState.Failed -> {
                    Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                    Text(
                        text = "下载失败:${d.message}",
                        style = YohakuType.copy13,
                        color = colors.error,
                    )
                }
                DownloadState.Idle -> Unit
            }

            if (info != null && info.releaseUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                Text(
                    text = "查看发布页",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { uriHandler.openUri(info.releaseUrl) }
                        .padding(vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val d = download) {
                is DownloadState.Running -> {
                    YohakuButton(
                        text = "下载中 ${d.percent}%",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "取消",
                        style = YohakuType.copy13,
                        color = colors.neutral7,
                        modifier = Modifier
                            .clickable { viewModel.cancelDownload() }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
                is DownloadState.Ready -> YohakuButton(
                    text = "安装",
                    onClick = {
                        installApk(context, d.file)?.let { reason ->
                            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                is DownloadState.Failed -> YohakuButton(
                    text = "重试下载",
                    onClick = { info?.let { viewModel.retryDownload(it) } },
                    enabled = info != null,
                    modifier = Modifier.weight(1f),
                )
                DownloadState.Idle -> when (state) {
                    is UpdateState.Available -> YohakuButton(
                        text = "下载并安装",
                        onClick = { viewModel.download((state as UpdateState.Available).info) },
                        modifier = Modifier.weight(1f),
                    )
                    is UpdateState.Checking -> YohakuButton(
                        text = "检查中…",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                    else -> YohakuButton(
                        text = "检查更新",
                        onClick = { viewModel.check() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    val colors = LocalYohakuColors.current
    Text(text = text, style = YohakuType.copy13, color = colors.neutral7)
}

private fun sizeText(bytes: Long): String =
    "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)

/**
 * 拉起系统安装器。返回 null 表示已成功交给系统,否则返回给用户看的原因。
 *
 * 安装前先确认两件事,避免把问题丢给系统的模糊提示:
 * 1. 应用已获准「安装未知应用」(没有就去设置页开,回来再点一次);
 * 2. 文件确实是能解析的 APK(校验在下载完成后已经做过,这里再兜一次文件被清理的情况)。
 */
private fun installApk(context: Context, apk: File): String? {
    if (!apk.exists()) return "安装包已不存在,请重新下载"
    if (context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) == null) {
        return "安装包无法解析,请重新下载"
    }
    if (!context.packageManager.canRequestPackageInstalls()) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        return "请在系统设置里允许本应用安装应用,然后回来再点一次「安装」"
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    }.getOrNull() ?: return "无法读取安装包"

    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.isSuccess
    if (opened) return null

    // 少数 ROM 不认 ACTION_VIEW,退回安装器专用 action
    val fallback = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            },
        )
    }.isSuccess
    return if (fallback) null else "无法启动系统安装器"
}

private const val APK_MIME = "application/vnd.android.package-archive"
