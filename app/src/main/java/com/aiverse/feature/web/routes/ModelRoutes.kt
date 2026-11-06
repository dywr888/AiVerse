package com.aiverse.feature.web.routes

import com.aiverse.feature.settings.domain.model.AIProviderConfig
import com.aiverse.feature.settings.domain.repository.AIProviderRepository
import com.aiverse.feature.web.AddModelRequest
import com.aiverse.feature.web.ApiResponse
import com.aiverse.feature.web.ModelInfoDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first

/** 模型路由：列出全部 provider 的可用模型 / 添加模型（与 App 的 Provider 配置同步）。 */
fun Route.modelRoutes(providerRepository: AIProviderRepository) {
    route("/models") {
        // 全部 provider + 可用模型。
        get {
            val providers = providerRepository.getAllProviders().first()
            call.respond(providers.map { it.toDto() })
        }

        // 给某 provider 追加一个模型名（添加模型）。
        post {
            val req = runCatching { call.receive<AddModelRequest>() }.getOrNull()
            if (req == null || req.providerId.isBlank() || req.model.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse(ok = false, error = "invalid body"))
                return@post
            }
            val provider = providerRepository.getProviderById(req.providerId)
            if (provider == null) {
                call.respond(HttpStatusCode.NotFound, ApiResponse(ok = false, error = "provider not found"))
                return@post
            }
            val model = req.model.trim()
            val updated = provider.copy(
                models = (provider.models + model).distinct()
            )
            providerRepository.saveProvider(updated)
            call.respond(ApiResponse(ok = true))
        }
    }
}

private fun AIProviderConfig.toDto() = ModelInfoDto(
    id = id,
    name = name,
    type = type.name,
    models = models,
    selectedModel = effectiveModel,
    isEnabled = isEnabled,
    hasApiKey = hasUsableApiKey
)
