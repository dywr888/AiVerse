package com.aiverse.feature.web.routes

import com.aiverse.feature.agent.data.local.dao.AgentMessageDao
import com.aiverse.feature.agent.data.local.dao.ChatSessionDao
import com.aiverse.feature.web.ApiResponse
import com.aiverse.feature.web.MessageDto
import com.aiverse.feature.web.SendMessageRequest
import com.aiverse.feature.web.SessionDto
import com.aiverse.feature.web.WebMessageBridge
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** 会话与消息的 REST 路由（只读 + Web 发消息转发）。 */
fun Route.sessionRoutes(sessionDao: ChatSessionDao, messageDao: AgentMessageDao, messageBridge: WebMessageBridge) {
    route("/api") {
        get("/sessions") {
            val workspace = call.request.queryParameters["workspace"]
            val sessions = if (workspace.isNullOrBlank()) {
                sessionDao.getAllOnce()
            } else {
                sessionDao.getAllSessionsByWorkspaceOnce(workspace)
            }
            call.respond(sessions.map(SessionDto::from))
        }
        get("/sessions/{id}") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@get
            }
            val session = sessionDao.getById(id)
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, ApiResponse(ok = false, error = "session not found"))
                return@get
            }
            call.respond(SessionDto.from(session))
        }
        get("/sessions/{id}/messages") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@get
            }
            val messages = messageDao.getMessagesBySessionOnce(id)
            call.respond(messages.map(MessageDto::from))
        }
        // Web 发消息：转发给注册的 AIAgentViewModel（App 前台时生效）。
        post("/sessions/{id}/messages") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@post
            }
            val session = sessionDao.getById(id)
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, ApiResponse(ok = false, error = "session not found"))
                return@post
            }
            val req = runCatching { call.receive<SendMessageRequest>() }.getOrNull()
            if (req == null || req.content.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "empty content"))
                return@post
            }
            val accepted = messageBridge.submit(id, req.content.trim())
            if (!accepted) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse(ok = false, error = "App 未在前台，无法发送。请打开 AiVerse 后重试。"))
                return@post
            }
            call.respond(ApiResponse(ok = true))
        }
    }
}
