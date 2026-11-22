package com.aiverse.feature.git.domain

import com.aiverse.feature.git.domain.model.GitHubCheckRun
import com.aiverse.feature.git.domain.model.GitHubCheckSummary
import com.aiverse.feature.git.domain.model.GitHubPullRequest
import com.aiverse.feature.git.domain.model.sortPullRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR 状态归并、CI 汇总与列表排序的纯逻辑用例。 */
class GitHubPullRequestTest {

    // ── PR 展示状态 ──

    @Test
    fun displayState_mergedWinsOverClosed() {
        val pr = GitHubPullRequest(number = 1, title = "t", state = "closed", merged = true)
        assertEquals("merged", pr.displayState)
    }

    @Test
    fun displayState_mergedAtAloneTreatedAsMerged() {
        // 列表接口返回的摘要对象 merged 恒为 false，只能靠 merged_at 识别已合并。
        val pr = GitHubPullRequest(number = 1, title = "t", state = "closed", mergedAt = "2024-01-01T00:00:00Z")
        assertEquals("merged", pr.displayState)
        assertTrue(pr.isMerged)
    }

    @Test
    fun displayState_closedWithoutMergeAtIsClosed() {
        val pr = GitHubPullRequest(number = 1, title = "t", state = "closed", merged = false, mergedAt = null)
        assertEquals("closed", pr.displayState)
        assertFalse(pr.isMerged)
    }

    @Test
    fun displayState_closedWithoutMerge() {
        val pr = GitHubPullRequest(number = 1, title = "t", state = "closed", merged = false)
        assertEquals("closed", pr.displayState)
    }

    @Test
    fun displayState_open() {
        val pr = GitHubPullRequest(number = 1, title = "t", state = "open")
        assertEquals("open", pr.displayState)
    }

    // ── CI 单条归并 ──

    @Test
    fun checkOutcome_completedSuccessAndNeutral() {
        assertEquals("success", GitHubCheckSummary.checkOutcome("completed", "success"))
        assertEquals("success", GitHubCheckSummary.checkOutcome("completed", "neutral"))
        assertEquals("success", GitHubCheckSummary.checkOutcome("completed", "skipped"))
    }

    @Test
    fun checkOutcome_completedFailureVariants() {
        listOf("failure", "timed_out", "cancelled", "action_required", "stale").forEach { conclusion ->
            assertEquals("conclusion=$conclusion", "failure", GitHubCheckSummary.checkOutcome("completed", conclusion))
        }
    }

    @Test
    fun checkOutcome_runningIsPending() {
        assertEquals("pending", GitHubCheckSummary.checkOutcome("queued", null))
        assertEquals("pending", GitHubCheckSummary.checkOutcome("in_progress", null))
        assertEquals("pending", GitHubCheckSummary.checkOutcome("completed", null))
    }

    // ── CI 汇总 ──

    @Test
    fun summary_failureWinsOverPendingAndSuccess() {
        val summary = GitHubCheckSummary(
            total = 3,
            success = 1,
            failure = 1,
            pending = 1,
            runs = listOf(
                run("build", "completed", "success"),
                run("test", "completed", "failure"),
                run("lint", "in_progress", null)
            )
        )
        assertEquals("failure", summary.state)
    }

    @Test
    fun summary_pendingWhenNoFailure() {
        val summary = GitHubCheckSummary(
            total = 2,
            success = 1,
            pending = 1,
            runs = listOf(run("build", "completed", "success"), run("test", "queued", null))
        )
        assertEquals("pending", summary.state)
    }

    @Test
    fun summary_successWhenAllRunsPassed() {
        val summary = GitHubCheckSummary(
            total = 2,
            success = 2,
            runs = listOf(run("build", "completed", "success"), run("test", "completed", "success"))
        )
        assertEquals("success", summary.state)
    }

    @Test
    fun summary_fallsBackToLegacyCombinedState() {
        assertEquals("success", GitHubCheckSummary(combinedState = "success").state)
        assertEquals("failure", GitHubCheckSummary(combinedState = "failure").state)
        assertEquals("pending", GitHubCheckSummary(combinedState = "pending").state)
    }

    @Test
    fun summary_emptyIsNone() {
        assertEquals("none", GitHubCheckSummary.EMPTY.state)
    }

    // ── 列表排序 ──

    @Test
    fun sortPullRequests_newestAndOldest() {
        val a = GitHubPullRequest(number = 1, title = "a", createdAt = "2024-01-01T00:00:00Z")
        val b = GitHubPullRequest(number = 2, title = "b", createdAt = "2024-02-01T00:00:00Z")
        val list = listOf(a, b)
        assertEquals(listOf(2L, 1L), sortPullRequests(list, "newest").map { it.number })
        assertEquals(listOf(1L, 2L), sortPullRequests(list, "oldest").map { it.number })
    }

    @Test
    fun sortPullRequests_updatedAtDescending() {
        val a = GitHubPullRequest(number = 1, title = "a", updatedAt = "2024-01-01T00:00:00Z")
        val b = GitHubPullRequest(number = 2, title = "b", updatedAt = "2024-03-01T00:00:00Z")
        assertEquals(listOf(2L, 1L), sortPullRequests(listOf(a, b), "updated").map { it.number })
    }

    private fun run(name: String, status: String, conclusion: String?) =
        GitHubCheckRun(name = name, status = status, conclusion = conclusion)
}
