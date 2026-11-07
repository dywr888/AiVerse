package com.aiverse.feature.agent.presentation.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiverse.core.theme.Radius
import com.aiverse.core.theme.Spacing
import com.aiverse.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp

/**
 * 上下文用量明细面板（可折叠，内嵌在输入框上方、占满横向）：
 * 展开态顶部「已用 tokens / 上下文上限（当前模型）/ 占用比例」+ 右侧倒三角，进度条上标出自动压缩
 * 阈值界限（如 85%），下方「达到阈值时自动压缩」说明与「立即压缩」按钮，再下为文件/MCP/思考分类明细。
 * 点右侧倒三角折叠成一行（左边进度条 + 右边展开按钮），点行/按钮重新展开完整信息。
 */
@Composable
internal fun ContextUsageDetailCard(
    detail: ContextUsageDetail,
    /** 立即压缩当前会话上下文。 */
    onCompact: () -> Unit,
    /** 自动压缩触发阈值（上下文窗口百分比），进度条上在此位置画界限标记。 */
    thresholdPercent: Int = 85,
    modifier: Modifier = Modifier
) {
    var collapsed by remember { mutableStateOf(false) }
    val usedRatio = detail.usedRatio.coerceIn(0f, 1f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        AnimatedContent(
            targetState = collapsed,
            transitionSpec = {
                (fadeIn(tween(200)) + expandVertically(tween(200))) togetherWith
                    (fadeOut(tween(200)) + shrinkVertically(tween(200)))
            },
            label = "usageCardCollapse"
        ) { isCollapsed ->
        if (isCollapsed) {
            // 折叠态：一行，左边进度条 + 右边可点展开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { collapsed = false }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                    color = contextRingColor(usedRatio),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                Icon(
                    FeatherIcons.ChevronDown,
                    contentDescription = stringResource(R.string.common_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Column(modifier = Modifier.padding(Spacing.md)) {
                // 已用 tokens / 上下文上限（当前模型）/ 占用比例 + 倒三角折叠
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.chat_context_used_tokens, detail.usedTokens),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.chat_context_budget_tokens, detail.budgetTokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = "${(usedRatio * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = contextRingColor(usedRatio)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    IconButton(
                        onClick = { collapsed = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            FeatherIcons.ChevronUp,
                            contentDescription = stringResource(R.string.common_collapse),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.sm))

                // 进度条 + 阈值界限标记（如 85% 处竖线，提示到达自动压缩线，非强制）
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val thresholdX = maxWidth * (thresholdPercent.coerceIn(0, 100) / 100f)
                    LinearProgressIndicator(
                        progress = { usedRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = contextRingColor(usedRatio),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(6.dp)
                            .offset(x = thresholdX - 1.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }

                Spacer(Modifier.height(Spacing.xs))

                // 自动压缩说明 + 立即压缩
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.chat_context_auto_compact, thresholdPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onCompact,
                        enabled = detail.usedTokens > 0,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.chat_context_compact_now),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.sm))

                // 分类明细
                DetailRow(stringResource(R.string.chat_context_file_tokens), detail.fileTokens, detail.usedTokens, MaterialTheme.colorScheme.primary)
                DetailRow(stringResource(R.string.chat_context_mcp_tokens), detail.mcpTokens, detail.usedTokens, MaterialTheme.colorScheme.tertiary)
                DetailRow(stringResource(R.string.chat_context_thinking_tokens), detail.thinkingTokens, detail.usedTokens, MaterialTheme.colorScheme.secondary)
                DetailRow(stringResource(R.string.chat_context_other_tokens), detail.otherTokens, detail.usedTokens, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: Int, total: Int, color: androidx.compose.ui.graphics.Color) {
    val pct = if (total > 0) (value * 100f / total) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$value  ($pct%)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}
