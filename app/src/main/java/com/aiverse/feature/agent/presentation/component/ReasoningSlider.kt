package com.aiverse.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 思考强度离散分段滑块（discrete segmented slider）。
 *
 * 顶部细轨道 + 7 个档位刻度圆点（选中档放大为主色圆点），档位名锚点对齐在每个刻度下方，
 * 档位名即刻度标签。无粗胶囊、无滑动圆点：点击或横向拖动立即跳到手指所在档位（不连续
 * 滑动），松手回调 [onChange]。
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
    val primary = MaterialTheme.colorScheme.primary
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val dotUnselected = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .pointerInput(n, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var index = segmentIndexFromX(down.position.x, size.width.toFloat(), n)
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
                        val newIndex = segmentIndexFromX(change.position.x, size.width.toFloat(), n)
                        if (newIndex != index) {
                            index = newIndex
                            selectedIndex = index
                        }
                        change.consume()
                    }
                }
            }
    ) {
        // 轨道 + 刻度圆点区
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val w = size.width
            val cy = size.height / 2f
            val trackH = 4.dp.toPx()
            val radius = trackH / 2f
            // 轨道左右端对齐首尾刻度（段中心）
            val left = w / (2f * n)
            val right = w * (n - 0.5f) / n
            val trackW = right - left
            // 轨道背景
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, cy - trackH / 2f),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(radius)
            )
            // 选中档之前的轨道填充主色
            val selX = w * (selectedIndex + 0.5f) / n
            if (selX > left) {
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(left, cy - trackH / 2f),
                    size = Size(selX - left, trackH),
                    cornerRadius = CornerRadius(radius)
                )
            }
            // 档位刻度圆点：选中档放大主色，其余灰色小点
            for (i in 0 until n) {
                val cx = w * (i + 0.5f) / n
                drawCircle(
                    color = if (i == selectedIndex) primary else dotUnselected,
                    radius = if (i == selectedIndex) 7.dp.toPx() else 3.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }
        }
        // 档位名：锚点对齐每个刻度下方
        Row(Modifier.fillMaxWidth().height(22.dp)) {
            levels.forEachIndexed { i, label ->
                val active = i == selectedIndex
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) primary else unselectedTextColor,
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

/** 按段中心选择：x 落在第几段（段宽 = width/n）即第几档。 */
private fun segmentIndexFromX(x: Float, width: Float, n: Int): Int {
    if (width <= 0f || n <= 1) return 0
    val seg = width / n
    return (x / seg).toInt().coerceIn(0, n - 1)
}
