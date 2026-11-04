package com.aiverse.feature.web.routes

import com.aiverse.feature.agent.data.local.dao.AgentMessageDao
import com.aiverse.feature.agent.data.local.dao.ChatSessionDao
import com.aiverse.feature.web.ApiResponse
import com.aiverse.feature.web.MessageDto
import com.aiverse.feature.web.SessionDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** 会话与消息的只读 REST 路由。 */
fun Route.sessionRoutes(sessionDao: ChatSessionDao, messageDao: AgentMessageDao) {
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
    }
}
