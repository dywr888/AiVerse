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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 供 AI 进行代码审查的只读工具：输出工作区相对某基线的 git diff。
 *
 * 与 Bash 工具不同，本工具固定执行 `git diff`（可配 base/to/path/staged），
 * 用 [CommandEngine.runCommandSyncUnbounded] 获取完整 diff——受限幅的默认通道会在输出
 * 超过 2 万字符时插入截断占位符，混入 diff 数据流产生伪 diff 行，故走不限幅通道。
 *
 * git diff 不包含未跟踪的新文件（只跟踪已纳入版本库的路径），因此额外列出未跟踪文件清单，
 * 避免 AI 审查时漏看新增文件。
 */
class ReviewTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool() {
    private companion object {
        const val TAG = "ReviewTool"
        const val DEFAULT_BASE = "HEAD"
    }

    override val name = "review"
    override val description = "获取工作区代码变更（git diff）供代码审查。只读，不修改任何文件。默认审查未提交改动（base=HEAD）；可指定基线/终点 ref、路径与暂存区。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE, ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "base" to ToolParameter(
            name = "base",
            type = ParameterType.STRING,
            description = "diff 起点 ref（commit 哈希/分支/tag），默认 $DEFAULT_BASE。审查 base 到工作区当前状态（含未提交改动）的全部差异；审查最近一次提交用 $DEFAULT_BASE~1。",
            required = false
        ),
        "to" to ToolParameter(
            name = "to",
            type = ParameterType.STRING,
            description = "可选：diff 终点 ref。给定时审查 base..to 两个 ref 之间的差异（不含工作区未提交改动）；不给时审查 base 到当前工作区。",
            required = false
        ),
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "可选：限定只审查该路径/文件（相对工作区根，如 app/src/...）。不给时覆盖全仓库。",
            required = false
        ),
        "staged" to ToolParameter(
            name = "staged",
            type = ParameterType.BOOLEAN,
            description = "可选：true 时只看已暂存改动（git diff --cached）。默认 false（含暂存+未暂存）。仅在未给 to 时生效。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val workdir = workspaceRepository.currentPath()
            val base = args["base"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
            val to = args["to"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val path = args["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val staged = args["staged"]?.jsonPrimitive?.booleanOrNull ?: false

            val isRepo = commandEngine.runCommandSyncUnbounded(
                "git rev-parse --is-inside-work-tree", workdir
            ).output.trim() == "true"
            if (!isRepo) {
                return ToolResult.Error("当前工作区不是 git 仓库，无法审查代码变更")
            }

            val hasHead = hasHead(workdir)
            var diffText: String? = null
            var truncated = false
            if (hasHead) {
                val diffCommand = buildDiffCommand(base, to, path, staged)
                FileLogger.d(TAG, "review diff: $diffCommand")
                val diffResult = commandEngine.runCommandSyncUnbounded(diffCommand, workdir)
                if (diffResult.output.startsWith("fatal")) {
                    return ToolResult.Error(
                        "git diff 失败：${diffResult.output.trim().lineSequence().firstOrNull() ?: diffResult.output.trim()}"
                    )
                }
                diffText = diffResult.output
                truncated = diffResult.outputTruncated
            }

            val untracked = commandEngine.runCommandSyncUnbounded(
                buildUntrackedCommand(path), workdir
            ).output.trim()

            val sb = StringBuilder()
            when {
                diffText == null -> sb.append("仓库尚无提交（无 HEAD），无版本库差异可审")
                diffText.isBlank() -> sb.append("工作区相对 $base 无差异")
                else -> sb.append(diffText.trimEnd())
            }
            if (truncated) {
                sb.append("\n\n[注意] diff 输出过大，仅保留部分内容，可能不完整。")
            }
            if (untracked.isNotBlank()) {
                sb.append("\n\n未跟踪的新文件（git diff 不含，如需内容请用 readFile 查看）：\n")
                sb.append(untracked)
            }

            val result = sb.toString().trim()
            FileLogger.v(TAG, "review 完成，输出 ${result.length} 字符")
            ToolResult.Success(JsonPrimitive(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "review 失败", e)
            ToolResult.Error("代码审查失败：${e.message}")
        }
    }

    private suspend fun hasHead(workdir: String): Boolean =
        runCatching {
            commandEngine.runCommandSyncUnbounded("git rev-parse --verify HEAD", workdir).output
                .let { it.isNotBlank() && !it.startsWith("fatal") }
        }.getOrDefault(false)

    private fun buildDiffCommand(base: String, to: String?, path: String?, staged: Boolean): String = buildString {
        append("git diff --no-color --no-ext-diff")
        if (to == null && staged) append(" --cached")
        append(' ').append(base)
        if (to != null) append(' ').append(to)
        if (path != null) append(" -- ").append(path)
    }

    private fun buildUntrackedCommand(path: String?): String = buildString {
        append("git ls-files --others --exclude-standard")
        if (path != null) append(" -- ").append(path)
    }
}
