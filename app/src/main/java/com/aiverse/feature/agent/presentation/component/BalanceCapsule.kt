package com.aiverse.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiverse.core.theme.Radius
import com.aiverse.core.theme.Spacing
import com.aiverse.feature.settings.domain.model.BalanceConfig
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02

/**
 * 输入框快捷栏的余额胶囊状态（按 provider id 缓存于 ViewModel）。
 */
data class BalanceCapsuleState(
    val providerId: String,
    val enabled: Boolean,
    /** 余额文本（如 `110.00`）；null 表示尚未加载成功。 */
    val text: String?,
    val loading: Boolean,
    /** 错误信息（非空时余额显示为错误态）。 */
    val error: String?,
    val config: BalanceConfig
)

/**
 * 余额胶囊（输入框快捷栏，只读展示）：开启时显示「袋装金钱图标 + 余额数字」，点主体刷新。
 * 开关与配置编辑在模型编辑页（「余额信息」项），快捷栏不可修改（与切换模型同风格）。
 */
@Composable
internal fun BalanceCapsule(
    state: BalanceCapsuleState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 显示由快捷栏 showBalance 与模型编辑页「余额信息」开关（state.enabled）共同决定（ChatInputBar 层），这里只负责渲染。
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(enabled = !state.loading, onClick = onRefresh)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            imageVector = HugeIcons.MoneyBag02,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = when {
                state.loading -> "…"
                state.text != null -> state.text
                else -> "--"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp)
        )
    }
}
