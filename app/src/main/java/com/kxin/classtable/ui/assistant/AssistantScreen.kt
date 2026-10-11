package com.kxin.classtable.ui.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kxin.classtable.data.AiAction
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMarkdown
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.Dates

/**
 * AI 助手面板内容:一句话建课 / 建日程,或者询问课表安排。
 *
 * 模型返回的**动作先在聊天气泡里预览**,点「确认添加」才落库 —— 模型幻觉不会直接改到课表。
 * 面板本身由 [AssistantOverlay] 从课表页顶部滑入(入口是课表页头的「✦ 助手」按钮)。
 *
 * 三条区分「谁在说话」的线索:用户是右对齐的 accent 实底气泡;助手是带「✦ 整理结果」标签的
 * 无气泡正文(走 [YohakuMarkdown],列表 / 加粗都能落回纸面字阶);待落库动作另起一张确认卡。
 */
@Composable
fun AssistantPanel(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, busy) {
        val target = messages.size + if (busy) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2_500)
            viewModel.clearToast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        YohakuTopBar(
            title = "AI 助手",
            onBack = onClose,
            actions = {
                Text(
                    text = "设置",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .yohakuTouchTarget()
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 8.dp),
                )
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                horizontal = YohakuDimens.screenPadding,
                vertical = YohakuDimens.gapTight,
            ),
            verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapCard),
        ) {
            if (messages.isEmpty()) {
                item { EmptyHint() }
            }
            items(messages, key = { it.id }) { message ->
                if (message.fromUser) {
                    UserBubble(message.text)
                } else {
                    AssistantBubble(
                        message = message,
                        onConfirm = { viewModel.confirm(message.id) },
                        onDismiss = { viewModel.dismiss(message.id) },
                    )
                }
            }
            if (busy) {
                item { AssistantLabel(text = "正在整理…", color = colors.neutral6) }
            }
        }

        if (toast != null) {
            Text(
                text = toast.orEmpty(),
                style = YohakuType.label12,
                color = colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 4.dp),
            )
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            canSend = !busy && input.isNotBlank(),
            onSend = {
                viewModel.send(input)
                input = ""
            },
        )
    }
}

/** 用户消息:右对齐的 accent 实底气泡,宽度随内容,最多占 85%。 */
@Composable
private fun UserBubble(text: String) {
    val colors = LocalYohakuColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(modifier = Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(YohakuDimens.radiusSheet),
                color = colors.accent,
            ) {
                Text(
                    text = text,
                    style = YohakuType.copy15,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * 助手消息:先一行身份标签(「✦ 整理结果」/「⚠ 出错了」)亮明这是 AI 说的,再是正文。
 * 正文无气泡 —— 与用户气泡拉开层级,也更适合承载成段的列表 / 小标题。
 */
@Composable
private fun AssistantBubble(
    message: AssistantMessage,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (message.failed) "⚠ 出错了" else "✦ 整理结果",
            style = YohakuType.label12,
            color = if (message.failed) colors.error else colors.neutral7,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (message.failed) {
            Text(text = message.text, style = YohakuType.copy15, color = colors.error)
        } else {
            YohakuMarkdown(
                text = message.text,
                baseStyle = YohakuType.copy15,
                baseColor = colors.neutral9,
            )
        }
        if (message.actions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            YohakuCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (message.actionsHandled) "已处理" else "将执行以下操作",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(6.dp))
                message.actions.forEach { action ->
                    Text(
                        text = describeAction(action),
                        style = YohakuType.copy13,
                        color = colors.neutral9,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (!message.actionsHandled) {
                    Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        YohakuButton(text = "确认添加", onClick = onConfirm)
                        Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
                        YohakuOutlineButton(text = "取消", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/** 等待态:沿用助手身份标签,下面一行提示,避免「裸文本飘在空里」。 */
@Composable
private fun AssistantLabel(text: String, color: Color) {
    val colors = LocalYohakuColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "✦ 整理结果", style = YohakuType.label12, color = colors.neutral7)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = text, style = YohakuType.copy15, color = color)
    }
}

@Composable
private fun EmptyHint() {
    val colors = LocalYohakuColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = YohakuDimens.gapCard)) {
        Text("可以这样用:", style = YohakuType.label12, color = colors.neutral7)
        Spacer(modifier = Modifier.height(6.dp))
        listOf(
            "周三下午加一门高数,1-16 周,教三 201",
            "明晚 8 点提醒我交作业",
            "这周还有哪些课?哪天最空?",
        ).forEach {
            Text("· $it", style = YohakuType.copy13, color = colors.neutral8)
        }
        Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
        Text(
            "AI 只负责理解与建议;新建课程 / 日程会先给你确认,再写进课表。",
            style = YohakuType.label12,
            color = colors.neutral6,
        )
    }
}

/**
 * 输入区:一枚胶囊输入框 + 右侧圆形发送键 —— 聊天语境不该出现「下划线表单 + 旁边一个方块按钮」
 * 那种填表长相。发送键在无内容 / 请求进行中置灰且不可点。
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.screenPadding, vertical = YohakuDimens.gapTight),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(percent = 50),
            color = colors.raised,
            border = BorderStroke(1.dp, colors.line),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = YohakuType.copy15.copy(color = colors.neutral9),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 4,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "说一句话,如:周三下午加一门高数,1-16 周",
                                style = YohakuType.copy15,
                                color = colors.neutral5,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) colors.accent else colors.neutral3)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↑",
                style = YohakuType.title20,
                color = if (canSend) Color.White else colors.neutral6,
            )
        }
    }
}

/** 预览卡片里的一行人类可读描述。 */
private fun describeAction(action: AiAction): String = when (action) {
    is AiAction.CreateCourse -> buildString {
        append("新建课程:")
        append(Dates.weekdayLabel(action.day))
        append(" 第").append(action.startSection).append('-').append(action.endSection).append("节 ")
        append(action.name)
        if (action.location.isNotBlank()) append(" @").append(action.location)
        if (action.teacher.isNotBlank()) append(" ").append(action.teacher)
        if (action.weeks.isNotEmpty()) append(" · ").append(weeksText(action.weeks))
    }

    is AiAction.CreateAgenda -> buildString {
        append("新建日程:")
        append(action.date)
        if (action.startTime != null && action.endTime != null) {
            append(' ').append(action.startTime).append('-').append(action.endTime)
        } else {
            append(" 全天")
        }
        append(' ').append(action.title)
        if (action.location.isNotBlank()) append(" @").append(action.location)
    }
}

private fun weeksText(weeks: List<Int>): String {
    val sorted = weeks.distinct().sorted()
    return if (sorted.size <= 6) {
        "第 " + sorted.joinToString("、") + " 周"
    } else {
        "第 ${sorted.first()}-${sorted.last()} 周"
    }
}
