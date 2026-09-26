package com.kxin.classtable.ui.settings

import android.content.Context
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.BlockCorner
import com.kxin.classtable.data.GridDensity
import com.kxin.classtable.data.NameSize
import com.kxin.classtable.data.TimetablePrefs
import com.kxin.classtable.data.TimetablePrefsStore
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TimetableDisplayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val prefs: StateFlow<TimetablePrefs> = TimetablePrefsStore.flow(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimetablePrefs())

    /** 改一项就落盘 —— 回到课表页立即生效,不必点保存。 */
    fun update(transform: (TimetablePrefs) -> TimetablePrefs) = viewModelScope.launch {
        TimetablePrefsStore.save(context, transform(prefs.value))
    }
}

/**
 * 课表显示:周视图课程块的显示内容与样式。
 *
 * 与「桌面小组件」同一套做法 —— 改一项立即落盘、立即生效;选项只存本机,不参与云同步。
 */
@Composable
fun TimetableDisplayScreen(
    nav: NavHostController,
    viewModel: TimetableDisplayViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "课表显示", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // SettingsSection 自带 screenPadding,这一层不能再加横向内边距
            Text(
                text = "调整周视图里课程块的样子,改完立即生效。",
                style = YohakuType.label12,
                color = colors.neutral7,
                modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            SettingsSection(title = "显示内容") {
                SettingBlock(title = "课程块") {
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
            }

            SettingsSection(title = "行高密度") {
                SettingBlock(title = "一屏放下多少节", subtitle = "紧凑档在小屏可能需要上下滚动。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "宽松",
                            selected = prefs.density == GridDensity.LOOSE,
                            onClick = { viewModel.update { it.copy(density = GridDensity.LOOSE) } },
                        )
                        YohakuChip(
                            text = "标准",
                            selected = prefs.density == GridDensity.STANDARD,
                            onClick = { viewModel.update { it.copy(density = GridDensity.STANDARD) } },
                        )
                        YohakuChip(
                            text = "紧凑",
                            selected = prefs.density == GridDensity.COMPACT,
                            onClick = { viewModel.update { it.copy(density = GridDensity.COMPACT) } },
                        )
                    }
                }
            }

            SettingsSection(title = "课程名") {
                SettingBlock(title = "行数") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2).forEach { n ->
                            YohakuChip(
                                text = "$n 行",
                                selected = prefs.nameLines == n,
                                onClick = { viewModel.update { it.copy(nameLines = n) } },
                            )
                        }
                    }
                }
                DividerLine()
                SettingBlock(title = "字号") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "小",
                            selected = prefs.nameSize == NameSize.SMALL,
                            onClick = { viewModel.update { it.copy(nameSize = NameSize.SMALL) } },
                        )
                        YohakuChip(
                            text = "中",
                            selected = prefs.nameSize == NameSize.MEDIUM,
                            onClick = { viewModel.update { it.copy(nameSize = NameSize.MEDIUM) } },
                        )
                        YohakuChip(
                            text = "大",
                            selected = prefs.nameSize == NameSize.LARGE,
                            onClick = { viewModel.update { it.copy(nameSize = NameSize.LARGE) } },
                        )
                    }
                }
            }

            SettingsSection(title = "块样式") {
                SettingBlock(title = "圆角") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "方",
                            selected = prefs.corner == BlockCorner.SQUARE,
                            onClick = { viewModel.update { it.copy(corner = BlockCorner.SQUARE) } },
                        )
                        YohakuChip(
                            text = "中",
                            selected = prefs.corner == BlockCorner.MEDIUM,
                            onClick = { viewModel.update { it.copy(corner = BlockCorner.MEDIUM) } },
                        )
                        YohakuChip(
                            text = "圆",
                            selected = prefs.corner == BlockCorner.ROUND,
                            onClick = { viewModel.update { it.copy(corner = BlockCorner.ROUND) } },
                        )
                    }
                }
                DividerLine()
                SettingBlock(title = "底色") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        YohakuChip(
                            text = "课程淡彩",
                            selected = prefs.showTint,
                            onClick = { viewModel.update { it.copy(showTint = true) } },
                        )
                        YohakuChip(
                            text = "纸面",
                            selected = !prefs.showTint,
                            onClick = { viewModel.update { it.copy(showTint = false) } },
                        )
                    }
                }
            }
        }
    }
}
