package com.aiverse.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiverse.R
import com.aiverse.core.theme.Spacing
import com.aiverse.core.ui.AdaptiveModalBottomSheet
import com.aiverse.feature.settings.data.remote.ExtensionItem
import com.aiverse.feature.settings.presentation.ExtensionDownloadState
import com.aiverse.feature.settings.presentation.ExtensionMarketState
import compose.icons.FeatherIcons
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.RefreshCw

/**
 * 扩展市场弹层：从后端 `?action=get` 拉取扩展列表（名称 / 版本 / 介绍），
 * 每项可下载 APK 并拉起系统安装器（用户在系统弹窗里确认安装）。
 * 版式对齐「拉取模型」弹层：标题行 + 分组卡片列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionMarketSheet(
    marketState: ExtensionMarketState,
    downloadState: ExtensionDownloadState,
    onRefresh: () -> Unit,
    onInstall: (ExtensionItem) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.extension_market_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onRefresh) {
                    Icon(
                        imageVector = FeatherIcons.RefreshCw,
                        contentDescription = stringResource(R.string.extension_market_refresh),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.extension_market_refresh),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            when (marketState) {
                is ExtensionMarketState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is ExtensionMarketState.Error -> {
                    SettingsGroup {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Text(
                                    text = stringResource(R.string.extension_market_load_failed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = marketState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                is ExtensionMarketState.Success -> {
                    if (marketState.items.isEmpty()) {
                        SettingsGroup {
                            Box(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.extension_market_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 480.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            items(marketState.items, key = { it.name }) { item ->
                                SettingsGroup {
                                    ExtensionMarketRow(
                                        item = item,
                                        downloadState = downloadState,
                                        onInstall = { onInstall(item) }
                                    )
                                }
                            }
                        }
                    }
                }
                ExtensionMarketState.Idle -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.extension_market_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 单个扩展条目：名称 + 版本 + 介绍 + 下载安装（或进度 / 失败 / 需授权）。 */
@Composable
private fun ExtensionMarketRow(
    item: ExtensionItem,
    downloadState: ExtensionDownloadState,
    onInstall: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (item.version.isNotBlank()) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = item.version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when (downloadState) {
            is ExtensionDownloadState.Downloading ->
                if (downloadState.name == item.name) {
                    Column(horizontalAlignment = Alignment.End) {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.width(96.dp).heightIn(min = 4.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.extension_market_downloading),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    DownloadButton(onInstall)
                }
            is ExtensionDownloadState.NeedPermission ->
                if (downloadState.name == item.name) {
                    TextButton(onClick = onInstall) {
                        Text(
                            text = stringResource(R.string.extension_market_install_permission),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    DownloadButton(onInstall)
                }
            is ExtensionDownloadState.Failed ->
                if (downloadState.name == item.name) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.extension_market_download_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = onInstall) {
                            Text(
                                text = stringResource(R.string.extension_market_retry),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                } else {
                    DownloadButton(onInstall)
                }
            ExtensionDownloadState.Idle -> DownloadButton(onInstall)
        }
    }
}

@Composable
private fun DownloadButton(onInstall: () -> Unit) {
    TextButton(onClick = onInstall) {
        Icon(
            imageVector = FeatherIcons.DownloadCloud,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = stringResource(R.string.extension_market_download),
            style = MaterialTheme.typography.labelMedium
        )
    }
}
