package com.kxin.classtable.ui.assistant

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.data.AiAction
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.Dates
import java.time.LocalDate

/**
 * AI 助手页:一句话建课 / 建日程,或者询问课表安排。
 *
 * 模型返回的**动作先在聊天气泡里预览**,点「确认添加」才落库 —— 模型幻觉不会直接改到课表。
 */
@Composable
fun AssistantScreen(
    nav: NavHostController,
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
            .background(colors.paper)
            .imePadding(),
    ) {
        YohakuTopBar(
            title = "AI 助手",
            onBack = null,
            actions = {
                Text(
                    text = "设置",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .yohakuTouchTarget()
                        .clickable { nav.navigate("ai_key") }
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
            verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapTight),
        ) {
            if (messages.isEmpty()) {
                item { EmptyHint() }
            }
            items(messages, key = { it.id }) { message ->
                if (message.fromUser) UserBubble(message.text) else AssistantBubble(
                    message = message,
                    onConfirm = { viewModel.confirm(message.id) },
                    onDismiss = { viewModel.dismiss(message.id) },
                )
            }
            if (busy) {
                item { Text("思考中…", style = YohakuType.copy13, color = colors.neutral6) }
            }
        }

        if (toast != null) {
            Text(
                text = toast.orEmpty(),
                style = YohakuType.copy13,
                color = colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = YohakuDimens.screenPadding,
                    vertical = YohakuDimens.gapTight,
                ),
            verticalAlignment = Alignment.Bottom,
        ) {
            YohakuTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "说一句话,如:周三下午加一门高数,1-16 周",
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
            )
            Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
            YohakuButton(
                text = "发送",
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = !busy && input.isNotBlank(),
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val colors = LocalYohakuColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(YohakuDimens.radiusCard),
            color = colors.accent,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(
                text = text,
                style = YohakuType.copy15,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    message: AssistantMessage,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message.text,
            style = YohakuType.copy15,
            color = if (message.failed) colors.error else colors.neutral9,
        )
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
