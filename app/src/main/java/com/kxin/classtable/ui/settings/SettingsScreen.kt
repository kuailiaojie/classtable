package com.kxin.classtable.ui.settings

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.IconCadence
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.icon.AppIcon
import com.kxin.classtable.icon.IconRotationScheduler
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

    /** 首次启动权限引导完成/跳过标记(只弹一次,设置页可随时重进)。 */
    fun completeOnboarding() =
        viewModelScope.launch { settingsRepository.setOnboardingDone() }

    /**
     * 「每次打开」节奏的图标轮播:本进程第一次真正进入界面时换下一张。
     * 后台节奏(每小时 / 每天)由 [com.kxin.classtable.icon.AppIconRotationWorker] 负责。
     */
    fun rotateIconOnLaunch() = viewModelScope.launch {
        val current = settingsRepository.currentSettings()
        val cadence = IconCadence.of(current.iconCarouselCadence)
        if (!IconRotationScheduler.claimLaunchRotation(current.iconCarouselEnabled, cadence)) return@launch
        settingsRepository.setAppIconIndex(AppIcon.nextIndex(current.appIconIndex))
    }
}

/**
 * 设置页:主题 / 强调色(6 和色)/ 应用图标 / 列表入口。
 *
 * 这里只放「一眼看得到当前值」的行与少量就地可改项;需要填表或多步操作的
 * (作息、周次、AI 密钥、课程提醒、雨课堂、小组件、更新…)都各有独立页面。
 */
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

    val uriHandler = LocalUriHandler.current

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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
            DividerLine()
            SettingRow(
                title = "应用图标",
                value = AppIcon.of(settings.appIconIndex).label.let {
                    if (settings.iconCarouselEnabled) "轮播中 · $it" else it
                },
                onClick = { nav.navigate("app_icon") },
            )
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
                onClick = { nav.navigate("ai_key") },
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
                onClick = { nav.navigate("course_reminder") },
            )
            DividerLine()
            SettingRow(title = "提醒可靠性", value = "通知 / 闹钟 / 自启动", onClick = { nav.navigate("permissions") })
            DividerLine()
            SettingRow(
                title = "上课免打扰",
                value = if (settings.classDndEnabled) "自动进 / 退" else "已关闭",
                onClick = { nav.navigate("class_dnd") },
            )
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
