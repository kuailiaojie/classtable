package com.kxin.classtable.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.data.AuthRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.SurveyPrompt
import com.kxin.classtable.data.yuketang.YuketangRepository
import com.kxin.classtable.design.AccentOptions
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.AiProvider
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.notify.LiveCourse
import com.kxin.classtable.notify.startLiveCourseService
import com.kxin.classtable.widget.NextClassWidgetReceiver
import com.kxin.classtable.widget.TodayWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val surveyPrompt: SurveyPrompt,
    yuketangRepository: YuketangRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val userEmail: StateFlow<String?> = authRepository.currentUser
        .map { it?.email }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 雨课堂登录态:决定设置页那一行的说明文字。 */
    val yuketangLoggedIn: StateFlow<Boolean> = yuketangRepository.loggedIn

    /** 够资格时弹一次用户问卷邀请(判定见 [SurveyPrompt])。 */
    val surveyInvite: StateFlow<Boolean> = surveyPrompt.shouldInvite
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 进入界面时记一次「打开过」;进程内只数一次。 */
    fun onAppOpened() = viewModelScope.launch { surveyPrompt.onAppOpened() }

    /** 问卷已处理:填了或不想填都算,之后不再自动弹。 */
    fun completeSurvey() = viewModelScope.launch { surveyPrompt.dismiss() }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    fun setAccent(hex: String) = viewModelScope.launch { settingsRepository.setAccent(hex) }

    fun setAiProvider(name: String) = viewModelScope.launch { settingsRepository.setAiProvider(name) }

    fun setAiKey(key: String) = viewModelScope.launch { settingsRepository.setAiKey(key) }

    fun setAiBaseUrl(url: String) = viewModelScope.launch { settingsRepository.setAiBaseUrl(url) }

    fun setAiModel(model: String) = viewModelScope.launch { settingsRepository.setAiModel(model) }

    fun setNotificationsEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }

    fun setNotifyLeadMinutes(minutes: Int) =
        viewModelScope.launch { settingsRepository.setNotifyLeadMinutes(minutes) }

    /** 提醒形态(标准 / 实时活动)。 */
    fun setNotifyMode(mode: String) = viewModelScope.launch {
        settingsRepository.setNotifyMode(
            runCatching { NotifyMode.valueOf(mode) }.getOrDefault(NotifyMode.STANDARD),
        )
    }

    /** 明日课程预告:开关 + 时刻。 */
    fun setTomorrowReminder(enabled: Boolean, time: String) =
        viewModelScope.launch { settingsRepository.setTomorrowReminder(enabled, time) }

    fun setAutoCheckUpdate(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoCheckUpdate(enabled) }

    /** 首次启动权限引导完成/跳过标记(只弹一次,设置页可随时重进)。 */
    fun completeOnboarding() =
        viewModelScope.launch { settingsRepository.setOnboardingDone() }
}

/** 设置页:主题 / 强调色(5 和色)/ 列表入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    nav: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val yuketangLoggedIn by viewModel.yuketangLoggedIn.collectAsStateWithLifecycle()
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }
    val firstPeriodText = if (periods.isNotEmpty()) {
        "第1节 %02d:%02d".format(periods[0].start / 60, periods[0].start % 60)
    } else {
        "未设置"
    }
    val realWeek = Schedule.currentWeek(settings.semesterStartDay, settings.semesterWeekCount)
    val adjustmentDays = remember(settings.scheduleAdjustments) {
        Adjustments.decode(settings.scheduleAdjustments).size
    }

    var showAiDialog by remember { mutableStateOf(false) }
    var showNotifyDialog by remember { mutableStateOf(false) }
    var showAutoCheckDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var aiProvider by remember { mutableStateOf(settings.aiProvider) }
    var aiKey by remember { mutableStateOf(settings.aiApiKey) }
    var aiBaseUrl by remember { mutableStateOf(settings.aiBaseUrl) }
    var aiModel by remember { mutableStateOf(settings.aiModel) }
    var notifyEnabled by remember { mutableStateOf(settings.notificationsEnabled) }
    var notifyLead by remember { mutableStateOf(settings.notifyLeadMinutes) }
    var notifyMode by remember { mutableStateOf(settings.notifyMode) }
    var tomorrowEnabled by remember { mutableStateOf(settings.tomorrowReminderEnabled) }
    var tomorrowTime by remember { mutableStateOf(settings.tomorrowReminderTime) }
    var autoCheck by remember { mutableStateOf(settings.autoCheckUpdate) }
    LaunchedEffect(showAutoCheckDialog) {
        if (showAutoCheckDialog) autoCheck = settings.autoCheckUpdate
    }
    if (showAutoCheckDialog) {
        YohakuDialog(
            onDismissRequest = { showAutoCheckDialog = false },
            title = "自动检查更新",
            actions = {
                YohakuDialogAction(text = "取消", onClick = { showAutoCheckDialog = false })
                YohakuDialogAction(
                    text = "保存",
                    accent = true,
                    onClick = {
                        viewModel.setAutoCheckUpdate(autoCheck)
                        showAutoCheckDialog = false
                    },
                )
            },
            content = {
                Text(
                    text = "每天最多检查一次,仅在联网时进行;发现新版本发一条通知。只提示,不自动下载安装。",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YohakuChip(
                        text = "开启",
                        selected = autoCheck,
                        onClick = { autoCheck = true },
                    )
                    YohakuChip(
                        text = "关闭",
                        selected = !autoCheck,
                        onClick = { autoCheck = false },
                    )
                }
            },
        )
    }
    LaunchedEffect(showNotifyDialog) {
        if (showNotifyDialog) {
            notifyEnabled = settings.notificationsEnabled
            notifyLead = settings.notifyLeadMinutes
            notifyMode = settings.notifyMode
            tomorrowEnabled = settings.tomorrowReminderEnabled
            tomorrowTime = settings.tomorrowReminderTime
        }
    }
    LaunchedEffect(showAiDialog) {
        if (showAiDialog) {
            aiProvider = settings.aiProvider
            aiKey = settings.aiApiKey
            aiBaseUrl = settings.aiBaseUrl
            aiModel = settings.aiModel
        }
    }
    if (showAiDialog) {
        val provider = runCatching { AiProvider.valueOf(aiProvider) }.getOrDefault(AiProvider.GEMINI)
        YohakuDialog(
            onDismissRequest = { showAiDialog = false },
            title = "AI 密钥",
            actions = {
                YohakuDialogAction(text = "取消", onClick = { showAiDialog = false })
                YohakuDialogAction(
                    text = "保存",
                    accent = true,
                    onClick = {
                        viewModel.setAiProvider(aiProvider)
                        viewModel.setAiKey(aiKey)
                        viewModel.setAiBaseUrl(aiBaseUrl)
                        viewModel.setAiModel(aiModel)
                        showAiDialog = false
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
                        text = "用于 AI 图片识别课表,密钥仅存本机。",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiProvider.entries.forEach { p ->
                            YohakuChip(
                                text = p.label,
                                selected = aiProvider == p.name,
                                onClick = {
                                    aiProvider = p.name
                                    aiBaseUrl = ""
                                    aiModel = ""
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    YohakuTextField(
                        value = aiKey,
                        onValueChange = { aiKey = it },
                        label = "API Key",
                        placeholder = "sk-...",
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (provider == AiProvider.OPENAI_COMPAT) {
                        YohakuTextField(
                            value = aiBaseUrl,
                            onValueChange = { aiBaseUrl = it },
                            label = "Base URL",
                            placeholder = provider.defaultBaseUrl,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "常用:OpenAI https://api.openai.com/v1 · DeepSeek https://api.deepseek.com/v1 · 通义千问 https://dashscope.aliyuncs.com/compatible-mode/v1 · Kimi https://api.moonshot.cn/v1 · 智谱 https://open.bigmodel.cn/api/paas/v4",
                            style = YohakuType.label12,
                            color = colors.neutral7,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    YohakuTextField(
                        value = aiModel,
                        onValueChange = { aiModel = it },
                        label = "模型",
                        placeholder = provider.defaultModel,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "图片识别需支持视觉的模型,如 gemini-2.5-flash / gpt-4o / qwen-vl-plus / glm-4v-flash / moonshot-v1-8k-vision-preview。",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                }
            },
        )
    }

    if (showNotifyDialog) {
        YohakuDialog(
            onDismissRequest = { showNotifyDialog = false },
            title = "课程提醒",
            actions = {
                YohakuDialogAction(
                    text = "预览实时活动",
                    onClick = {
                        previewLiveUpdate(context)
                        showNotifyDialog = false
                    },
                )
                YohakuDialogAction(text = "取消", onClick = { showNotifyDialog = false })
                YohakuDialogAction(
                    text = "保存",
                    accent = true,
                    onClick = {
                        viewModel.setNotificationsEnabled(notifyEnabled)
                        viewModel.setNotifyLeadMinutes(notifyLead)
                        viewModel.setNotifyMode(notifyMode)
                        viewModel.setTomorrowReminder(tomorrowEnabled, tomorrowTime)
                        showNotifyDialog = false
                    },
                )
            },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "在每节课开始前发送通知。内容在触发时动态计算(剩余分钟/开始时间/地点)。",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "开启",
                            selected = notifyEnabled,
                            onClick = { notifyEnabled = true },
                        )
                        YohakuChip(
                            text = "关闭",
                            selected = !notifyEnabled,
                            onClick = { notifyEnabled = false },
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "提前多少分钟提醒",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val leadOptions = listOf(0 to "准点", 5 to "5 分钟", 10 to "10 分钟", 15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        leadOptions.forEach { (min, label) ->
                            YohakuChip(
                                text = label,
                                selected = notifyEnabled && notifyLead == min,
                                onClick = { notifyLead = min },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(text = "提醒形态", style = YohakuType.label12, color = colors.neutral7)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "标准提醒",
                            selected = notifyMode == NotifyMode.STANDARD.name,
                            onClick = { notifyMode = NotifyMode.STANDARD.name },
                        )
                        YohakuChip(
                            text = "实时活动",
                            selected = notifyMode == NotifyMode.LIVE.name,
                            onClick = { notifyMode = NotifyMode.LIVE.name },
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "实时活动:从提前量那一刻起常驻一条通知,显示「还有 N 分钟上课 / 下课」直到下课,通知上可直接取消本节课提醒(重试与重启都不会再打扰)。",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(text = "明日课程预告", style = YohakuType.label12, color = colors.neutral7)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "开启",
                            selected = tomorrowEnabled,
                            onClick = { tomorrowEnabled = true },
                        )
                        YohakuChip(
                            text = "关闭",
                            selected = !tomorrowEnabled,
                            onClick = { tomorrowEnabled = false },
                        )
                    }
                    if (tomorrowEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        YohakuTextField(
                            value = tomorrowTime,
                            onValueChange = { tomorrowTime = it },
                            label = "提醒时刻",
                            placeholder = "21:30",
                            isError = Schedule.parseClock(tomorrowTime) == null,
                        )
                        if (Schedule.parseClock(tomorrowTime) == null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "请按 24 小时制填写,如 21:30",
                                style = YohakuType.label12,
                                color = colors.error,
                            )
                        }
                    }
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        // 设置是底部导航的根标签页,没有上一级可返回,因此这里不放返回箭头
        YohakuTopBar(title = "设置")

        SettingsSection(title = "外观") {
            SettingBlock(title = "主题") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        YohakuChip(
                            text = when (mode) {
                                ThemeMode.SYSTEM -> "跟随系统"
                                ThemeMode.LIGHT -> "浅色"
                                ThemeMode.DARK -> "深色"
                            },
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                        )
                    }
                }
            }
            DividerLine()
            SettingBlock(title = "强调色") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AccentOptions.forEach { (label, hex) ->
                        val selected = settings.accentHex.equals(hex, ignoreCase = true)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(accentColor(hex))
                                    .then(
                                        if (selected) {
                                            Modifier.border(2.dp, colors.neutral10, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { viewModel.setAccent(hex) },
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = YohakuType.label12,
                                color = if (selected) colors.neutral9 else colors.neutral7,
                            )
                        }
                    }
                }
            }
        }

        SettingsSection(title = "课表") {
            SettingRow(title = "作息时间", value = firstPeriodText, onClick = { nav.navigate("schedule_times") })
            DividerLine()
            SettingRow(title = "学期周次", value = "当前第 $realWeek 周", onClick = { nav.navigate("semester") })
            DividerLine()
            SettingRow(
                title = "调休课表",
                value = if (adjustmentDays == 0) "未设置" else "$adjustmentDays 天",
                onClick = { nav.navigate("adjustments") },
            )
        }

        SettingsSection(title = "导入与识别") {
            SettingRow(title = "教务导入", value = "3 步导入", onClick = { nav.navigate("import") })
            DividerLine()
            SettingRow(
                title = "AI 密钥",
                value = if (settings.aiApiKey.isBlank()) "未配置" else "已配置 · ${settings.provider().label}",
                onClick = { showAiDialog = true },
            )
            DividerLine()
            SettingRow(title = "适配器同步", value = "更新学校与脚本", onClick = { nav.navigate("adapter_sync") })
        }

        SettingsSection(title = "提醒") {
            SettingRow(
                title = "课程提醒",
                value = when {
                    !settings.notificationsEnabled -> "已关闭"
                    settings.notifyMode == NotifyMode.LIVE.name && settings.notifyLeadMinutes <= 0 ->
                        "准点 · 实时活动"
                    settings.notifyMode == NotifyMode.LIVE.name ->
                        "课前 ${settings.notifyLeadMinutes} 分钟 · 实时活动"
                    settings.notifyLeadMinutes <= 0 -> "准点提醒"
                    else -> "课前 ${settings.notifyLeadMinutes} 分钟"
                },
                onClick = { showNotifyDialog = true },
            )
            DividerLine()
            SettingRow(title = "提醒可靠性", value = "通知 / 闹钟 / 自启动", onClick = { nav.navigate("permissions") })
        }

        SettingsSection(title = "雨课堂") {
            SettingRow(
                title = "雨课堂公告",
                value = if (yuketangLoggedIn) "已登录" else "未登录",
                onClick = { nav.navigate("rain_classroom") },
            )
        }

        SettingsSection(title = "桌面") {
            SettingRow(
                title = "桌面小组件",
                value = "今日 / 明日 / 下节课 · 可自定义",
                onClick = { nav.navigate("widget_settings") },
            )
        }

        SettingsSection(title = "账号与数据") {
            SettingRow(title = "账号", value = userEmail ?: "未登录", onClick = { nav.navigate("account") })
        }

        SettingsSection(title = "关于") {
            SettingRow(title = "检查更新", value = "v${BuildConfig.VERSION_NAME}", onClick = { nav.navigate("update") })
            DividerLine()
            SettingRow(
                title = "自动检查更新",
                value = if (settings.autoCheckUpdate) {
                    if (settings.dismissedVersion.isNotBlank()) "每天一次 · 已忽略 v${settings.dismissedVersion}" else "每天一次"
                } else {
                    "已关闭"
                },
                onClick = { showAutoCheckDialog = true },
            )
            DividerLine()
            SettingRow(title = "关于", value = "v${BuildConfig.VERSION_NAME}", onClick = { nav.navigate("about") })
            DividerLine()
            // 邀请弹窗关了之后不是死路:这里常驻入口,想填随时能填
            SettingRow(
                title = "用户问卷",
                value = "两分钟 · 帮我们改进",
                onClick = { runCatching { uriHandler.openUri(SurveyPrompt.FORM_URL) } },
            )
        }

        // 悬浮导航浮在内容之上:列表中途会从栏下穿过,末尾留出栏体高度,
        // 最后一行才不会被永久盖住。
        Spacer(modifier = Modifier.height(YohakuDimens.navReservedHeight))
    }
}

/**
 * 预览实时活动:用一节「5 分钟后开始、45 分钟」的假课程拉起常驻通知,
 * 让用户在设置里先看清它长什么样(不写课程数据、不影响真实提醒)。
 */
private fun previewLiveUpdate(context: Context) {
    val start = System.currentTimeMillis() + 5 * 60_000L
    startLiveCourseService(
        context,
        LiveCourse(
            courseId = "preview",
            name = "高等数学",
            location = "教学楼 A101",
            startAtMillis = start,
            endAtMillis = start + 45 * 60_000L,
            leadMinutes = 5,
            muteKey = "preview:${System.currentTimeMillis()}",
        ),
    )
}
