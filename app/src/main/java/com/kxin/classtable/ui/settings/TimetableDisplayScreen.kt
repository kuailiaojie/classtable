package com.kxin.classtable.ui.settings

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.BlockCorner
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.GridDensity
import com.kxin.classtable.data.NameSize
import com.kxin.classtable.data.TimetablePrefs
import com.kxin.classtable.data.TimetablePrefsStore
import com.kxin.classtable.design.CoursePalette
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.model.CourseColorScheme
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
class TimetableDisplayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val courseRepository: CourseRepository,
) : ViewModel() {
    val prefs: StateFlow<TimetablePrefs> = TimetablePrefsStore.flow(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimetablePrefs())

    /** 「重新配色」完成后给用户的一句回执;null = 这一页还没点过。 */
    private val _recolorNote = MutableStateFlow<String?>(null)
    val recolorNote: StateFlow<String?> = _recolorNote.asStateFlow()

    /** 改一项就落盘 —— 回到课表页立即生效,不必点保存。 */
    fun update(transform: (TimetablePrefs) -> TimetablePrefs) = viewModelScope.launch {
        TimetablePrefsStore.save(context, transform(prefs.value))
    }

    fun reassignColors() = viewModelScope.launch {
        _recolorNote.value = runCatching { courseRepository.reassignAllColors() }.fold(
            onSuccess = { if (it > 0) "已重新分配 $it 门课程的颜色" else "没有需要重新分配的课程" },
            onFailure = { "重新配色失败,请重试" },
        )
    }
}

/**
 * 课表显示:周视图课程块的显示内容与样式。
 *
 * 与「桌面小组件」同一套做法 —— 改一项立即落盘、立即生效;选项只存本机,不参与云同步。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimetableDisplayScreen(
    nav: NavHostController,
    viewModel: TimetableDisplayViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val recolorNote by viewModel.recolorNote.collectAsStateWithLifecycle()
    var confirmingRecolor by remember { mutableStateOf(false) }

    // 色板预览:当前方案铺开的整圈淡彩。跟着主题与档位走,换档立刻看得到区别。
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val swatches = remember(dark, prefs.colorScheme) {
        CoursePalette.swatches(prefs.colorScheme, dark)
    }

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

            SettingsSection(title = "配色方案") {
                SettingBlock(
                    title = "自动配色",
                    subtitle = "新建 / 导入课程时给它定一个色相并钉在课程上,不同课程不会分到同一个颜色;" +
                        "色相数就是能排下的课程数。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourseColorScheme.entries.forEach { scheme ->
                            YohakuChip(
                                text = scheme.label,
                                selected = prefs.colorScheme == scheme,
                                onClick = { viewModel.update { it.copy(colorScheme = scheme) } },
                            )
                        }
                    }
                    Text(
                        text = prefs.colorScheme.description,
                        style = YohakuType.label12,
                        color = colors.neutral6,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        swatches.forEach { swatch ->
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                                    .background(swatch),
                            )
                        }
                    }
                }
                DividerLine()
                SettingBlock(
                    title = "重新配色",
                    subtitle = "按上面的方案把全部课程的色相重排一遍。自己指定过颜色的课程保持不动。",
                ) {
                    YohakuOutlineButton(
                        text = "重新配色全部课程",
                        onClick = { confirmingRecolor = true },
                    )
                    recolorNote?.let { note ->
                        Text(
                            text = note,
                            style = YohakuType.label12,
                            color = colors.neutral7,
                            modifier = Modifier.padding(top = 8.dp),
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

        if (confirmingRecolor) {
            YohakuDialog(
                onDismissRequest = { confirmingRecolor = false },
                title = "重新配色?",
                actions = {
                    YohakuDialogAction(
                        text = "取消",
                        onClick = { confirmingRecolor = false },
                    )
                    YohakuDialogAction(
                        text = "重新配色",
                        accent = true,
                        onClick = {
                            confirmingRecolor = false
                            viewModel.reassignColors()
                        },
                    )
                },
            ) {
                Text(
                    text = "会按「${prefs.colorScheme.label}」给所有课程重新分配色相," +
                        "课表里的颜色可能和你习惯的不一样 —— 之前就分到同一个颜色的课,到这一步才会分开。",
                    style = YohakuType.copy14,
                    color = LocalYohakuColors.current.neutral9,
                )
            }
        }
    }
}
