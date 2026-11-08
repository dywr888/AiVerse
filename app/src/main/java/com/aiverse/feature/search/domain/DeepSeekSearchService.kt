package com.aiverse.feature.search.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiverse.feature.search.domain.SearchResult.SearchResultItem
import com.aiverse.feature.search.domain.SearchService.Companion.httpClient
import com.aiverse.feature.search.domain.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** DeepSeek 搜索。 */
object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name: String = "DeepSeek"

    private const val SEARCH_URL = "https://api.deepseek.com/v1/tools/search"

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val apiKey = serviceOptions.apiKey

            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize.coerceIn(1, 50))
            }

            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).await()
            val bodyRaw = response.body!!.string()
            if (!response.isSuccessful) {
                error("DeepSeek search failed: ${response.errorMessage(bodyRaw)}")
            }

            val searchResponse = json.decodeFromString<DeepSeekSearchResponse>(bodyRaw)
            SearchResult(
                items = searchResponse.results.map { result ->
                    SearchResultItem(
                        title = result.title,
                        url = result.url,
                        text = result.summary.ifBlank { result.snippet },
                        publishedDate = result.date,
                    )
                }
            )
        }
    }

    private fun Response.errorMessage(bodyRaw: String): String {
        val message = runCatching {
            json.decodeFromString<DeepSeekErrorResponse>(bodyRaw).error.message
        }.getOrNull() ?: bodyRaw
        return "#$code $message"
    }

    @Serializable
    data class DeepSeekSearchResponse(
        val results: List<DeepSeekSearchResultItem> = emptyList(),
    )

    @Serializable
    data class DeepSeekSearchResultItem(
        val title: String,
        val url: String,
        val snippet: String = "",
        val summary: String = "",
        val content: String = "",
        @SerialName("site_name")
        val siteName: String? = null,
        val date: String? = null,
        @SerialName("rerank_score")
        val rerankScore: Double? = null,
        val authority: Int? = null,
    )

    @Serializable
    data class DeepSeekErrorResponse(
        val error: DeepSeekError,
    )

    @Serializable
    data class DeepSeekError(
        val message: String,
    )
}
