package com.aiverse.feature.agent.presentation.component

import com.aiverse.feature.agent.presentation.AgentUIMessage
import com.aiverse.feature.agent.presentation.MessageRole
import com.aiverse.feature.settings.domain.model.ModelContextPolicy

/**
 * 上下文用量的分类明细：把总 token 按「文件/图片、MCP/工具、思考」三类归一化拆分。
 *
 * 模型接口只返回总输入 token（inputTokens），没有分类；这里按当前会话消息的 UI 快照做
 * 相对占比估算（附件张数/大小、工具调用次数、思考文本长度），再把占比映射到真实总量上，
 * 保证 [fileTokens] + [mcpTokens] + [thinkingTokens] + [otherTokens] == [usedTokens]。
 */
data class ContextUsageDetail(
    /** 已用输入 token（真实/估算总量）。 */
    val usedTokens: Int,
    /** 上下文预算（模型窗口）。 */
    val budgetTokens: Int,
    /** 文件 / 图片 / 命令类工具 估算占用。 */
    val fileTokens: Int,
    /** MCP / 其它工具调用 估算占用。 */
    val mcpTokens: Int,
    /** 思考（reasoning）估算占用。 */
    val thinkingTokens: Int,
    /** 其它（系统提示、对话文本等）占用。 */
    val otherTokens: Int,
    /** 总量是否为估算（非模型实际返回）。 */
    val estimated: Boolean
) {
    val remainingTokens: Int get() = (budgetTokens - usedTokens).coerceAtLeast(0)
    val usedRatio: Float get() = if (budgetTokens > 0) usedTokens.toFloat() / budgetTokens else 0f
    val remainingRatio: Float get() = if (budgetTokens > 0) remainingTokens.toFloat() / budgetTokens else 0f
}

/**
 * 按当前会话消息的 UI 快照估算三类 token 的占比。
 */
object ContextUsageEstimator {

    /** 每张图片（视觉 token）的粗估。 */
    private const val IMAGE_TOKENS = 850

    /** 每个非图片附件的粗估上限/下限（按字节/4 折算，钳在 50~5000）。 */
    private const val FILE_TOKEN_MAX = 5000
    private const val FILE_TOKEN_MIN = 50

    /** 每次工具结果的粗估。 */
    private const val TOOL_RESULT_TOKENS = 500

    /**
     * 系统提示 / 工具定义 / 上下文基线占用的估算 token：无真实事件（启动/回放历史）时叠加到消息估算，
     * 避免把估算值低估到严重偏离真实。取上下文窗口保留基线量级。
     */
    private const val SYSTEM_PROMPT_BASE_TOKENS = 2000

    /**
     * 「文件 / 命令」类工具：算作文件占用。其余工具（含 websearch、mcp_*、browser 等）算 MCP。
     */
    private val FILE_TOOLS = setOf(
        "readFile", "list", "search", "sendFile", "writeFile", "editFile",
        "generateImage", "viewImage", "Bash", "terminal", "Shizuku", "ShizukuControl",
        "sshList", "sshRead", "sshExec", "sshRm", "sshMv", "sshDownload", "sshTail", "sshUpload",
        "loadSkill", "askUserQuestion", "manageMcp", "planMode", "goalStatus", "todo", "memory",
        "task", "browser", "review"
    )

    /** 文本 token 估算：ASCII 按 4 字符/token、非 ASCII（中文/东亚）按 1 字符/token，贴合真实。 */
    private fun textTokens(text: String): Int = ModelContextPolicy.estimateTextTokens(text)

    fun estimate(
        messages: List<AgentUIMessage>,
        usedTokens: Int,
        budgetTokens: Int,
        estimated: Boolean
    ): ContextUsageDetail {
        var rawFile = 0
        var rawMcp = 0
        var rawThinking = 0
        for (m in messages) {
            when (m.role) {
                MessageRole.USER -> {
                    for (a in m.attachments) {
                        rawFile += if (a.isImage) {
                            IMAGE_TOKENS
                        } else {
                            // 非图片附件按字节估算（1 token ≈ 4 字节），钳在合理区间。
                            (a.sizeBytes / 4).toInt().coerceIn(FILE_TOKEN_MIN, FILE_TOKEN_MAX)
                        }
                    }
                }
                MessageRole.TOOL -> {
                    if (m.toolName in FILE_TOOLS) rawFile += TOOL_RESULT_TOKENS else rawMcp += TOOL_RESULT_TOKENS
                }
                MessageRole.ASSISTANT -> {
                    rawThinking += textTokens(m.reasoning.orEmpty())
                }
            }
        }

        val sum = rawFile + rawMcp + rawThinking
        // 无真实/事件用量（新会话或回放历史消息）时从消息快照估算总量兜底，避免明细/圆环显示 0。
        val effectiveUsed = if (usedTokens > 0) usedTokens else estimateTotal(messages)
        val (file, mcp, thinking) = if (sum > 0 && effectiveUsed > 0) {
            Triple(
                (effectiveUsed.toLong() * rawFile / sum).toInt(),
                (effectiveUsed.toLong() * rawMcp / sum).toInt(),
                (effectiveUsed.toLong() * rawThinking / sum).toInt()
            )
        } else {
            Triple(0, 0, 0)
        }
        val other = (effectiveUsed - file - mcp - thinking).coerceAtLeast(0)
        return ContextUsageDetail(
            usedTokens = effectiveUsed,
            budgetTokens = budgetTokens,
            fileTokens = file,
            mcpTokens = mcp,
            thinkingTokens = thinking,
            otherTokens = other,
            estimated = estimated
        )
    }

    /**
     * 从消息快照估算总输入 token（文本 + 附件 + 工具 + 思考 + 系统提示基线），用于无真实用量的兜底。
     * 文本用 [textTokens]（ASCII 4 字符/token、中文等按 1 字符/token），修正中文/东亚文本低估。
     */
    fun estimateTotal(messages: List<AgentUIMessage>): Int {
        var total = SYSTEM_PROMPT_BASE_TOKENS
        for (m in messages) {
            when (m.role) {
                MessageRole.USER -> {
                    total += textTokens(m.content)
                    for (a in m.attachments) {
                        total += if (a.isImage) IMAGE_TOKENS
                        else (a.sizeBytes / 4).toInt().coerceIn(FILE_TOKEN_MIN, FILE_TOKEN_MAX)
                    }
                }
                MessageRole.TOOL -> total += TOOL_RESULT_TOKENS
                MessageRole.ASSISTANT -> {
                    total += textTokens(m.content)
                    total += textTokens(m.reasoning.orEmpty())
                }
            }
        }
        return total
    }
}
