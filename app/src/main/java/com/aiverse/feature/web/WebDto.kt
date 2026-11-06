package com.aiverse.feature.web

import com.aiverse.feature.agent.data.local.entity.AgentMessageEntity
import com.aiverse.feature.agent.data.local.entity.ChatSessionEntity
import com.aiverse.feature.agent.data.repository.FavoriteItem
import kotlinx.serialization.Serializable

/** 网页端 API 返回的会话 DTO（与 ChatSessionEntity 字段对应）。 */
@Serializable
data class SessionDto(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = "BUILD",
    val modeBeforePlan: String? = null,
    val goal: String? = null,
    val goalStatus: String? = null,
    val reasoningEffort: String = "MEDIUM",
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val isPinned: Boolean = false,
    val parentId: String? = null,
    val subagentType: String? = null
) {
    companion object {
        fun from(e: ChatSessionEntity) = SessionDto(
            id = e.id,
            title = e.title,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            workspacePath = e.workspacePath,
            mode = e.mode,
            modeBeforePlan = e.modeBeforePlan,
            goal = e.goal,
            goalStatus = e.goalStatus,
            reasoningEffort = e.reasoningEffort,
            providerId = e.providerId,
            model = e.model,
            totalInputTokens = e.totalInputTokens,
            totalOutputTokens = e.totalOutputTokens,
            isPinned = e.isPinned,
            parentId = e.parentId,
            subagentType = e.subagentType
        )
    }
}

/** 网页端 API 返回的消息 DTO（与 AgentMessageEntity 字段对应）。 */
@Serializable
data class MessageDto(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    val reasoning: String? = null,
    val attachmentsJson: String? = null,
    val isCompacted: Boolean = false,
    val isContextSummary: Boolean = false,
    val isCompactionMarker: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val isContextExcluded: Boolean = false
) {
    companion object {
        fun from(e: AgentMessageEntity) = MessageDto(
            id = e.id,
            sessionId = e.sessionId,
            role = e.role,
            content = e.content,
            timestamp = e.timestamp,
            toolCallsJson = e.toolCallsJson,
            toolCallId = e.toolCallId,
            toolName = e.toolName,
            toolArgs = e.toolArgs,
            isError = e.isError,
            reasoning = e.reasoning,
            attachmentsJson = e.attachmentsJson,
            isCompacted = e.isCompacted,
            isContextSummary = e.isContextSummary,
            isCompactionMarker = e.isCompactionMarker,
            inputTokens = e.inputTokens,
            outputTokens = e.outputTokens,
            cachedInputTokens = e.cachedInputTokens,
            isContextExcluded = e.isContextExcluded
        )
    }
}

/** 网页端 API 返回的收藏 DTO（与 FavoriteItem 字段对应）。 */
@Serializable
data class FavoriteDto(
    val id: String,
    val content: String,
    val sessionTitle: String = "",
    val createdAt: Long
) {
    companion object {
        fun from(e: FavoriteItem) = FavoriteDto(
            id = e.id,
            content = e.content,
            sessionTitle = e.sessionTitle,
            createdAt = e.createdAt
        )
    }
}

/** 发送消息请求体。 */
@Serializable
data class SendMessageRequest(
    val content: String
)

/** 更新收藏请求体。 */
@Serializable
data class UpdateFavoriteRequest(
    val content: String
)

/** 重命名会话请求体。 */
@Serializable
data class RenameSessionRequest(
    val title: String
)

/** 切换会话模型请求体。 */
@Serializable
data class SetSessionModelRequest(
    val providerId: String? = null,
    val model: String? = null
)

/** 添加模型请求体（给某 provider 追加一个模型名）。 */
@Serializable
data class AddModelRequest(
    val providerId: String,
    val model: String
)

/** 网页端 API 返回的模型/提供商 DTO。 */
@Serializable
data class ModelInfoDto(
    val id: String,
    val name: String,
    val type: String,
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val hasApiKey: Boolean = false
)

/** 网页端 API 返回的工作区文件条目。 */
@Serializable
data class FileEntryDto(
    val name: String,
    val path: String,
    val isDir: Boolean = false,
    val size: Long = 0
)

/** 通用响应。 */
@Serializable
data class ApiResponse(
    val ok: Boolean = true,
    val error: String? = null
)
