package com.aiverse.feature.plugins.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiverse.R
import com.aiverse.core.ui.AppSwitch
import com.aiverse.feature.plugins.data.InstalledPlugin
import com.aiverse.feature.plugins.domain.model.PluginPermissions
import kotlinx.coroutines.launch

/** 脚本插件管理页：列表（启用/禁用/卸载）+ 安装 zip。 */
@Composable
fun PluginListSection(viewModel: PluginListViewModel) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val installing by viewModel.installing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var confirmUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.install(uri) }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error ?: return@LaunchedEffect)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message ?: return@LaunchedEffect)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_plugins),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { zipPicker.launch("application/zip") },
                    enabled = !installing
                ) {
                    if (installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.plugins_install))
                }
            }

            Spacer(Modifier.size(8.dp))

            if (plugins.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.plugins_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(plugins, key = { it.manifest.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onToggleEnabled = { enabled ->
                                viewModel.setEnabled(plugin.manifest.id, enabled)
                            },
                            onUninstall = { confirmUninstall = plugin }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    confirmUninstall?.let { plugin ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text(stringResource(R.string.plugins_confirm_uninstall_title)) },
            text = {
                Text(stringResource(R.string.plugins_confirm_uninstall_message, plugin.manifest.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(plugin.manifest.id)
                    confirmUninstall = null
                }) {
                    Text(
                        text = stringResource(R.string.plugins_uninstall),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = null }) {
                    Text(stringResource(R.string.plugins_cancel))
                }
            }
        )
    }
}

@Composable
private fun PluginCard(
    plugin: InstalledPlugin,
    onToggleEnabled: (Boolean) -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = plugin.manifest.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val meta = listOfNotNull(
                        plugin.manifest.version,
                        plugin.manifest.author.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                AppSwitch(
                    checked = plugin.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (plugin.manifest.description.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = plugin.manifest.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (plugin.manifest.permissions.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    plugin.manifest.permissions.forEach { perm ->
                        val label = PluginPermissions.LABELS[perm] ?: perm
                        val dangerous = perm in PluginPermissions.DANGEROUS
                        val color = if (dangerous) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            modifier = Modifier
                                .background(
                                    color = color.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onUninstall) {
                    Text(
                        text = stringResource(R.string.plugins_uninstall),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
