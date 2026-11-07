package com.aiverse.feature.agent.domain.tool.subagent

import com.aiverse.core.util.FileLogger
import com.aiverse.feature.agent.domain.model.AgentContext
import com.aiverse.feature.agent.domain.session.SessionUseCase
import com.aiverse.feature.agent.domain.subagent.SubAgentEvent
import com.aiverse.feature.agent.domain.subagent.SubAgentEventBus
import com.aiverse.feature.agent.domain.subagent.SubAgentEventType
import com.aiverse.feature.agent.domain.tool.AbstractContextualTool
import com.aiverse.feature.agent.domain.tool.ParameterType
import com.aiverse.feature.agent.domain.tool.ToolParameter
import com.aiverse.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理 → 子代理的对等互通工具 `messagePeer`（专家模式多 Agent 协作）。
 *
 * - 发送方为当前子代理会话，[peerId] 为同一父会话下的兄弟子代理会话 id。
 * - 每条消息带元信息：发送者 id、目标 id、全局序号（[SubAgentEventBus.nextSeq]）。
 * - 调度器（ViewModel）**只按 id 投递**，不解析消息文字里的名字，避免名字歧义导致误投。
 * - 异步不阻塞：调用即返回，消息由 ViewModel 按目标子代理状态分发（忙碌时搭车，空闲时触发新一轮）。
 * - 仅子代理可用——主会话工具集剔除它（见 AIAgentViewModel）。
 */
class MessagePeerTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val eventBus: SubAgentEventBus
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "MessagePeerTool"
        const val MESSAGE_MAX = 4000
    }

    override val name = "messagePeer"

    override val description = "向另一个专家子代理直接发送消息（对等互通，专家模式）；按 id 投递，不经过主对话转发。异步不阻塞，回复作为后续消息送达。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "peerId" to ToolParameter(
            name = "peerId",
            type = ParameterType.STRING,
            description = "目标子代理的会话 id（兄弟子代理，取自 task 创建时返回的 id）",
            required = true
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "发给目标专家的消息正文（讨论、求助、交接、审查请求等）",
            required = true
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val current = sessionUseCase.getSessionById(sessionId)
            ?: return ToolResult.Error("当前会话不存在", "SESSION_NOT_FOUND")
        val parentSessionId = current.parentId
            ?: return ToolResult.Error("当前会话不是子代理，无法向其他专家发送消息", "NO_PARENT")
        val peerId = (args["peerId"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (peerId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：peerId 不能为空", "INVALID_ARGS")
        }
        if (peerId == sessionId) {
            return ToolResult.Error("不能给自己发消息", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        }
        if (message.length > MESSAGE_MAX) {
            return ToolResult.Error("消息过长（上限 $MESSAGE_MAX 字）", "INVALID_ARGS")
        }
        // 校验目标是同一父会话下的兄弟子代理（防止跨会话误投）
        val peer = sessionUseCase.getSessionById(peerId)
        if (peer == null) {
            return ToolResult.Error("目标子代理不存在：$peerId", "PEER_NOT_FOUND")
        }
        if (peer.parentId != parentSessionId) {
            return ToolResult.Error("目标不是同一主对话下的子代理，无法互通", "PEER_NOT_SIBLING")
        }

        val seq = eventBus.nextSeq()
        eventBus.emit(
            SubAgentEvent(
                subSessionId = sessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_PEER,
                detail = message,
                targetSubSessionId = peerId,
                seq = seq
            )
        )
        FileLogger.i(TAG, "子代理对等消息: from=$sessionId to=$peerId parent=$parentSessionId seq=$seq")
        return ToolResult.Success(
            buildJsonObject {
                put("state", "sent")
                put("seq", seq)
                put("message", "消息已发送给目标专家（id=$peerId，序号=$seq），可继续手头工作，对方的回复会稍后送达。")
            }
        )
    }
}
