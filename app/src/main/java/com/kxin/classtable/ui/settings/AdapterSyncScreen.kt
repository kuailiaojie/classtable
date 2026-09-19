package com.kxin.classtable.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.importer.AdapterSource
import com.kxin.classtable.data.importer.AdapterSyncInfo
import com.kxin.classtable.data.importer.AdapterSyncRepository
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AdapterSyncViewModel @Inject constructor(
    private val repository: AdapterSyncRepository,
) : ViewModel() {
    private val _info = MutableStateFlow<AdapterSyncInfo?>(null)
    val info: StateFlow<AdapterSyncInfo?> = _info.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { _info.value = repository.info() }
    }

    fun sync() = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        repository.sync()
            .onSuccess {
                _info.value = it
                _message.value = "同步完成:${it.schoolCount} 所学校 / ${it.adapterCount} 个适配器"
            }
            .onFailure { _message.value = "同步失败:${it.message ?: "未知错误"}" }
        _busy.value = false
    }

    fun clear() = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        repository.clear()
            .onSuccess {
                _info.value = it
                _message.value = "已恢复内置适配器"
            }
            .onFailure { _message.value = "操作失败:${it.message ?: "未知错误"}" }
        _busy.value = false
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/**
 * 适配器同步子页:从自建 Netlify 站点拉取最新学校索引与适配脚本,
 * 落地后导入页优先使用缓存,失败回退内置数据。
 */
@Composable
fun AdapterSyncScreen(
    nav: NavHostController,
    viewModel: AdapterSyncViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val info by viewModel.info.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    val synced = info?.source == AdapterSource.SYNCED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "适配器同步", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "从云端获取最新的学校索引与教务适配脚本,同步后导入页优先使用云端数据;",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "同步失败或未同步时自动使用应用内置适配器,不影响导入。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            InfoRow("数据来源", if (synced) "已同步(云端)" else "内置")
            InfoRow("学校数量", (info?.schoolCount ?: 0).toString())
            InfoRow("适配器数量", (info?.adapterCount ?: 0).toString())
            InfoRow("适配脚本", if (synced) (info?.scriptCount ?: 0).toString() else "—")
            InfoRow("同步时间", if (synced) formatSyncedAt(info?.syncedAt ?: 0L) else "—")

            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            if (info?.generatedAt?.isNotBlank() == true) {
                Text(
                    text = "云端数据生成于 ${info?.generatedAt}",
                    style = YohakuType.label12,
                    color = colors.neutral6,
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            }
            if (synced) {
                Text(
                    text = "恢复内置适配器",
                    style = YohakuType.copy13,
                    color = colors.error,
                    modifier = Modifier
                        .clickable(enabled = !busy) { viewModel.clear() }
                        .padding(vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = if (busy) "同步中…" else "立即同步",
                onClick = { viewModel.sync() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = YohakuType.copy15,
            color = colors.neutral9,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = YohakuType.copy13, color = colors.neutral7)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.neutral3),
    )
}

private fun formatSyncedAt(millis: Long): String =
    if (millis <= 0L) {
        "—"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
