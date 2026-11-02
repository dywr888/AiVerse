package com.aiverse.feature.credentials.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aiverse.R
import com.aiverse.core.theme.Spacing
import com.aiverse.core.ui.AdaptiveModalBottomSheet
import com.aiverse.feature.settings.presentation.component.SettingsGroup
import com.aiverse.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit3

/**
 * 「添加凭据」底部弹层：提供手动配置凭据入口。
 *
 * 版式与 [com.aiverse.feature.settings.presentation.component.McpAddSheet]、
 * [com.aiverse.feature.settings.presentation.component.SkillAddSheet] 保持一致：
 * 标题 + 分组卡片内的可点行（图标 / 标题 / 说明 / 右箭头）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CredentialAddSheet(
    onManual: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.credential_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Edit3,
                    title = stringResource(R.string.credential_add_manual_title),
                    subtitle = stringResource(R.string.credential_add_manual_subtitle),
                    onClick = onManual
                )
            }
        }
    }
}
