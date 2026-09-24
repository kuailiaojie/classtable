package com.kxin.classtable.ui.yuketang

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.yuketang.YuketangRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.ui.settings.DividerLine
import com.kxin.classtable.ui.settings.SettingBlock
import com.kxin.classtable.ui.settings.SettingRow
import com.kxin.classtable.ui.settings.SettingsSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class RainClassroomViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val repository: YuketangRepository,
    courseRepository: CourseRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 登录态直接来自仓库(单例流),登录/登出后本页立刻跟着变。 */
    val loggedIn: StateFlow<Boolean> = repository.loggedIn

    /** 已绑定雨课堂班级的课程数。 */
    val boundCount: StateFlow<Int> = repository.observeBindings()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 本机课程总数(绑定进度的分母)。 */
    val courseCount: StateFlow<Int> = courseRepository.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    var refreshing by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    fun clearMessage() {
        message = null
    }

    fun setOptions(
        enabled: Boolean,
        includeInReminder: Boolean,
        backgroundFetch: Boolean,
        notifyNew: Boolean,
    ) = viewModelScope.launch {
        settingsRepository.setYuketangOptions(enabled, includeInReminder, backgroundFetch, notifyNew)
    }

    fun logout() = viewModelScope.launch {
        repository.logout()
        message = "已退出雨课堂登录(绑定关系保留)"
    }

    /** 前台立即拉一次公告。登录失效时明确提示,不静默;网络不通则只说网络问题。 */
    fun refreshNow() = viewModelScope.launch {
        if (!repository.isLoggedIn()) {
            message = "请先登录雨课堂"
            return@launch
        }
        refreshing = true
        val verified = runCatching { repository.verifyLogin() }
        if (verified.getOrNull() == false) {
            refreshing = false
            repository.logout()
            message = "登录已失效,请重新登录"
            return@launch
        }
        if (verified.isFailure) {
            refreshing = false
            message = "校验失败:网络不通,稍后再试"
            return@launch
        }
        val result = runCatching { repository.syncAnnouncements() }
        settingsRepository.markYuketangFetched()
        refreshing = false
        message = result.fold(
            onSuccess = { sync ->
                if (sync.fetched == 0) {
                    "没有取到公告(可能未绑定课程,或接口未返回数据)"
                } else {
                    "已同步 ${sync.fetched} 条公告"
                }
            },
            onFailure = { "同步失败:${it.message ?: "网络错误"}" },
        )
    }
}

/** 雨课堂设置页:登录状态 + 四个开关 + 课程绑定 + 手动刷新。 */
@Composable
fun RainClassroomScreen(
    nav: NavHostController,
    viewModel: RainClassroomViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()
    val boundCount by viewModel.boundCount.collectAsStateWithLifecycle()
    val courseCount by viewModel.courseCount.collectAsStateWithLifecycle()

    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        YohakuDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = "雨课堂登录",
            actions = {
                YohakuDialogAction(text = "取消", onClick = { showLogoutDialog = false })
                YohakuDialogAction(
                    text = "重新登录",
                    onClick = {
                        showLogoutDialog = false
                        nav.navigate("yuketang_login")
                    },
                )
                YohakuDialogAction(
                    text = "退出登录",
                    accent = true,
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    },
                )
            },
            content = {
                Text(
                    text = "登录态一般两周左右过期。过期后重新登录一次即可,已绑定的课程不用再配。",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        YohakuTopBar(title = "雨课堂", onBack = { nav.popBackStack() })

        SettingsSection(title = "账号") {
            SettingRow(
                title = "登录状态",
                value = if (loggedIn) "已登录" else "未登录",
                onClick = {
                    if (loggedIn) showLogoutDialog = true else nav.navigate("yuketang_login")
                },
            )
            DividerLine()
            SettingRow(
                title = "课程对应关系",
                value = if (courseCount == 0) "没有课程" else "已绑定 $boundCount / 共 $courseCount 门",
                onClick = { nav.navigate("yuketang_bind") },
            )
        }

        SettingsSection(title = "公告") {
            SettingBlock(
                title = "雨课堂公告",
                subtitle = "总开关。关闭后不发通知、课前提醒里也不带公告,后台不再拉取。",
            ) {
                ChipToggle(
                    selected = settings.yuketangEnabled,
                    onSelected = { enabled ->
                        viewModel.setOptions(
                            enabled = enabled,
                            includeInReminder = settings.yuketangIncludeInReminder,
                            backgroundFetch = settings.yuketangBackgroundFetch,
                            notifyNew = settings.yuketangNotifyNew,
                        )
                    },
                )
            }
            DividerLine()
            SettingBlock(
                title = "课前提醒包含最新公告",
                subtitle = "每节课开始前的提醒里附上该课程最新一条公告的标题(读本机缓存,不联网)。",
            ) {
                ChipToggle(
                    selected = settings.yuketangIncludeInReminder,
                    onSelected = { enabled ->
                        viewModel.setOptions(
                            enabled = settings.yuketangEnabled,
                            includeInReminder = enabled,
                            backgroundFetch = settings.yuketangBackgroundFetch,
                            notifyNew = settings.yuketangNotifyNew,
                        )
                    },
                )
            }
            DividerLine()
            SettingBlock(
                title = "后台自动拉取",
                subtitle = "每小时检查一次(仅联网时),把公告同步到本机,供课程详情与课前提醒使用。",
            ) {
                ChipToggle(
                    selected = settings.yuketangBackgroundFetch,
                    onSelected = { enabled ->
                        viewModel.setOptions(
                            enabled = settings.yuketangEnabled,
                            includeInReminder = settings.yuketangIncludeInReminder,
                            backgroundFetch = enabled,
                            notifyNew = settings.yuketangNotifyNew,
                        )
                    },
                )
            }
            DividerLine()
            SettingBlock(
                title = "新公告提醒",
                subtitle = "发现新公告时发一条通知(独立渠道,不覆盖课程提醒)。",
            ) {
                ChipToggle(
                    selected = settings.yuketangNotifyNew,
                    onSelected = { enabled ->
                        viewModel.setOptions(
                            enabled = settings.yuketangEnabled,
                            includeInReminder = settings.yuketangIncludeInReminder,
                            backgroundFetch = settings.yuketangBackgroundFetch,
                            notifyNew = enabled,
                        )
                    },
                )
            }
        }

        SettingsSection(title = "数据") {
            SettingRow(
                title = if (viewModel.refreshing) "正在刷新…" else "立即刷新公告",
                value = fetchedAtText(settings.yuketangLastFetchAt),
                onClick = { if (!viewModel.refreshing) viewModel.refreshNow() },
            )
        }

        viewModel.message?.let { hint ->
            Text(
                text = hint,
                style = YohakuType.label12,
                color = colors.neutral7,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 开 / 关一对 chip(项目没有 Switch,布尔项一律用 chip 对)。 */
@Composable
private fun ChipToggle(selected: Boolean, onSelected: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        YohakuChip(text = "开启", selected = selected, onClick = { onSelected(true) })
        YohakuChip(text = "关闭", selected = !selected, onClick = { onSelected(false) })
    }
}

/** 「上次 9月12日 21:30」;从未拉取过时给一句直白的话。 */
private fun fetchedAtText(at: Long): String {
    if (at <= 0L) return "从未"
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime()
    val date = LocalDate.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
    return "上次 %d月%d日 %02d:%02d".format(date.monthValue, date.dayOfMonth, time.hour, time.minute)
}
