package com.aiverse.feature.git.domain.model

import com.google.gson.annotations.SerializedName

/** Pull Request 的 head/base 分支引用。 */
data class GitHubPullRequestRef(
    val ref: String = "",
    val sha: String = "",
    val label: String = ""
)

/**
 * GitHub Pull Request 的视图模型。由 [com.aiverse.feature.git.domain.GitHubPullRequestsRepository]
 * 用 Gson 解析 GitHub REST API 的 JSON 得到，纯数据、无 Android 依赖。
 */
data class GitHubPullRequest(
    val number: Long,
    val title: String,
    val body: String? = null,
    /** "open" / "closed"；已合并的 PR state 仍是 closed，需结合 [merged] 判断。 */
    val state: String = "open",
    val draft: Boolean = false,
    val merged: Boolean = false,
    @SerializedName("merged_at") val mergedAt: String? = null,
    val user: GitHubIssueUser? = null,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("closed_at") val closedAt: String? = null,
    val comments: Int = 0,
    @SerializedName("review_comments") val reviewComments: Int = 0,
    val commits: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerializedName("changed_files") val changedFiles: Int = 0,
    val labels: List<IssueLabel> = emptyList(),
    val assignees: List<GitHubIssueUser> = emptyList(),
    val head: GitHubPullRequestRef = GitHubPullRequestRef(),
    val base: GitHubPullRequestRef = GitHubPullRequestRef(),
    @SerializedName("html_url") val htmlUrl: String = ""
) {
    val userLogin: String get() = user?.login ?: ""
    val isOpen: Boolean get() = state == "open"

    /**
     * 是否已合并。列表接口（GET /pulls）返回的摘要对象不含 merged 布尔字段（恒为 false），
     * 但带 merged_at 时间戳；详情接口才返回 merged=true。二者取或，保证列表/详情判定一致。
     */
    val isMerged: Boolean get() = merged || mergedAt != null

    /** 列表/详情展示用的状态：merged 优先于 closed。 */
    val displayState: String get() = when {
        isMerged -> "merged"
        state == "closed" -> "closed"
        else -> "open"
    }
}

/** 单条 CI 检查（GitHub check-run 或旧版 commit status）。 */
data class GitHubCheckRun(
    val name: String = "",
    /** queued / in_progress / completed；旧版 status 转换后直接写 success/failure/pending。 */
    val status: String = "",
    val conclusion: String? = null,
    @SerializedName("html_url") val htmlUrl: String = "",
    val summary: String? = null
) {
    /** 归并后的检查结果：success / failure / pending。 */
    val outcome: String get() = GitHubCheckSummary.checkOutcome(status, conclusion)
}

/** 一个提交上全部 CI 检查的汇总。 */
data class GitHubCheckSummary(
    val total: Int = 0,
    val success: Int = 0,
    val failure: Int = 0,
    val pending: Int = 0,
    val runs: List<GitHubCheckRun> = emptyList(),
    /** 旧版 commit status 的整体状态；没有 statuses 时为 null。 */
    val combinedState: String? = null
) {
    /** 整体状态：failure / pending / success / none。 */
    val state: String get() = when {
        failure > 0 -> "failure"
        pending > 0 -> "pending"
        total > 0 -> "success"
        combinedState == "failure" -> "failure"
        combinedState == "pending" -> "pending"
        combinedState == "success" -> "success"
        else -> "none"
    }

    companion object {
        val EMPTY = GitHubCheckSummary()

        /** 按 GitHub check-run 的 status/conclusion 归并成 success / failure / pending。 */
        fun checkOutcome(status: String, conclusion: String?): String = when {
            status != "completed" -> "pending"
            conclusion in setOf("success", "neutral", "skipped") -> "success"
            conclusion in setOf("failure", "timed_out", "cancelled", "action_required", "stale") -> "failure"
            conclusion == null -> "pending"
            else -> "failure"
        }
    }
}

/** PR 里的单个提交（GitHub commits 接口的嵌套结构）。 */
data class GitHubPullRequestCommit(
    val sha: String = "",
    val commit: GitHubCommitInfo? = null,
    val author: GitHubIssueUser? = null
) {
    /** 只取提交信息首行，列表里不展开多行正文。 */
    val message: String get() = commit?.message?.lineSequence()?.firstOrNull().orEmpty()
    val authorName: String
        get() = author?.login?.takeIf { it.isNotBlank() } ?: commit?.author?.name.orEmpty()
    val date: String get() = commit?.author?.date.orEmpty()
}

data class GitHubCommitInfo(
    val message: String = "",
    val author: GitHubCommitAuthor? = null
)

data class GitHubCommitAuthor(
    val name: String = "",
    val date: String = ""
)

/** PR 里变更的文件（files 接口，不含 patch 正文）。 */
data class GitHubPullRequestFile(
    val filename: String = "",
    /** added / removed / modified / renamed / copied / changed / unchanged。 */
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    @SerializedName("previous_filename") val previousFilename: String? = null
)

/** 合并 PR 的结果。 */
data class GitHubMergeResult(
    val merged: Boolean = false,
    val message: String = "",
    val sha: String = ""
)

/** 按排序方式对 PR 列表排序（ISO-8601 时间戳可直接字典序比较）。 */
internal fun sortPullRequests(pulls: List<GitHubPullRequest>, sort: String): List<GitHubPullRequest> {
    return when (sort) {
        "oldest" -> pulls.sortedWith(compareBy({ it.createdAt }, { it.number }))
        "updated" -> pulls.sortedWith(compareByDescending<GitHubPullRequest> { it.updatedAt }.thenByDescending { it.number })
        else -> pulls.sortedWith(compareByDescending<GitHubPullRequest> { it.createdAt }.thenByDescending { it.number })
    }
}
