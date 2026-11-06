package com.aiverse.feature.web.routes

import com.aiverse.feature.agent.domain.mention.FileMentionRepository
import com.aiverse.feature.web.FileEntryDto
import com.aiverse.feature.workspace.data.repository.WorkspaceRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** 工作区文件列表路由：复用 App 的 rg --files 清单（与工作区同步）。 */
fun Route.fileRoutes(
    fileMentionRepository: FileMentionRepository,
    workspaceRepository: WorkspaceRepository
) {
    route("/files") {
        get {
            val path = workspaceRepository.currentPath()
            if (path.isBlank()) {
                call.respond(HttpStatusCode.ServiceUnavailable, listOf<FileEntryDto>())
                return@get
            }
            val result = fileMentionRepository.loadFiles(path)
            when (result) {
                is FileMentionRepository.LoadResult.Success -> {
                    val entries = result.files.map { rel ->
                        FileEntryDto(
                            name = rel.substringAfterLast('/'),
                            path = rel,
                            isDir = false,
                            size = 0
                        )
                    }
                    call.respond(entries)
                }
                else -> call.respond(listOf<FileEntryDto>())
            }
        }
    }
}
