package com.aiverse.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiverse.core.theme.Spacing

/**
 * 思考强度滑块（普通样式，无特效）。
 *
 * 选中区域蓝色填充，未选白色；档位圆点选中蓝、未选白；无粒子动画、无渐变。
 * 左右拉动选档，松手吸附到最近档位并回调 [onChange]。
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
    var thumbIndex by remember { mutableStateOf(currentIndex.coerceIn(0, n - 1)) }
    LaunchedEffect(currentIndex) {
        thumbIndex = currentIndex.coerceIn(0, n - 1)
    }
    val trackRadius = 22.dp
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = Color.White.copy(alpha = 0.2f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(trackRadius))
            .background(Color.White.copy(alpha = 0.08f))
            .onSizeChanged { }
            .pointerInput(n, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        thumbIndex = offsetToIndex(offset.x, size.width.toFloat(), n)
                    },
                    onDrag = { change, _ ->
                        thumbIndex = offsetToIndex(change.position.x, size.width.toFloat(), n)
                    },
                    onDragEnd = { onChange(thumbIndex) },
                    onDragCancel = { onChange(thumbIndex) }
                )
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val w = size.width
            val h = size.height
            val ratio = if (n <= 1) 0f else thumbIndex.toFloat() / (n - 1)
            val fillW = w * ratio

            // 选中区域蓝色填充
            if (fillW > 0f) {
                drawRoundRect(
                    color = selectedColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(fillW, h),
                    cornerRadius = CornerRadius(h / 2f)
                )
            }

            // 档位圆点：选中蓝、未选白
            for (i in 0 until n) {
                val x = w * (if (n <= 1) 0.5f else i.toFloat() / (n - 1))
                val reached = i <= thumbIndex
                drawCircle(
                    color = if (reached) Color.White else unselectedColor,
                    radius = if (reached) 4f else 3f,
                    center = Offset(x, h / 2f)
                )
            }

            // 滑块（白色圆）
            drawCircle(
                color = Color.White,
                radius = 15f,
                center = Offset(w * ratio, h / 2f)
            )
        }
    }

    // 档位名称行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs, bottom = Spacing.sm),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        levels.forEachIndexed { i, label ->
            val active = i == thumbIndex
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun offsetToIndex(x: Float, width: Float, n: Int): Int {
    if (width <= 0f || n <= 1) return 0
    val ratio = (x / width).coerceIn(0f, 1f)
    return (ratio * (n - 1)).let { kotlin.math.round(it).toInt().coerceIn(0, n - 1) }
}
