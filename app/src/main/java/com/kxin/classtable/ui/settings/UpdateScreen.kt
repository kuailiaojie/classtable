package com.kxin.classtable.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.kxin.classtable.data.UpdateInfo
import com.kxin.classtable.data.UpdateRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val file: File, val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
) : ViewModel() {
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check() = viewModelScope.launch {
        _state.value = UpdateState.Checking
        repository.markChecked()
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

    fun download(info: UpdateInfo) = viewModelScope.launch {
        _state.value = UpdateState.Downloading(0)
        repository.downloadApk(info.apkUrl) { percent ->
            _state.value = UpdateState.Downloading(percent)
        }
            .onSuccess { file -> _state.value = UpdateState.Ready(file, info) }
            .onFailure { _state.value = UpdateState.Failed(it.message ?: "下载失败") }
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

/** 检查更新页:查询自建反代的最新版本,应用内下载 APK 并拉起系统安装器。 */
@Composable
fun UpdateScreen(
    nav: NavHostController,
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 进入即检查一次
    LaunchedEffect(Unit) { viewModel.check() }

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
                is UpdateState.Failed -> {
                    Text(
                        text = "检查失败:${s.message}",
                        style = YohakuType.copy13,
                        color = colors.error,
                    )
                }
                is UpdateState.Downloading -> Hint("正在下载安装包 ${s.percent}%")
                is UpdateState.Ready -> Hint("安装包已下载,点下方「安装」完成更新。")
                is UpdateState.Available -> {
                    Text(
                        text = "发现新版本 v${s.info.latestVersion}",
                        style = YohakuType.copy16,
                        color = colors.accent,
                    )
                    Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                    if (s.info.notes.isNotBlank()) {
                        Text(text = "更新说明", style = YohakuType.label12, color = colors.neutral7)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = s.info.notes,
                            style = YohakuType.copy13,
                            color = colors.neutral9,
                        )
                    }
                }
            }

            val info = when (val s = state) {
                is UpdateState.Available -> s.info
                is UpdateState.Ready -> s.info
                else -> null
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
        ) {
            when (val s = state) {
                is UpdateState.Available -> YohakuButton(
                    text = "下载并安装",
                    onClick = { viewModel.download(s.info) },
                    modifier = Modifier.weight(1f),
                )
                is UpdateState.Downloading -> YohakuButton(
                    text = "下载中 ${s.percent}%",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
                is UpdateState.Ready -> YohakuButton(
                    text = "安装",
                    onClick = { installApk(context, s.file) },
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

@Composable
private fun Hint(text: String) {
    val colors = LocalYohakuColors.current
    Text(text = text, style = YohakuType.copy13, color = colors.neutral7)
}

/**
 * 拉起系统安装器:未授予「安装未知应用」时先跳设置页,授权后返回重试。
 * 通过 FileProvider 暴露 filesDir/updates 下的 APK。
 */
private fun installApk(context: Context, apk: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
        Toast.makeText(context, "请允许安装未知应用后返回重试", Toast.LENGTH_LONG).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "无法启动安装器:${it.message}", Toast.LENGTH_LONG).show() }
}
