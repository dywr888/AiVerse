package com.aiverse.feature.web.routes

import com.aiverse.feature.agent.data.repository.FavoriteRepository
import com.aiverse.feature.web.ApiResponse
import com.aiverse.feature.web.FavoriteDto
import com.aiverse.feature.web.UpdateFavoriteRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first

/** 收藏的 REST 路由（读列表 + 更新 + 删除）。 */
fun Route.favoriteRoutes(favoriteRepository: FavoriteRepository) {
    route("/api") {
        get("/favorites") {
            val items = favoriteRepository.favoritesFlow.first()
            call.respond(items.map(FavoriteDto::from))
        }
        put("/favorites/{id}") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@put
            }
            val req = call.receive<UpdateFavoriteRequest>()
            favoriteRepository.update(id, req.content)
            call.respond(ApiResponse())
        }
        delete("/favorites/{id}") {
            val id = call.parameters["id"]
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "missing id"))
                return@delete
            }
            favoriteRepository.delete(id)
            call.respond(ApiResponse())
        }
    }
}
