package com.kxin.classtable.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.widget.NextClassWidgetReceiver
import com.kxin.classtable.widget.TodayWidgetReceiver
import com.kxin.classtable.widget.TomorrowWidgetReceiver
import com.kxin.classtable.widget.WidgetPrefs
import com.kxin.classtable.widget.WidgetPrefsStore
import com.kxin.classtable.widget.WidgetSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WidgetSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val prefs: StateFlow<WidgetPrefs> = WidgetPrefsStore.flow(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WidgetPrefs())

    /** 改一项就落盘并立刻刷新所有小组件(否则要等系统下次周期刷新才看到)。 */
    fun update(transform: (WidgetPrefs) -> WidgetPrefs) = viewModelScope.launch {
        val next = transform(prefs.value)
        WidgetPrefsStore.save(context, next)
        WidgetPrefsStore.refreshWidgets(context)
    }
}

/**
 * 小组件设置:三款小组件(今日 / 明日 / 下节课)的显示内容与样式。
 *
 * 选项存在本机 DataStore(与提醒等设置同一个),改完立即 `updateAll` —— 桌面上已放的小组件
 * 会一起变。强调色不在这里单独设,它跟随「设置 → 外观」。
 */
@Composable
fun WidgetSettingsScreen(
    nav: NavHostController,
    viewModel: WidgetSettingsViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "桌面小组件", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "改完立即生效,桌面上已放的小组件会一起刷新。强调色跟随「外观」。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            SettingsSection(title = "添加到桌面") {
                PinRow("今日课表", "4×2 · 今天全部课程", TodayWidgetReceiver::class.java)
                DividerLine()
                PinRow("明日课程", "2×2 · 明天要上什么", TomorrowWidgetReceiver::class.java)
                DividerLine()
                PinRow("下节课", "1×1 · 下一节的名称 / 时间 / 地点", NextClassWidgetReceiver::class.java)
            }

            SettingsSection(title = "显示内容") {
                SettingBlock(title = "课程行") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            YohakuChip(
                                text = "时刻",
                                selected = prefs.showTime,
                                onClick = { viewModel.update { it.copy(showTime = !it.showTime) } },
                            )
                            YohakuChip(
                                text = "节次",
                                selected = prefs.showPeriod,
                                onClick = { viewModel.update { it.copy(showPeriod = !it.showPeriod) } },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            YohakuChip(
                                text = "地点",
                                selected = prefs.showLocation,
                                onClick = { viewModel.update { it.copy(showLocation = !it.showLocation) } },
                            )
                            YohakuChip(
                                text = "教师",
                                selected = prefs.showTeacher,
                                onClick = { viewModel.update { it.copy(showTeacher = !it.showTeacher) } },
                            )
                        }
                    }
                }
                DividerLine()
                SettingBlock(title = "课表最多显示") {
                    // 五个片在一行会挤爆窄屏(320dp),减到四个
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 6, 8).forEach { n ->
                            YohakuChip(
                                text = "$n 门",
                                selected = prefs.maxRows == n,
                                onClick = { viewModel.update { it.copy(maxRows = n) } },
                            )
                        }
                    }
                }
            }

            SettingsSection(title = "样式") {
                SettingBlock(title = "行距") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "标准",
                            selected = !prefs.compact,
                            onClick = { viewModel.update { it.copy(compact = false) } },
                        )
                        YohakuChip(
                            text = "紧凑",
                            selected = prefs.compact,
                            onClick = { viewModel.update { it.copy(compact = true) } },
                        )
                    }
                }
                DividerLine()
                SettingBlock(title = "底色") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "纸面",
                            selected = prefs.surface == WidgetSurface.PAPER,
                            onClick = { viewModel.update { it.copy(surface = WidgetSurface.PAPER) } },
                        )
                        YohakuChip(
                            text = "深色",
                            selected = prefs.surface == WidgetSurface.INK,
                            onClick = { viewModel.update { it.copy(surface = WidgetSurface.INK) } },
                        )
                        YohakuChip(
                            text = "轻透",
                            selected = prefs.surface == WidgetSurface.CLEAR,
                            onClick = { viewModel.update { it.copy(surface = WidgetSurface.CLEAR) } },
                        )
                    }
                }
            }
        }
    }
}

/** 一行「添加到桌面」:点击走系统固定流程,桌面不支持时提示手动添加。 */
@Composable
private fun PinRow(title: String, subtitle: String, receiver: Class<out GlanceAppWidgetReceiver>) {
    val context = LocalContext.current
    SettingRowWithSubtitle(
        title = title,
        subtitle = subtitle,
        onClick = { pinWidget(context, receiver) },
    )
}

/** 请求把指定小组件固定到桌面;Launcher 不支持时提示手动添加。 */
private fun pinWidget(context: Context, receiver: Class<out GlanceAppWidgetReceiver>) {
    val component = ComponentName(context.applicationContext, receiver)
    val manager = AppWidgetManager.getInstance(context)
    // extras 与添加成功回调 PendingIntent 均不需要,传 null
    val ok = manager.requestPinAppWidget(component, null, null)
    if (!ok) {
        Toast.makeText(context, "当前桌面不支持直接添加,请长按桌面空白处手动添加", Toast.LENGTH_SHORT).show()
    }
}
