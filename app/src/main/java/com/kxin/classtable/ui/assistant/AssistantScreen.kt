package com.kxin.classtable.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kxin.classtable.data.AiAction
import com.kxin.classtable.data.AiPages
import com.kxin.classtable.data.AiPlanner
import com.kxin.classtable.data.AiSettingRegistry
import com.kxin.classtable.design.EntranceTracker
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMarkdown
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberYohakuHaptics
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import java.time.Instant
import java.time.ZoneId

/**
 * AI 助手面板内容:一句话建 / 改 / 删课程、建 / 改 / 删日程、记偏好,或者询问课表安排。
 *
 * 背后是一个**只读工具循环**(见 data/AgentTools):模型先查事实,再给出 `actions`;
 * App 先做本地校验(必要时喂回模型修一次),把动作渲染成预览卡,**用户确认后才落库** ——
 * 模型幻觉不会直接改到课表。面板本身由 [AssistantOverlay] 从屏幕底部滑入(入口是课表页头的「✦ 助手」按钮)。
 *
 * 三条区分「谁在说话」的线索:用户是右对齐的 accent 实底气泡;助手是左对齐的浅底气泡,首行带
 * 「✦ 助手」标签、正文走 [YohakuMarkdown];待落库动作另起一张确认卡,嵌在助手气泡里。
 */
@Composable
fun AssistantPanel(
    onOpenSettings: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val agenda by viewModel.agenda.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingRoute by viewModel.pendingRoute.collectAsStateWithLifecycle()

    // 确认里若含「打开页面」,动作执行后会给出路由:关面板并把用户带过去。
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let {
            viewModel.clearPendingRoute()
            onNavigate(it)
        }
    }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 入场只演一次:打开面板时已存在的消息先记为「已见」,只有本次会话新到的消息才滑入 ——
    // 关掉再打开不重播一段旧对话的入场。
    val entrance = remember {
        EntranceTracker().apply { messages.forEach { markSeen(it.id) } }
    }
    // toast 退场动画期间文案不能变空,留住最后一条非空值。
    var lastToast by remember { mutableStateOf("") }
    LaunchedEffect(toast) {
        if (toast != null) {
            lastToast = toast.orEmpty()
            kotlinx.coroutines.delay(2_500)
            viewModel.clearToast()
        }
    }

    // 只有用户本来就贴在底部时才自动跟到最新 —— 向上翻历史时不被新消息拽走。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    var firstSettled by remember { mutableStateOf(false) }
    val newestIndex = messages.size - 1 + if (busy) 1 else 0
    // 首次打开直接落到最新一条(瞬时,不演一段长滚动)
    LaunchedEffect(Unit) {
        if (newestIndex >= 0) listState.scrollToItem(newestIndex)
        firstSettled = true
    }
    LaunchedEffect(messages.size, busy) {
        if (firstSettled && newestIndex >= 0 && atBottom) listState.animateScrollToItem(newestIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        YohakuTopBar(
            title = "AI 助手",
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

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = YohakuDimens.screenPadding,
                    end = YohakuDimens.screenPadding,
                    top = YohakuDimens.gapTight,
                    // 底部多留一截 = toast 胶囊的高度:回执浮在这里,不压住最后一条消息
                    bottom = YohakuDimens.gapTight + 34.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapCard),
            ) {
                if (messages.isEmpty()) {
                    item {
                        MessageEntrance(entrance = entrance, id = "empty-hint") { EmptyHint() }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageEntrance(
                        entrance = entrance,
                        id = message.id,
                        // 位置变化用 animateItem;淡入交给入场动画,别两处同时改 alpha。
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    ) {
                        if (message.fromUser) {
                            UserBubble(message.text)
                        } else {
                            AssistantBubble(
                                message = message,
                                courses = courses,
                                agenda = agenda,
                                settings = settings,
                                onConfirm = {
                                    haptics.select()
                                    viewModel.confirm(message.id)
                                },
                                onDismiss = {
                                    haptics.tick()
                                    viewModel.dismiss(message.id)
                                },
                            )
                        }
                    }
                }
                if (busy) {
                    item { TypingIndicator() }
                }
            }

            // toast 浮在消息区底部(不占布局,出现时不会把输入区整体顶动)
            ToastLayer(
                visible = toast != null,
                text = lastToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = YohakuDimens.gapTight),
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

/**
 * 消息入场:淡入 + 轻轻上移(只动合成层)。[EntranceTracker] 去重,同一条消息在一次面板
 * 存活期内只演一次;已经呈现过的(打开面板时就在的)直接给终值,不重播。
 */
@Composable
private fun MessageEntrance(
    entrance: EntranceTracker,
    id: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val animate = entrance.shouldAnimate(id)
    // 初值取终值当不需要动画时,避免呈现一帧半透明。
    val appear = remember(id) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(id) {
        if (animate) {
            appear.animateTo(
                targetValue = 1f,
                animationSpec = YohakuMotion.tween(YohakuMotion.durBase, YohakuMotion.easeOut),
            )
            entrance.markSeen(id)
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 16f
        },
    ) {
        content()
    }
}

/** 用户消息:右对齐的 accent 实底气泡,宽度随内容,最多占 85%。 */
@Composable
private fun UserBubble(text: String) {
    val colors = LocalYohakuColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(modifier = Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH), contentAlignment = Alignment.CenterEnd) {
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

/** 助手气泡底色(含等待态气泡):面板浮起面上叠一层极淡前景色,浅色下浅灰、深色下略亮的底。 */
@Composable
private fun assistantBubbleColor(): Color =
    LocalYohakuColors.current.neutral10.copy(alpha = 0.06f)

/**
 * 助手消息:左对齐的气泡,首行是身份标签(「✦ 助手」/「⚠ 出错了」)亮明这是 AI 说的,再是正文。
 *
 * 气泡底色:在面板浮起面上叠一层极淡前景色([assistantBubbleColor]) + 一道细边框 ——
 * 浅色下是白纸面上一抹浅灰,深色下是浮起面板里一块略亮的底;一套值同时适配两个主题,
 * 都读得出「这是一条收到的消息」,又不至于抢戏。
 * 正文成段(列表 / 小标题)时气泡随内容撑高、纵向铺满 [BUBBLE_MAX_WIDTH] 的上限。
 */
@Composable
private fun AssistantBubble(
    message: AssistantMessage,
    courses: List<Course>,
    agenda: List<AgendaEvent>,
    settings: AppSettings,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH),
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(
                shape = RoundedCornerShape(YohakuDimens.radiusSheet),
                color = if (message.failed) colors.error.copy(alpha = 0.10f) else assistantBubbleColor(),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (message.failed) colors.error.copy(alpha = 0.40f) else colors.line,
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = if (message.failed) "⚠ 出错了" else "✦ 助手",
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
                        ActionCard(
                            message = message,
                            courses = courses,
                            agenda = agenda,
                            settings = settings,
                            onConfirm = onConfirm,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

/** 待落库动作的确认卡:动作明细 + 「确认执行 / 取消」。嵌在助手气泡内,和「谁在说话」绑在一起。 */
@Composable
private fun ActionCard(
    message: AssistantMessage,
    courses: List<Course>,
    agenda: List<AgendaEvent>,
    settings: AppSettings,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    YohakuCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (message.actionsHandled) "✓ 已处理" else "将执行以下操作",
            style = YohakuType.label12,
            color = if (message.actionsHandled) colors.success else colors.neutral7,
        )
        Spacer(modifier = Modifier.height(6.dp))
        message.actions.forEach { action ->
            ActionPreview(
                action = action,
                course = courses.firstOrNull { it.id == courseIdOf(action) },
                agenda = agenda,
                settings = settings,
            )
        }
        if (!message.actionsHandled) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Row(verticalAlignment = Alignment.CenterVertically) {
                YohakuButton(text = "确认执行", onClick = onConfirm)
                Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
                YohakuOutlineButton(text = "取消", onClick = onDismiss)
            }
        }
    }
}

/**
 * 等待态:一只和助手消息同款的左对齐气泡,里面「✦ 助手」+ 三点错峰呼吸 ——
 * 比一行静止的「正在整理…」更像「在算」,也提前占住助手气泡的位置。
 * 初值取满亮:系统「移除动画」时无限动画停在初值,三点仍清晰可读,不留半亮的残缺态。
 */
@Composable
private fun TypingIndicator() {
    val colors = LocalYohakuColors.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(YohakuDimens.radiusSheet),
            color = assistantBubbleColor(),
            border = BorderStroke(1.dp, colors.line),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "✦ 助手", style = YohakuType.label12, color = colors.neutral7)
                Spacer(modifier = Modifier.width(YohakuDimens.gapTight))
                repeat(3) { index ->
                    val dotAlpha by transition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.25f,
                        animationSpec = infiniteRepeatable(
                            animation = YohakuMotion.tween(
                                durationMs = YohakuMotion.durBase,
                                easing = YohakuMotion.easeInOut,
                                delayMs = index * 160,
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "typingDot$index",
                    )
                    Box(
                        modifier = Modifier
                            .padding(end = 5.dp)
                            .size(6.dp)
                            .graphicsLayer { alpha = dotAlpha }
                            .clip(CircleShape)
                            .background(colors.neutral7),
                    )
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
            "加一门周一、周三的高数,1-16 周",
            "把高数第 3-5 周改到周二 5-6 节",
            "把「小组会议」改到周五 15:00",
            "删掉明天的「提交高数作业」",
            "这周还有哪些课?哪天最空?",
            "以后周三下午别给我排课",
        ).forEach {
            Text("· $it", style = YohakuType.copy13, color = colors.neutral8)
        }
        Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
        Text(
            "助手会先查一遍你的课表再作答;新建 / 修改 / 删除都会先列出来给你确认,再写进课表。",
            style = YohakuType.label12,
            color = colors.neutral6,
        )
    }
}

/**
 * toast 的出入场:淡入 + 自下轻推。
 *
 * 抽成独立组件是为了拿到干净的**顶层** [AnimatedVisibility]:直接嵌在 `Box`(其外层又是
 * `Column`)里时,Kotlin 会选中 `ColumnScope.AnimatedVisibility` 的重载,因作用域接收者
 * 不匹配而编译不过。
 */
@Composable
private fun ToastLayer(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(YohakuMotion.tween(YohakuMotion.durFast)) +
            slideInVertically(
                YohakuMotion.tween(YohakuMotion.durBase, YohakuMotion.easeOut),
            ) { it / 2 },
        exit = fadeOut(YohakuMotion.tween(YohakuMotion.durFast)) +
            slideOutVertically(YohakuMotion.tween(YohakuMotion.durFast)) { it / 2 },
        modifier = modifier,
    ) {
        ToastPill(text)
    }
}

/** 确认添加 / 取消后的回执:浮起面小胶囊,浮在消息区底部。 */
@Composable
private fun ToastPill(text: String) {
    if (text.isBlank()) return
    val colors = LocalYohakuColors.current
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = colors.raised,
        border = BorderStroke(1.dp, colors.line),
        shadowElevation = 4.dp,
    ) {
        Text(
            text = text,
            style = YohakuType.label12,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * 输入区:一枚胶囊输入框 + 右侧圆形发送键 —— 聊天语境不该出现「下划线表单 + 旁边一个方块按钮」
 * 那种填表长相。发送键在无内容 / 请求进行中置灰且不可点,可点 / 按下都带缓动与触感。
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (pressed && canSend) 0.88f else 1f,
        animationSpec = YohakuMotion.snappySpring(),
        label = "sendScale",
    )
    val sendBackground by animateColorAsState(
        targetValue = if (canSend) colors.accent else colors.neutral3,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "sendBackground",
    )
    val sendContent by animateColorAsState(
        targetValue = if (canSend) Color.White else colors.neutral6,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "sendContent",
    )
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
                .graphicsLayer {
                    scaleX = sendScale
                    scaleY = sendScale
                }
                .clip(CircleShape)
                .background(sendBackground)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = canSend,
                    onClick = {
                        haptics.tick()
                        onSend()
                    },
                )
                .semantics { contentDescription = "发送" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↑",
                style = YohakuType.title20,
                color = sendContent,
            )
        }
    }
}

/**
 * 预览卡片里的一条动作:动作类型 + **字段级明细**。改 / 删会解析出目标课程 [course] 或日程
 * ([agenda] 里按 id 匹配),逐字段给出「改前 → 改后」,而不是一长句。
 */
@Composable
private fun ActionPreview(
    action: AiAction,
    course: Course?,
    agenda: List<AgendaEvent>,
    settings: AppSettings,
) {
    val colors = LocalYohakuColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = when (action) {
                is AiAction.CreateCourse -> "新增课程"
                is AiAction.CreateAgenda -> "新增日程"
                is AiAction.UpdateCourse -> "修改课程"
                is AiAction.DeleteCourse -> "删除课程"
                is AiAction.UpdateAgenda -> "修改日程"
                is AiAction.DeleteAgenda -> "删除日程"
                is AiAction.Remember -> "记住偏好"
                is AiAction.SetSetting -> "修改设置"
                is AiAction.OpenSettings -> "打开页面"
            },
            style = YohakuType.label12,
            color = when (action) {
                is AiAction.UpdateCourse, is AiAction.SetSetting, is AiAction.UpdateAgenda -> colors.info
                is AiAction.DeleteCourse, is AiAction.DeleteAgenda -> colors.error
                is AiAction.Remember, is AiAction.OpenSettings -> colors.neutral7
                else -> colors.success
            },
        )
        when (action) {
            is AiAction.CreateCourse -> {
                ActionField(label = "名称", value = action.name)
                val days = action.days.ifEmpty { listOf(action.day) }
                ActionField(
                    label = "时间",
                    value = days.joinToString("、") { Course.weekdayName(it) } +
                        " 第${action.startSection}-${action.endSection}节",
                )
                if (action.weeks.isNotEmpty()) ActionField(label = "周次", value = weeksText(action.weeks))
                if (action.location.isNotBlank()) ActionField(label = "地点", value = action.location)
                if (action.teacher.isNotBlank()) ActionField(label = "教师", value = action.teacher)
            }

            is AiAction.CreateAgenda -> {
                ActionField(label = "标题", value = action.title)
                ActionField(label = "日期", value = action.date)
                ActionField(
                    label = "时间",
                    value = if (action.startTime != null && action.endTime != null) {
                        "${action.startTime}-${action.endTime}"
                    } else {
                        "全天"
                    },
                )
                if (action.location.isNotBlank()) ActionField(label = "地点", value = action.location)
            }

            is AiAction.UpdateAgenda -> {
                val event = agenda.firstOrNull { it.id == action.agendaId }
                ActionField(label = "日程", value = event?.title ?: "id=${action.agendaId}(已不存在)")
                if (event != null) {
                    val patched = AiPlanner.applyAgendaPatch(event, action)
                    DiffField("标题", event.title, action.title?.takeIf { it.isNotBlank() })
                    DiffField("日期", agendaDateText(event), patched?.let { agendaDateText(it) })
                    DiffField("时间", agendaTimeText(event), patched?.let { agendaTimeText(it) })
                    DiffField("地点", event.location, action.location)
                    DiffField("备注", event.note, action.note)
                }
            }

            is AiAction.DeleteAgenda -> {
                val event = agenda.firstOrNull { it.id == action.agendaId }
                ActionField(label = "日程", value = event?.title ?: "id=${action.agendaId}(已不存在)")
                if (event != null) {
                    ActionField(label = "日期", value = agendaDateText(event))
                    ActionField(label = "时间", value = agendaTimeText(event))
                }
            }

            is AiAction.UpdateCourse -> {
                ScopeField(action.scope, action.sourceWeeks)
                ActionField(label = "课程", value = course?.name ?: "id=${action.courseId}(已不存在)")
                if (course != null) {
                    val clear = action.clearFields.map { it.lowercase() }.toSet()
                    DiffField("名称", course.name, action.name)
                    DiffField("教师", course.teacher, if ("teacher" in clear) "" else action.teacher)
                    DiffField("地点", course.location, if ("location" in clear) "" else action.location)
                    DiffField("备注", course.note, if ("note" in clear) "" else action.note)
                    DiffField(
                        "星期",
                        Course.weekdaysText(course),
                        action.days?.joinToString("、") { Course.weekdayName(it) },
                    )
                    DiffField(
                        "节次",
                        "${course.startPeriod}-${course.endPeriod}",
                        if (action.startSection != null || action.endSection != null) {
                            "${action.startSection ?: course.startPeriod}-${action.endSection ?: course.endPeriod}"
                        } else {
                            null
                        },
                    )
                    DiffField("周次", course.weeksText(), action.weeks?.let { weeksText(it) })
                }
            }

            is AiAction.DeleteCourse -> {
                ScopeField(action.scope, action.sourceWeeks)
                ActionField(label = "课程", value = course?.name ?: "id=${action.courseId}(已不存在)")
                if (course != null) {
                    ActionField(
                        label = "时间",
                        value = "${Course.weekdaysText(course)} 第${course.startPeriod}-${course.endPeriod}节",
                    )
                    ActionField(label = "周次", value = course.weeksText())
                }
            }

            is AiAction.Remember -> ActionField(label = "记忆", value = action.text)

            is AiAction.SetSetting -> {
                val entry = AiSettingRegistry.find(action.key)
                ActionField(label = "设置", value = entry?.label ?: action.key)
                if (entry != null) {
                    val normalized = AiSettingRegistry.normalize(entry, action.value)
                    DiffField("值", entry.current(settings), normalized ?: action.value)
                } else {
                    ActionField(label = "值", value = action.value)
                }
            }

            is AiAction.OpenSettings -> {
                ActionField(label = "页面", value = AiPages.find(action.page)?.label ?: action.page)
            }
        }
    }
}

/** SELECTED_WEEKS 时补一行范围说明。 */
@Composable
private fun ScopeField(scope: String, sourceWeeks: List<Int>) {
    if (scope == AiAction.SCOPE_SELECTED && sourceWeeks.isNotEmpty()) {
        ActionField(label = "范围", value = "仅第 ${sourceWeeks.joinToString("、")} 周")
    }
}

/** 只在字段确实变了时渲染「改前 → 改后」。 */
@Composable
private fun DiffField(label: String, before: String, after: String?) {
    if (after == null || after == before) return
    val colors = LocalYohakuColors.current
    Row(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = label,
            style = YohakuType.label12,
            color = colors.neutral6,
            modifier = Modifier.width(40.dp),
        )
        Text(text = before.ifBlank { "空" }, style = YohakuType.copy13, color = colors.neutral6)
        Text(text = " → ", style = YohakuType.copy13, color = colors.neutral5)
        Text(text = after.ifBlank { "空" }, style = YohakuType.copy13, color = colors.neutral10)
    }
}

/** 改 / 删动作指向的课程 ID;其余动作为 null。 */
private fun courseIdOf(action: AiAction): String? = when (action) {
    is AiAction.UpdateCourse -> action.courseId
    is AiAction.DeleteCourse -> action.courseId
    else -> null
}

@Composable
private fun ActionField(label: String, value: String) {
    val colors = LocalYohakuColors.current
    Row(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = label,
            style = YohakuType.label12,
            color = colors.neutral6,
            modifier = Modifier.width(40.dp),
        )
        Text(text = value, style = YohakuType.copy13, color = colors.neutral9)
    }
}

/** 日程的日期文本(设备时区)。 */
private fun agendaDateText(event: AgendaEvent): String =
    Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/** 日程的时间文本:全天为「全天」,否则「HH:MM-HH:MM」。 */
private fun agendaTimeText(event: AgendaEvent): String {
    if (event.allDay) return "全天"
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDateTime()
    val end = Instant.ofEpochMilli(event.endAt).atZone(zone).toLocalDateTime()
    return "%02d:%02d-%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
}

private fun weeksText(weeks: List<Int>): String {
    val sorted = weeks.distinct().sorted()
    return if (sorted.size <= 6) {
        "第 " + sorted.joinToString("、") + " 周"
    } else {
        "第 ${sorted.first()}-${sorted.last()} 周"
    }
}

/** 气泡最大宽度占消息列的比例:留出对侧余白,一眼看出左右谁在说话。 */
private const val BUBBLE_MAX_WIDTH = 0.85f
