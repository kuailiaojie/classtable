package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染:只覆盖实际会用到的子集 ——
 * 标题(`#` / `##` / `###`)、无序列表(`-` `*` `+`,含折行续写)、分隔线(`---`)、
 * 引用(`>`)、空行分段,以及行内 `**加粗**`、`` `代码` ``、`[文字](链接)`。
 *
 * 不引第三方 Markdown 库:更新说明的形态有限,自绘才能让排版落回 Yohaku 的字阶与中性档,
 * 而不是把 GitHub 的网页样式搬进纸面。
 */
@Composable
fun YohakuMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = YohakuType.copy13,
    baseColor: Color = LocalYohakuColors.current.neutral9,
) {
    val colors = LocalYohakuColors.current
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val previous = blocks.getOrNull(index - 1)
            val gap = when {
                index == 0 -> 0.dp
                block is Block.Heading -> 16.dp
                block is Block.Rule -> 12.dp
                block is Block.Bullet && previous is Block.Bullet -> 4.dp
                else -> 8.dp
            }
            if (gap > 0.dp) Spacer(modifier = Modifier.height(gap))
            when (block) {
                is Block.Heading -> Text(
                    text = inline(block.text, colors),
                    style = when (block.level) {
                        1 -> YohakuType.copy16
                        2 -> YohakuType.copy15
                        else -> YohakuType.copy14
                    },
                    color = colors.neutral10,
                )

                is Block.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "·",
                        style = baseStyle,
                        color = colors.neutral6,
                        modifier = Modifier.width(14.dp),
                    )
                    Text(text = inline(block.text, colors), style = baseStyle, color = baseColor)
                }

                is Block.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.neutral3),
                )

                is Block.Paragraph -> Text(
                    text = inline(block.text, colors),
                    style = baseStyle,
                    color = baseColor,
                )
            }
        }
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data object Rule : Block
}

/** 按行切块;以空格开头的行并入上一块(Markdown 里的折行续写)。 */
private fun parseMarkdown(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    source.lineSequence().forEach { raw ->
        val trimmed = raw.trim()
        val continuation = raw.isNotEmpty() && raw.first().isWhitespace() &&
            trimmed.isNotEmpty() && blocks.isNotEmpty()
        if (continuation) {
            when (val last = blocks.removeAt(blocks.lastIndex)) {
                is Block.Bullet -> blocks += last.copy(text = "${last.text} $trimmed")
                is Block.Paragraph -> blocks += last.copy(text = "${last.text} $trimmed")
                else -> {
                    blocks += last
                    blocks += Block.Paragraph(trimmed)
                }
            }
            return@forEach
        }
        when {
            trimmed.isEmpty() -> Unit
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> blocks += Block.Rule
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += Block.Heading(level, trimmed.drop(level).trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
                blocks += Block.Bullet(trimmed.drop(2).trim())
            trimmed.startsWith("> ") -> blocks += Block.Paragraph(trimmed.drop(2).trim())
            else -> blocks += Block.Paragraph(trimmed)
        }
    }
    return blocks
}

/**
 * 行内标记 → AnnotatedString。加粗用 Medium(500)而不是 Bold:与字阶一致,避免合成粗体。
 */
private fun inline(source: String, colors: YohakuColors): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith("**", index) -> {
                val end = source.indexOf("**", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = colors.neutral10)) {
                        append(source.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(source[index])
                    index++
                }
            }

            source[index] == '`' -> {
                val end = source.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(SpanStyle(fontFamily = YohakuFonts.Mono, color = colors.neutral10)) {
                        append(source.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(source[index])
                    index++
                }
            }

            source[index] == '[' -> {
                val labelEnd = source.indexOf(']', index + 1)
                val urlStart = if (labelEnd > index && source.getOrNull(labelEnd + 1) == '(') labelEnd + 2 else -1
                val urlEnd = if (urlStart > 0) source.indexOf(')', urlStart) else -1
                if (urlEnd > urlStart && urlStart > 0) {
                    withStyle(SpanStyle(color = colors.accent)) { append(source.substring(index + 1, labelEnd)) }
                    index = urlEnd + 1
                } else {
                    append(source[index])
                    index++
                }
            }

            else -> {
                append(source[index])
                index++
            }
        }
    }
}
