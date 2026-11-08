package com.aiverse.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiverse.core.ui.AppTextField
import com.aiverse.core.ui.SwipeToDeleteRow
import com.aiverse.feature.search.domain.SearchServiceOptions
import com.aiverse.feature.search.presentation.SearchProviderViewModel
import com.aiverse.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 搜索提供商管理页：服务列表（拖拽排序 / 左滑删除 / 单选当前）+ 结果数量 + 右上角加号弹窗。
 * 模仿 RikkaHub 的搜索服务管理页，去掉 RikkaHub 自带搜索。
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Composable
fun SearchProviderSection(
    viewModel: SearchProviderViewModel,
    onEdit: (kotlin.uuid.Uuid) -> Unit,
    showAddSheet: Boolean,
    onDismissAddSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val services = state.services
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.moveService(from.index, to.index)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(services, key = { it.id }) { service ->
                val index = services.indexOf(service)
                ReorderableItem(reorderableState, key = service.id) { isDragging ->
                    SwipeToDeleteRow(
                        onDelete = { viewModel.removeService(service.id) },
                        deleteEnabled = services.size > 1,
                        onClick = { onEdit(service.id) },
                        content = {
                            SearchProviderCard(
                                service = service,
                                selected = index == state.selected,
                                onSelect = { viewModel.selectService(index) },
                                dragging = isDragging
                            )
                        }
                    )
                }
            }
            item {
                ResultSizeRow(
                    resultSize = state.common.resultSize,
                    onUpdate = viewModel::updateResultSize
                )
            }
        }
    }

    if (showAddSheet) {
        AddSearchProviderDialog(
            onDismiss = onDismissAddSheet,
            onConfirm = { options ->
                viewModel.addService(options)
                onDismissAddSheet()
            }
        )
    }
}

@Composable
private fun SearchProviderCard(
    service: SearchServiceOptions,
    selected: Boolean,
    onSelect: () -> Unit,
    dragging: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = if (dragging) CardDefaults.cardElevation(defaultElevation = 6.dp) else CardDefaults.cardElevation()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(
                    text = service.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.search_provider_search_ability_search),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ResultSizeRow(
    resultSize: Int,
    onUpdate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(resultSize) { mutableStateOf(resultSize.toString()) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.search_provider_result_size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextField(
                value = text,
                onValueChange = { input ->
                    text = input.filter { it.isDigit() }.take(2)
                    text.toIntOrNull()?.let(onUpdate)
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                supportingText = {
                    Text(
                        text = stringResource(R.string.search_provider_result_size_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }
}

@Composable
private fun AddSearchProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (SearchServiceOptions) -> Unit
) {
    var selectedType by remember {
        mutableStateOf(SearchServiceOptions.TYPES.keys.first())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_provider_add)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(SearchServiceOptions.TYPES.keys.toList()) { type ->
                    val name = SearchServiceOptions.TYPES[type] ?: "Unknown"
                    val isSelected = selectedType == type
                    Card(
                        onClick = { selectedType = type },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(SearchServiceOptions.FACTORIES.getValue(selectedType)()) }
            ) {
                Text(stringResource(R.string.search_provider_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.search_provider_cancel))
            }
        }
    )
}
