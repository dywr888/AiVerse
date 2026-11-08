package com.aiverse.feature.search.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiverse.feature.search.domain.SearchResult.SearchResultItem
import com.aiverse.feature.search.domain.SearchService.Companion.httpClient
import com.aiverse.feature.search.domain.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Serper（Google 搜索代理）搜索。 */
object SerperSearchService : SearchService<SearchServiceOptions.SerperOptions> {
    override val name: String = "Serper"

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("q", query)
                put("num", commonOptions.resultSize)
            }
            val apiKey = serviceOptions.apiKey

            val request = Request.Builder()
                .url("https://google.serper.dev/search")
                .post(body.toString().toRequestBody())
                .addHeader("X-API-KEY", apiKey)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body!!.string()
                val searchResponse = json.decodeFromString<SerperSearchResponse>(responseBody)

                val answer = searchResponse.answerBox?.let { it.answer ?: it.snippet }
                    ?: searchResponse.knowledgeGraph?.description

                val items = searchResponse.organic.map { result ->
                    SearchResultItem(
                        title = result.title,
                        url = result.link,
                        text = result.snippet ?: ""
                    )
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = answer,
                        items = items
                    )
                )
            } else {
                error("Serper search failed with code ${response.code}: ${response.message}")
            }
        }
    }

    @Serializable
    data class SerperSearchResponse(
        val answerBox: AnswerBox? = null,
        val knowledgeGraph: KnowledgeGraph? = null,
        val organic: List<OrganicResult> = emptyList(),
    )

    @Serializable
    data class AnswerBox(
        val answer: String? = null,
        val snippet: String? = null,
        val title: String? = null,
    )

    @Serializable
    data class KnowledgeGraph(
        val title: String? = null,
        val description: String? = null,
    )

    @Serializable
    data class OrganicResult(
        val title: String,
        val link: String,
        val snippet: String? = null,
    )
}
