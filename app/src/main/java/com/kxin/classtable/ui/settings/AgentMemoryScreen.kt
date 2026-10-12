package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgentMemoryViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val memory: StateFlow<List<String>> = settingsRepository.agentMemory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(text: String) = viewModelScope.launch {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) settingsRepository.setAgentMemory(memory.value + trimmed)
    }

    fun remove(text: String) = viewModelScope.launch {
        settingsRepository.setAgentMemory(memory.value - text)
    }

    fun clear() = viewModelScope.launch { settingsRepository.setAgentMemory(emptyList()) }
}

/**
 * 助手记忆:助手在对话里说「记住…」时写进来的长期偏好,用户可增删改清。
 *
 * 与「对话记录」不同,记忆是**跨会话**注入上下文的少量事实;所以放在设置里让用户能看见、能删。
 */
@Composable
fun AgentMemoryScreen(
    nav: NavHostController,
    viewModel: AgentMemoryViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val memory by viewModel.memory.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        YohakuDialog(
            onDismissRequest = { confirmClear = false },
            title = "清空助手记忆?",
            actions = {
                YohakuDialogAction(text = "取消", onClick = { confirmClear = false })
                YohakuDialogAction(
                    text = "清空",
                    accent = true,
                    onClick = {
                        confirmClear = false
                        viewModel.clear()
                    },
                )
            },
        ) {
            Text(
                text = "共 ${memory.size} 条。清空后助手不再记得这些偏好,此操作不可撤销。",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "助手记忆", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "助手在对话里说「记住…」时会写到这里,之后的对话会参考它们。" +
                    "记忆只存本机,可随时删除或清空。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            Row(verticalAlignment = Alignment.Bottom) {
                YohakuTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "例如:我周三下午没空,别排课",
                )
                Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
                YohakuButton(
                    text = "添加",
                    enabled = draft.isNotBlank(),
                    onClick = {
                        viewModel.add(draft)
                        draft = ""
                    },
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            if (memory.isEmpty()) {
                Text(
                    text = "还没有记忆。",
                    style = YohakuType.copy14,
                    color = colors.neutral7,
                )
            } else {
                memory.forEachIndexed { index, entry ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.neutral3),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry,
                            style = YohakuType.copy15,
                            color = colors.neutral9,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
                        Text(
                            text = "删除",
                            style = YohakuType.copy13,
                            color = colors.accent,
                            modifier = Modifier.clickable { viewModel.remove(entry) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }

        if (memory.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(YohakuDimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(YohakuDimens.gapTight),
            ) {
                YohakuOutlineButton(
                    text = "清空全部",
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
