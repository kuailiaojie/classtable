package com.kxin.classtable.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.notify.LiveCourse
import com.kxin.classtable.notify.startLiveCourseService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CourseReminderViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun save(
        enabled: Boolean,
        leadMinutes: Int,
        mode: String,
        tomorrowEnabled: Boolean,
        tomorrowTime: String,
    ) = viewModelScope.launch {
        settingsRepository.setNotificationsEnabled(enabled)
        settingsRepository.setNotifyLeadMinutes(leadMinutes)
        settingsRepository.setNotifyMode(
            runCatching { NotifyMode.valueOf(mode) }.getOrDefault(NotifyMode.STANDARD),
        )
        settingsRepository.setTomorrowReminder(tomorrowEnabled, tomorrowTime)
        _saved.value = true
    }
}

/**
 * 课程提醒页(从设置页「提醒 → 课程提醒」进入)。
 * 课前提醒(提前量 + 形态)与明日课程预告放在这一页,保存后写回设置。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseReminderScreen(
    nav: NavHostController,
    viewModel: CourseReminderViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var enabled by rememberSaveable { mutableStateOf(settings.notificationsEnabled) }
    var leadMinutes by rememberSaveable { mutableIntStateOf(settings.notifyLeadMinutes) }
    var mode by rememberSaveable { mutableStateOf(settings.notifyMode) }
    var tomorrowEnabled by rememberSaveable { mutableStateOf(settings.tomorrowReminderEnabled) }
    var tomorrowTime by rememberSaveable { mutableStateOf(settings.tomorrowReminderTime) }
    var hydrated by remember { mutableStateOf(false) }

    // settings 流先发占位再发真实值;等真实值到位后只灌一次,避免编辑中被覆盖
    LaunchedEffect(settings) {
        if (!hydrated && settings != AppSettings()) {
            enabled = settings.notificationsEnabled
            leadMinutes = settings.notifyLeadMinutes
            mode = settings.notifyMode
            tomorrowEnabled = settings.tomorrowReminderEnabled
            tomorrowTime = settings.tomorrowReminderTime
            hydrated = true
        }
    }
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    // 明日预告时刻填写不合法时不允许保存:否则到点不会触发,却没有任何提示
    val timeValid = Schedule.parseClock(tomorrowTime) != null
    val canSave = !tomorrowEnabled || timeValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "课程提醒", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = "课前提醒") {
                SettingBlock(
                    title = "课程提醒",
                    subtitle = "在每节课开始前发送通知;内容在触发那一刻才算(剩余分钟 / 开始时间 / 地点)。",
                ) {
                    ChipToggle(selected = enabled) { enabled = it }
                }
                DividerLine()
                SettingBlock(title = "提前多少分钟提醒") {
                    val leadOptions = listOf(
                        0 to "准点",
                        5 to "5 分钟",
                        10 to "10 分钟",
                        15 to "15 分钟",
                        30 to "30 分钟",
                        60 to "1 小时",
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        leadOptions.forEach { (minutes, label) ->
                            YohakuChip(
                                text = label,
                                selected = enabled && leadMinutes == minutes,
                                onClick = { leadMinutes = minutes },
                            )
                        }
                    }
                }
                DividerLine()
                SettingBlock(title = "提醒形态") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "标准提醒",
                            selected = mode == NotifyMode.STANDARD.name,
                            onClick = { mode = NotifyMode.STANDARD.name },
                        )
                        YohakuChip(
                            text = "实时活动",
                            selected = mode == NotifyMode.LIVE.name,
                            onClick = { mode = NotifyMode.LIVE.name },
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "实时活动:从提前量那一刻起常驻一条通知,显示「还有 N 分钟上课 / 下课」直到下课," +
                            "通知上可直接取消本节课提醒(重试与重启都不会再打扰)。",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                    )
                }
            }

            SettingsSection(title = "明日课程预告") {
                SettingBlock(
                    title = "明日课程预告",
                    subtitle = "前一天提醒「明天有 N 门课 · 第一节几点、在哪」。",
                ) {
                    ChipToggle(selected = tomorrowEnabled) { tomorrowEnabled = it }
                }
                if (tomorrowEnabled) {
                    DividerLine()
                    SettingBlock(title = "提醒时刻") {
                        YohakuTextField(
                            value = tomorrowTime,
                            onValueChange = { tomorrowTime = it },
                            label = "时刻",
                            placeholder = "21:30",
                            isError = !timeValid,
                        )
                        if (!timeValid) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "请按 24 小时制填写,如 21:30",
                                style = YohakuType.label12,
                                color = colors.error,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 预览是并列的次要动作,不做实底;整屏的 accent 实底只留给「保存」
            YohakuOutlineButton(
                text = "预览实时活动",
                onClick = { previewLiveUpdate(context) },
                modifier = Modifier.weight(1f),
            )
            YohakuButton(
                text = "保存",
                onClick = {
                    viewModel.save(
                        enabled = enabled,
                        leadMinutes = leadMinutes,
                        mode = mode,
                        tomorrowEnabled = tomorrowEnabled,
                        tomorrowTime = tomorrowTime,
                    )
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            )
        }
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
