package com.aiverse.feature.agent.domain.tool.search

import com.aiverse.core.util.FileLogger
import com.aiverse.feature.agent.domain.tool.AgentTool
import com.aiverse.feature.settings.data.repository.InputBarSettingsRepository
import com.aiverse.feature.agent.domain.tool.ParameterType
import com.aiverse.feature.agent.domain.tool.ToolParameter
import com.aiverse.feature.agent.domain.tool.ToolCapability
import com.aiverse.feature.agent.domain.tool.ToolResult
import com.aiverse.feature.search.data.SearchServiceSettingsRepository
import com.aiverse.feature.search.domain.SearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebSearchTool @Inject constructor(
    private val inputBarSettingsRepository: InputBarSettingsRepository,
    private val searchServiceSettingsRepository: SearchServiceSettingsRepository
) : AgentTool() {

    private companion object {
        const val TAG = "WebSearchTool"
        // 回退端点：搜索服务失败时兜底
        const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"

        // 走全局/提供商代理链路（AppProxy 的全局 ProxySelector + 按 host 分派的认证），
        // 与对话等其它 HTTP 请求保持一致；lazy 确保 client 在 applyGlobal 之后首次构建。
        val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .proxyAuthenticator(com.aiverse.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }

    override val name = "websearch"
    override val description = "通过互联网搜索获取实时信息。适用于需要最新资料或时效性信息的任务。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "搜索关键字。请提炼出准确、易于搜索的短语。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：query")

        if (!inputBarSettingsRepository.webSearchEnabled) {
            return ToolResult.Error("联网搜索已关闭，可在输入框快捷栏重新开启")
        }

        return withContext(Dispatchers.IO) {
            // 主路径：用所选搜索提供商（模仿 RikkaHub 的搜索服务）。
            val providerResult = searchViaProvider(query)
            if (providerResult != null) {
                providerResult
            } else {
                // 回退：Parallel MCP（原逻辑）。
                searchViaParallel(query)
            }
        }
    }

    /** 用所选搜索提供商搜索；无可用提供商或失败返回 null（走回退）。 */
    private suspend fun searchViaProvider(query: String): ToolResult? {
        return try {
            val state = searchServiceSettingsRepository.state.value
            val services = state.services
            if (services.isEmpty()) return null
            val options = services.getOrNull(state.selected) ?: services.first()
            val service = SearchService.getService(options)
            val params = buildJsonObject { put("query", query) }
            val result = service.search(params, state.common, options).getOrThrow()
            ToolResult.Success(encodeSearchResult(result))
        } catch (e: Exception) {
            FileLogger.e(TAG, "搜索提供商失败，回退 Parallel MCP: ${e.message}")
            null
        }
    }

    private fun encodeSearchResult(result: com.aiverse.feature.search.domain.SearchResult): JsonElement {
        return buildJsonObject {
            result.answer?.let { put("answer", JsonPrimitive(it)) }
            put(
                "items",
                JsonArray(result.items.mapIndexed { index, item ->
                    buildJsonObject {
                        put("id", JsonPrimitive(java.util.UUID.randomUUID().toString().take(6)))
                        put("index", JsonPrimitive(index + 1))
                        put("title", JsonPrimitive(item.title))
                        put("url", JsonPrimitive(item.url))
                        put("text", JsonPrimitive(item.text))
                        item.publishedDate?.let { put("publishedDate", JsonPrimitive(it)) }
                    }
                })
            )
            if (result.images.isNotEmpty()) {
                put("images", JsonArray(result.images.map { JsonPrimitive(it) }))
            }
            result.retrievedAt?.let { put("retrievedAt", JsonPrimitive(it)) }
        }
    }

    private suspend fun searchViaParallel(query: String): ToolResult {
        return try {
            val requestBody = JsonObject(
                mapOf(
                    "jsonrpc" to JsonPrimitive("2.0"),
                    "id" to JsonPrimitive(1),
                    "method" to JsonPrimitive("tools/call"),
                    "params" to JsonObject(
                        mapOf(
                            "name" to JsonPrimitive("web_search"),
                            "arguments" to JsonObject(
                                mapOf(
                                    "objective" to JsonPrimitive(query),
                                    "search_queries" to JsonArray(listOf(JsonPrimitive(query))),
                                    "session_id" to JsonPrimitive("AiVerse-android")
                                )
                            )
                        )
                    )
                )
            ).toString()

            FileLogger.i(TAG, "发起 WebSearch 请求: $query")

            val request = Request.Builder()
                .url(PARALLEL_MCP_URL)
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", "AiVerse/1.0")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val responseCode = resp.code
                if (responseCode !in 200..299) {
                    val errorStr = resp.body?.string()
                    FileLogger.e(TAG, "WebSearch 失败: HTTP $responseCode, $errorStr")
                    return@use ToolResult.Error("网络搜索失败（HTTP $responseCode）")
                }

                val responseBody = resp.body?.string().orEmpty()
                val rawResultText = parseMcpResponse(responseBody)

                if (rawResultText.isNullOrBlank()) {
                    ToolResult.Success(JsonPrimitive("未找到关于「$query」的搜索结果，请更换关键词重试。"))
                } else {
                    ToolResult.Success(parseSearchResultPayload(rawResultText) ?: JsonPrimitive(rawResultText))
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "WebSearch 发生异常", e)
            ToolResult.Error("搜索失败：${e.message}")
        }
    }

    /**
     * 兼容解析直接的 JSON 或 SSE (Server-Sent Events) 格式。
     */
    private fun parseMcpResponse(body: String): String? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }

        for (line in lines) {
            val jsonStr = if (line.startsWith("data: ")) {
                line.substring(6)
            } else if (line.startsWith("{")) {
                line
            } else {
                continue
            }

            try {
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                if (json.containsKey("result")) {
                    val contentArr = json["result"]?.jsonObject?.get("content")?.jsonArray
                    if (!contentArr.isNullOrEmpty()) {
                        return contentArr[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors for partial lines
            }
        }
        return null
    }

    private fun parseSearchResultPayload(raw: String): JsonElement? {
        return runCatching {
            val element = Json.parseToJsonElement(raw.trim())
            val obj = element as? JsonObject ?: return@runCatching null
            if (obj["results"] is JsonArray) element else null
        }.getOrNull()
    }
}
