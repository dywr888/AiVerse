package com.aiverse.feature.git.presentation.component

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiverse.R
import com.aiverse.core.theme.Radius
import com.aiverse.core.theme.Spacing
import com.aiverse.feature.git.domain.model.GitHubCheckSummary
import com.aiverse.feature.git.domain.model.GitHubComment
import com.aiverse.feature.git.domain.model.GitHubPullRequest
import com.aiverse.feature.git.domain.model.GitHubPullRequestCommit
import com.aiverse.feature.git.domain.model.GitHubPullRequestFile
import com.aiverse.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import kotlinx.coroutines.launch

/**
 * GitHub Pull Request 详情页（全屏覆盖）：状态、标题、分支与增删统计、正文、CI 检查、
 * 评论与发表入口，以及「让 AI 审查 / 修复 CI」两个入口（把结构化 prompt 交给聊天页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitPullRequestDetailPage(
    pr: GitHubPullRequest?,
    loading: Boolean,
    checks: GitHubCheckSummary?,
    checksLoading: Boolean,
    comments: List<GitHubComment>,
    commentsLoading: Boolean,
    commits: List<GitHubPullRequestCommit>,
    commitsLoading: Boolean,
    files: List<GitHubPullRequestFile>,
    filesLoading: Boolean,
    actionBusy: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddComment: (String) -> Unit = {},
    onDeleteComment: (Long) -> Unit = {},
    onClosePull: () -> Unit = {},
    onReopenPull: () -> Unit = {},
    onMergePull: (String) -> Unit = {},
    onAskAgent: (String) -> Unit = {},
    toastMessage: String? = null,
    onToastConsumed: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            onToastConsumed()
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = settingsPageBackground(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_pr_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground())
            )
        }
    ) { padding ->
        when {
            loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.git_pulls_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            pr == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.git_pulls_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }
            }
            else -> PullRequestDetailContent(
                pr = pr,
                checks = checks,
                checksLoading = checksLoading,
                comments = comments,
                commentsLoading = commentsLoading,
                commits = commits,
                commitsLoading = commitsLoading,
                files = files,
                filesLoading = filesLoading,
                actionBusy = actionBusy,
                snackbarHostState = snackbarHostState,
                modifier = Modifier.padding(padding),
                onAddComment = onAddComment,
                onDeleteComment = onDeleteComment,
                onClosePull = onClosePull,
                onReopenPull = onReopenPull,
                onMergePull = onMergePull,
                onAskAgent = onAskAgent
            )
        }
    }
}

@Composable
private fun PullRequestDetailContent(
    pr: GitHubPullRequest,
    checks: GitHubCheckSummary?,
    checksLoading: Boolean,
    comments: List<GitHubComment>,
    commentsLoading: Boolean,
    commits: List<GitHubPullRequestCommit>,
    commitsLoading: Boolean,
    files: List<GitHubPullRequestFile>,
    filesLoading: Boolean,
    actionBusy: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAddComment: (String) -> Unit,
    onDeleteComment: (Long) -> Unit,
    onClosePull: () -> Unit,
    onReopenPull: () -> Unit,
    onMergePull: (String) -> Unit,
    onAskAgent: (String) -> Unit
) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var commentInput by remember { mutableStateOf("") }
    var deleteConfirmCommentId by remember { mutableStateOf<Long?>(null) }
    var mergeConfirmVisible by remember { mutableStateOf(false) }
    var closeConfirmVisible by remember { mutableStateOf(false) }
    var mergeMethod by remember { mutableStateOf("merge") }

    val stateLabel = when {
        pr.draft -> stringResource(R.string.git_pr_state_draft)
        pr.displayState == "merged" -> stringResource(R.string.git_pr_state_merged)
        pr.displayState == "closed" -> stringResource(R.string.git_pr_state_closed)
        else -> stringResource(R.string.git_pr_state_open)
    }
    val checksLabel = when {
        checks == null -> stringResource(R.string.git_pr_ci_unknown)
        checks.state == "failure" -> stringResource(R.string.git_pr_ci_failure)
        checks.state == "pending" -> stringResource(R.string.git_pr_ci_pending)
        checks.state == "success" -> stringResource(R.string.git_pr_ci_success)
        else -> stringResource(R.string.git_pr_ci_none)
    }
    val failedChecks = checks?.runs
        ?.filter { it.outcome == "failure" }
        ?.map { it.name }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val failedLabel = failedChecks.joinToString(", ").ifBlank { checksLabel }
    val reviewPrompt = stringResource(
        R.string.git_pr_ai_review_prompt,
        pr.number,
        pr.title,
        pr.head.ref,
        pr.base.ref,
        checksLabel
    )
    val fixCiPrompt = stringResource(
        R.string.git_pr_ai_fix_ci_prompt,
        pr.number,
        pr.title,
        failedLabel
    )
    val linkCopiedMessage = stringResource(R.string.git_pr_link_copied)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.md,
            bottom = Spacing.xl
        )
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PullStateIcon(
                    state = pr.displayState,
                    draft = pr.draft,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "#${pr.number}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                ChecksBadge(summary = checks)
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = pr.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pr.userLogin.isNotEmpty()) {
                    IssueAvatar(name = pr.userLogin, size = 18.dp)
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text(
                    text = stringResource(
                        R.string.git_pr_meta,
                        pr.userLogin,
                        formatIssueDate(pr.createdAt),
                        pr.head.ref,
                        pr.base.ref
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    R.string.git_pr_stats,
                    pr.commits,
                    pr.changedFiles,
                    pr.additions,
                    pr.deletions
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.git_pr_comments, pr.comments),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("pr-link", pr.htmlUrl))
                            )
                            snackbarHostState.showSnackbar(linkCopiedMessage)
                        }
                    }
                ) { Text(stringResource(R.string.git_pr_action_copy_link)) }
                TextButton(onClick = { runCatching { uriHandler.openUri(pr.htmlUrl) } }) {
                    Text(stringResource(R.string.git_pr_action_open_browser))
                }
                TextButton(onClick = { onAskAgent(reviewPrompt) }) {
                    Text(stringResource(R.string.git_pr_action_ai_review))
                }
                if (checks?.state == "failure") {
                    TextButton(onClick = { onAskAgent(fixCiPrompt) }) {
                        Text(stringResource(R.string.git_pr_action_ai_fix_ci))
                    }
                }
                if (pr.isOpen && !pr.draft) {
                    TextButton(
                        onClick = { mergeConfirmVisible = true },
                        enabled = !actionBusy
                    ) { Text(stringResource(R.string.git_pr_action_merge)) }
                }
                if (pr.isOpen) {
                    TextButton(
                        onClick = { closeConfirmVisible = true },
                        enabled = !actionBusy
                    ) { Text(stringResource(R.string.git_pr_action_close)) }
                } else if (!pr.isMerged) {
                    TextButton(onClick = onReopenPull, enabled = !actionBusy) {
                        Text(stringResource(R.string.git_pr_action_reopen))
                    }
                }
            }
        }

        if (pr.labels.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    pr.labels.forEach { label -> LabelChip(label) }
                }
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }

        item {
            val body = pr.body?.trim().orEmpty()
            if (body.isEmpty()) {
                Text(
                    text = stringResource(R.string.git_issue_body_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    GitIssueMarkdown(text = body, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = stringResource(R.string.git_pr_files_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        when {
            filesLoading -> item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            files.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.git_pr_files_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> itemsIndexed(files, key = { index, file -> "$index-${file.filename}" }) { _, file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.filename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = fileStatusLabel(file.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = "+${file.additions} -${file.deletions}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = stringResource(R.string.git_pr_commits_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        when {
            commitsLoading -> item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            commits.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.git_pr_commits_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> itemsIndexed(commits, key = { index, commit -> "$index-${commit.sha}" }) { _, commit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = commit.sha.take(7).ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(Spacing.xxl * 2f)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = commit.message.ifBlank { stringResource(R.string.git_pr_commit_unnamed) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${commit.authorName} • ${formatIssueDate(commit.date)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = stringResource(R.string.git_pr_ci_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        when {
            checksLoading -> item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            checks == null || checks.runs.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.git_pr_ci_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> itemsIndexed(checks.runs, key = { index, run -> "$index-${run.name}" }) { _, run ->
                val color = checkOutcomeColor(run.outcome)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (run.htmlUrl.isNotBlank()) {
                                Modifier.clickable { runCatching { uriHandler.openUri(run.htmlUrl) } }
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        Modifier
                            .padding(top = Spacing.xs)
                            .size(Spacing.sm)
                            .background(color, CircleShape)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = run.name.ifBlank { stringResource(R.string.git_pr_ci_unnamed) },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = checkOutcomeLabel(run.outcome),
                                style = MaterialTheme.typography.labelSmall,
                                color = color
                            )
                        }
                        if (!run.summary.isNullOrBlank()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = run.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = stringResource(R.string.git_pr_comments_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (commentsLoading) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        } else if (comments.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.git_issue_no_comments),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            itemsIndexed(comments, key = { _, comment -> comment.id }) { _, comment ->
                val commentBody = comment.body?.trim().orEmpty()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (comment.userLogin.isNotEmpty()) {
                                IssueAvatar(name = comment.userLogin, size = 18.dp)
                                Spacer(Modifier.width(Spacing.xs))
                            }
                            Text(
                                text = comment.userLogin,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = formatIssueDate(comment.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { deleteConfirmCommentId = comment.id },
                                enabled = !actionBusy
                            ) {
                                Text(
                                    stringResource(R.string.git_issue_delete_comment),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (commentBody.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.xs))
                            GitIssueMarkdown(text = commentBody, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedTextField(
                    value = commentInput,
                    onValueChange = { commentInput = it },
                    placeholder = { Text(stringResource(R.string.git_pr_comment_placeholder)) },
                    enabled = !actionBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.md)
                )
                TextButton(
                    onClick = {
                        val text = commentInput.trim()
                        if (text.isNotEmpty()) {
                            onAddComment(text)
                            commentInput = ""
                        }
                    },
                    enabled = !actionBusy && commentInput.isNotBlank()
                ) { Text(stringResource(R.string.git_pr_action_comment)) }
            }
        }
    }

    deleteConfirmCommentId?.let { commentId ->
        AlertDialog(
            onDismissRequest = { deleteConfirmCommentId = null },
            title = { Text(stringResource(R.string.git_issue_delete_comment)) },
            text = { Text(stringResource(R.string.git_pr_delete_comment_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmCommentId = null
                        onDeleteComment(commentId)
                    }
                ) { Text(stringResource(R.string.git_issue_delete_comment)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmCommentId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (mergeConfirmVisible) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) mergeConfirmVisible = false },
            title = { Text(stringResource(R.string.git_pr_merge_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.git_pr_merge_confirm))
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        val methods = listOf(
                            "merge" to R.string.git_pr_merge_method_merge,
                            "squash" to R.string.git_pr_merge_method_squash,
                            "rebase" to R.string.git_pr_merge_method_rebase
                        )
                        methods.forEach { (value, label) ->
                            FilterChip(
                                selected = mergeMethod == value,
                                onClick = { mergeMethod = value },
                                label = { Text(stringResource(label)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mergeConfirmVisible = false
                        onMergePull(mergeMethod)
                    },
                    enabled = !actionBusy
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { mergeConfirmVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (closeConfirmVisible) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) closeConfirmVisible = false },
            title = { Text(stringResource(R.string.git_pr_close_title)) },
            text = { Text(stringResource(R.string.git_pr_close_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeConfirmVisible = false
                        onClosePull()
                    },
                    enabled = !actionBusy
                ) { Text(stringResource(R.string.git_pr_action_close)) }
            },
            dismissButton = {
                TextButton(onClick = { closeConfirmVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun fileStatusLabel(status: String): String = when (status) {
    "added" -> stringResource(R.string.git_pr_file_added)
    "removed" -> stringResource(R.string.git_pr_file_removed)
    "modified" -> stringResource(R.string.git_pr_file_modified)
    "renamed" -> stringResource(R.string.git_pr_file_renamed)
    else -> stringResource(R.string.git_pr_file_changed)
}
