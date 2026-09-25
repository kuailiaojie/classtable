package com.kxin.classtable.ui.settings

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.notify.DndController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClassDndViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun save(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setClassDndEnabled(enabled)
        // 关掉时立刻退出:否则这节课剩下的时间会一直停在免打扰里,直到下课闹钟(已被取消)之外没人管
        if (!enabled) DndController.exit(context)
        _saved.value = true
    }
}

/**
 * 上课免打扰页(从设置页「提醒 → 上课免打扰」进入)。
 *
 * 开关本身只是「愿不愿意」;真正能否切换系统免打扰取决于「勿扰访问权限」,所以权限状态与
 * 授权入口直接放在这一页上,不让用户自己去系统设置里翻。
 */
@Composable
fun ClassDndScreen(
    nav: NavHostController,
    viewModel: ClassDndViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var enabled by rememberSaveable { mutableStateOf(settings.classDndEnabled) }
    var hydrated by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(DndController.isGranted(context)) }

    // settings 流先发占位再发真实值;等真实值到位后只灌一次,避免编辑中被远端同步覆盖
    LaunchedEffect(settings) {
        if (!hydrated && settings != AppSettings()) {
            enabled = settings.classDndEnabled
            hydrated = true
        }
    }
    // 从系统「勿扰访问权限」页返回时刷新授权状态
    LifecycleResumeEffect(Unit) {
        granted = DndController.isGranted(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "上课免打扰", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = "上课免打扰") {
                SettingBlock(
                    title = "自动免打扰",
                    subtitle = "每节课开始时把手机切进免打扰(完全静音),下课时退回原来的状态。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(text = "开启", selected = enabled, onClick = { enabled = true })
                        YohakuChip(text = "关闭", selected = !enabled, onClick = { enabled = false })
                    }
                }
                DividerLine()
                SettingBlock(
                    title = "勿扰访问权限",
                    subtitle = if (granted) {
                        "已授权,应用可以在上课时切换免打扰。"
                    } else {
                        "系统要求先授权,应用才能切换免打扰;开启开关后还需要在这里授权一次。"
                    },
                ) {
                    if (!granted) {
                        YohakuOutlineButton(
                            text = "去系统设置授权",
                            onClick = { context.startActivity(DndController.settingsIntent()) },
                        )
                    }
                }
            }

            SettingsSection(title = "说明") {
                SettingBlock(title = "它什么时候生效") {
                    Text(
                        text = "按课表的作息时间来:有课的那一段进免打扰,课与课之间的休息退出;一天里连着上的课" +
                            "算作一段,中途不会退出。调休日停课不生效,补课日照常生效。\n\n" +
                            "进入时如果手机本来就已经是免打扰,应用不会去改它,下课自然也不会替你关掉;" +
                            "只有由应用切换的那一次,下课时才会按原样恢复。",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = "保存",
                onClick = { viewModel.save(enabled) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
