package com.aiverse.feature.agent.domain.tool.review

import com.aiverse.core.util.FileLogger
import com.aiverse.feature.agent.domain.container.CommandEngine
import com.aiverse.feature.agent.domain.tool.AgentTool
import com.aiverse.feature.agent.domain.tool.ParameterType
import com.aiverse.feature.agent.domain.tool.ToolCapability
import com.aiverse.feature.agent.domain.tool.ToolParameter
import com.aiverse.feature.agent.domain.tool.ToolPermissionPolicy
import com.aiverse.feature.agent.domain.tool.ToolResult
import com.aiverse.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 专家模式专属的 PR 审查工具 `reviewPr`：拉取远端 GitHub Pull Request 的变更供 AI 审查。
 *
 * 与 [ReviewTool] 不同，它面向**远端 PR**（不是本地未提交改动），固定走
 * `git fetch origin pull/<pr>/head`（写入 FETCH_HEAD，不改分支与工作区文件）+ `git diff <base>...FETCH_HEAD`，
 * 复用工作区 git 凭证 helper（私有仓库可访问），并取 PR 头部提交的元信息。
 *
 * 只读审查语义：fetch 仅更新 `.git/FETCH_HEAD` 与对象库，不触碰工作区文件与任何分支引用，
 * 与 [ReviewTool] 一致采用 [ToolPermissionPolicy.AUTO_APPROVE]。
 *
 * 改动文件数超过 [FILE_LIST_LIMIT] 时降级为只给清单与统计，避免超大 PR 撑爆上下文；
 * 需要逐文件细看时由模型用 `review(path=...)`（本地基线）或按需补拉。
 */
class ReviewPrTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool() {
    private companion object {
        const val TAG = "ReviewPrTool"
        const val DEFAULT_BASE = "main"
        const val FILE_LIST_LIMIT = 50
        /** 提交元信息格式：hash | 作者 | 日期 | 标题，正文另起。 */
        const val LOG_FORMAT = "%H|%an|%ad|%s%n%b"
    }

    override val name = "reviewPr"
    override val description = "拉取远端 GitHub Pull Request 的变更（git fetch + diff）供代码审查，专家模式专属。只读，不修改工作区文件与分支。默认对比 main，可指定 base。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE, ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "pr" to ToolParameter(
            name = "pr",
            type = ParameterType.STRING,
            description = "Pull Request 编号（必需），如 reviewPr(pr=\"123\")。",
            required = true
        ),
        "base" to ToolParameter(
            name = "base",
            type = ParameterType.STRING,
            description = "对比基线 ref（commit 哈希/分支/tag），默认 $DEFAULT_BASE。审查 PR 相对该基线引入的全部差异。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val workdir = workspaceRepository.currentPath()
            val pr = args["pr"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            if (pr.isBlank() || !pr.all { it.isDigit() }) {
                return ToolResult.Error("参数无效：pr 必须是数字编号（如 reviewPr(pr=\"123\")）")
            }
            val base = args["base"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE

            val isRepo = commandEngine.runCommandSyncUnbounded(
                "git rev-parse --is-inside-work-tree", workdir
            ).output.trim() == "true"
            if (!isRepo) {
                return ToolResult.Error("当前工作区不是 git 仓库，无法审查远端 PR")
            }

            // 拉取 PR 头部到 FETCH_HEAD（不改分支/工作区），复用工作区凭证 helper。
            val fetch = commandEngine.runCommandSyncUnbounded(
                "git fetch origin pull/$pr/head", workdir
            )
            if (fetch.output.startsWith("fatal") || fetch.output.startsWith("error")) {
                return ToolResult.Error(
                    "拉取 PR #$pr 失败：${fetch.output.trim().lineSequence().firstOrNull() ?: fetch.output.trim()}"
                )
            }

            // PR 头部提交元信息 + 相对基线的改动文件清单与 diff。
            val meta = commandEngine.runCommandSyncUnbounded(
                "git log -1 --format=\"$LOG_FORMAT\" FETCH_HEAD", workdir
            ).output.trim()
            val nameStatus = commandEngine.runCommandSyncUnbounded(
                "git diff --name-status --no-color $base...FETCH_HEAD", workdir
            ).output.trim()
            val diffResult = commandEngine.runCommandSyncUnbounded(
                "git diff --no-color --no-ext-diff $base...FETCH_HEAD", workdir
            )

            val sb = StringBuilder()
            sb.append("=== PR #$pr 提交元信息 ===\n")
            sb.append(meta.ifBlank { "(无法读取 PR 头部提交信息)" }).append("\n\n")
            sb.append("=== 相对 $base 的改动文件（--name-status） ===\n")
            sb.append(nameStatus.ifBlank { "(无改动文件)" }).append("\n\n")

            val fileCount = nameStatus.lineSequence().count { it.isNotBlank() }
            if (fileCount > FILE_LIST_LIMIT) {
                sb.append("改动文件 $fileCount 个，超过 $FILE_LIST_LIMIT，不再输出完整 diff（避免上下文过大）。\n")
                sb.append("需要细看时用 review(path=...) 按文件补审，或分批拉取。")
            } else if (diffResult.output.isBlank()) {
                sb.append("=== diff ===\n(相对 $base 无差异)")
            } else {
                sb.append("=== diff（$base...FETCH_HEAD） ===\n")
                sb.append(diffResult.output.trimEnd())
                if (diffResult.outputTruncated) {
                    sb.append("\n\n[注意] diff 输出过大，仅保留部分内容，可能不完整。")
                }
            }

            FileLogger.v(TAG, "reviewPr #$pr 完成，文件数=$fileCount，输出 ${sb.length} 字符")
            ToolResult.Success(JsonPrimitive(sb.toString().trim()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "reviewPr 失败", e)
            ToolResult.Error("PR 审查失败：${e.message}")
        }
    }
}
