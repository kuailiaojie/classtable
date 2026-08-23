package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text

/**
 * 下划线式输入框。底线 n-5(border);聚焦 = accent 底线;错误 = 蘇芳。
 * 课程名输入请传 serifStyle=true(所见即所得,纸感)。
 */
@Composable
fun YohakuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    serifStyle: Boolean = false,
    isError: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    val colors = LocalYohakuColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val underlineColor = when {
        isError -> colors.error
        isFocused -> colors.accent
        else -> colors.neutral5
    }
    val inputStyle = (if (serifStyle) YohakuType.courseName else YohakuType.copy15).copy(color = colors.neutral9)

    Column(modifier = modifier) {
        if (label != null) {
            Text(text = label, style = YohakuType.label12, color = colors.neutral7)
            Spacer(modifier = Modifier.height(4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = inputStyle,
            cursorBrush = SolidColor(colors.accent),
            singleLine = singleLine,
            maxLines = maxLines,
            interactionSource = interactionSource,
            decorationBox = { inner ->
                Box(modifier = Modifier.padding(vertical = 8.dp)) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = inputStyle, color = colors.neutral5)
                    }
                    inner()
                }
            },
        )
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(underlineColor))
    }
}
