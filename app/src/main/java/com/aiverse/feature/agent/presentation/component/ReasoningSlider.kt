package com.aiverse.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 思考强度分段单选按钮（segmented control）。
 *
 * 7 档（不思考/自动/低/中/高/极高/最高）等宽分段，选中段主色胶囊高亮，档位名即刻度，
 * 无滑动圆点。点击或横向拖动立即跳到手指所在档位（不连续滑动），松手回调 [onChange]。
 */
@Composable
internal fun ReasoningSlider(
    levels: List<String>,
    currentIndex: Int,
    /** 关闭档在 [levels] 中的下标；-1 表示无关闭档（模型思考恒开）。 */
    closedIndex: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (levels.isEmpty()) return
    val n = levels.size
    var selectedIndex by remember { mutableStateOf(currentIndex.coerceIn(0, n - 1)) }
    LaunchedEffect(currentIndex) {
        selectedIndex = currentIndex.coerceIn(0, n - 1)
    }
    val selectedColor = MaterialTheme.colorScheme.primary
    val selectedTextColor = MaterialTheme.colorScheme.onPrimary
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .padding(4.dp)
            .pointerInput(n, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var index = offsetToIndex(down.position.x, size.width.toFloat(), n)
                    // 按下即跳到对应档位（直接跳档，无滑动圆点）
                    selectedIndex = index
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            onChange(index)
                            break
                        }
                        if (change.isConsumed) {
                            onChange(index)
                            break
                        }
                        val newIndex = offsetToIndex(change.position.x, size.width.toFloat(), n)
                        if (newIndex != index) {
                            index = newIndex
                            selectedIndex = index
                        }
                        change.consume()
                    }
                }
            }
    ) {
        Row(Modifier.fillMaxWidth()) {
            levels.forEachIndexed { i, label ->
                val active = i == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) selectedColor else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) selectedTextColor else unselectedTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private fun offsetToIndex(x: Float, width: Float, n: Int): Int {
    if (width <= 0f || n <= 1) return 0
    val ratio = (x / width).coerceIn(0f, 1f)
    return (ratio * (n - 1)).let { kotlin.math.round(it).toInt().coerceIn(0, n - 1) }
}
