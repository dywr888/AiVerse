package com.aiverse.feature.settings.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiverse.R
import com.aiverse.core.theme.ChatBubbleStyle
import com.aiverse.core.theme.Spacing
import com.aiverse.core.ui.AdaptiveModalBottomSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/** 聊天气泡样式选择弹窗：列出 8 种，每种带模式名预览气泡。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBubbleStyleSheet(
    selected: ChatBubbleStyle,
    onSelect: (ChatBubbleStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            SectionLabel(stringResource(R.string.settings_chat_bubble))
            ChatBubbleStyle.entries.forEach { style ->
                val isSelected = style == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(style) }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChatBubblePreview(style)
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = stringResource(style.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.weight(1f))
                    if (isSelected) {
                        Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** 迷你预览气泡：按样式渲染，文字即该样式名称。 */
@Composable
private fun ChatBubblePreview(style: ChatBubbleStyle) {
    val color = when (style) {
        ChatBubbleStyle.CLASSIC_OUTLINE,
        ChatBubbleStyle.SOFT_SHADOW,
        ChatBubbleStyle.MINIMAL_WHITE -> MaterialTheme.colorScheme.surface
        ChatBubbleStyle.DARK_MODE -> Color(0xFF121212)
        ChatBubbleStyle.COMIC -> Color.White
        ChatBubbleStyle.FRESH_GREEN -> Color(0xFF4CAF50)
        ChatBubbleStyle.HEARTBEAT -> Color(0xFFF2F2F2)
        ChatBubbleStyle.ORIGINAL -> MaterialTheme.colorScheme.primary
    }
    val textColor = when (style) {
        ChatBubbleStyle.DARK_MODE -> Color.White
        ChatBubbleStyle.FRESH_GREEN -> Color(0xFF1B1B1B)
        ChatBubbleStyle.ORIGINAL -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when (style) {
        ChatBubbleStyle.CLASSIC_OUTLINE -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface)
        ChatBubbleStyle.COMIC -> BorderStroke(2.dp, Color.Black)
        ChatBubbleStyle.HEARTBEAT -> BorderStroke(1.5.dp, Color(0xFFBDBDBD))
        else -> null
    }
    val shape = if (style == ChatBubbleStyle.FRESH_GREEN) RoundedCornerShape(24.dp) else RoundedCornerShape(10.dp)
    val elevation = if (style == ChatBubbleStyle.SOFT_SHADOW) 4.dp else 0.dp

    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = shape,
            color = color,
            border = border,
            shadowElevation = elevation
        ) {
            Text(
                text = stringResource(style.labelRes),
                color = textColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        if (style == ChatBubbleStyle.COMIC) {
            Canvas(
                modifier = Modifier
                    .size(8.dp, 12.dp)
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, Color.Black)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.sm)
    )
}
