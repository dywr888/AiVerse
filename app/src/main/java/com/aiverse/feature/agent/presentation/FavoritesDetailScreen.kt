package com.aiverse.feature.agent.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiverse.R
import com.aiverse.core.theme.Spacing
import com.aiverse.core.ui.AppTextField
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Trash2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收藏详情页：展示单条收藏的完整内容，可编辑或删除。
 * 查看态内容支持长按选择复制（包 SelectionContainer），编辑态走 AppTextField。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesDetailScreen(
    viewModel: FavoriteViewModel,
    favoriteId: String?,
    onNavigateBack: () -> Unit
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val item = favorites.find { it.id == favoriteId }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.favorites_detail_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (item != null) {
                        if (editing) {
                            IconButton(
                                onClick = {
                                    viewModel.update(item.id, draft)
                                    editing = false
                                }
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Check,
                                    contentDescription = stringResource(R.string.common_save),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    draft = item.content
                                    editing = true
                                }
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.common_edit),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.delete(item.id)
                                onNavigateBack()
                            }
                        ) {
                            Icon(
                                imageVector = FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (item == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.favorites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            ) {
                if (item.sessionTitle.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.favorites_source_session, item.sessionTitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(Spacing.xs))
                }
                Text(
                    text = timeFmt.format(Date(item.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(Spacing.md))
                if (editing) {
                    AppTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 6,
                        maxLines = Int.MAX_VALUE,
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
