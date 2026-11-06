package com.aiverse.feature.web.routes

import com.aiverse.feature.agent.domain.session.SessionUseCase
import com.aiverse.feature.web.ApiResponse
import com.aiverse.feature.web.RenameSessionRequest
import com.aiverse.feature.web.SetSessionModelRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** 会话管理路由：删除 / 重命名 / 切换模型（与 App 同一数据库，App 端实时同步）。 */
fun Route.sessionManageRoutes(sessionUseCase: SessionUseCase) {
    route("/sessions") {
        // 删除会话（含子会话 + 消息级联）。
        delete("/{id}") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@delete
            }
            val deleted = sessionUseCase.deleteSession(id)
            call.respond(ApiResponse(ok = true))
        }

        // 重命名会话标题。
        put("/{id}/title") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@put
            }
            val req = runCatching { call.receive<RenameSessionRequest>() }.getOrNull()
            val title = req?.title?.trim().orEmpty()
            if (title.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "empty title"))
                return@put
            }
            if (sessionUseCase.getSessionById(id) == null) {
                call.respond(HttpStatusCode.NotFound, ApiResponse(ok = false, error = "session not found"))
                return@put
            }
            sessionUseCase.updateTitle(id, title)
            call.respond(ApiResponse(ok = true))
        }

        // 切换会话模型（providerId + model，两者都可为 null 表示跟随默认）。
        put("/{id}/model") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@put
            }
            val req = runCatching { call.receive<SetSessionModelRequest>() }.getOrNull()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "invalid body"))
                    return@put
                }
            if (sessionUseCase.getSessionById(id) == null) {
                call.respond(HttpStatusCode.NotFound, ApiResponse(ok = false, error = "session not found"))
                return@put
            }
            sessionUseCase.updateProviderModel(id, req.providerId, req.model)
            call.respond(ApiResponse(ok = true))
        }
    }
}
