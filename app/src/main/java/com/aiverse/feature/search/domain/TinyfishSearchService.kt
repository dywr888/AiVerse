package com.aiverse.feature.search.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.aiverse.feature.search.domain.SearchResult.SearchResultItem
import com.aiverse.feature.search.domain.SearchService.Companion.httpClient
import com.aiverse.feature.search.domain.SearchService.Companion.json
import okhttp3.Request

/** Tinyfish 搜索。 */
object TinyfishSearchService : SearchService<SearchServiceOptions.TinyfishOptions> {
    override val name: String = "Tinyfish"

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TinyfishOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val purpose = params["purpose"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val url = buildString {
                append("https://api.search.tinyfish.ai")
                append("?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                if (purpose != null) {
                    append("&purpose=${java.net.URLEncoder.encode(purpose, "UTF-8")}")
                }
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("X-API-Key", serviceOptions.apiKey)
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body!!.string()
                val searchResponse = json.decodeFromString<TinyfishSearchResponse>(responseBody)

                val items = searchResponse.results.map { result ->
                    SearchResultItem(
                        title = result.title,
                        url = result.url,
                        text = result.snippet
                    )
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = null,
                        items = items
                    )
                )
            } else {
                error("Tinyfish search failed with code ${response.code}: ${response.message}")
            }
        }
    }

    @Serializable
    data class TinyfishSearchResponse(
        val query: String? = null,
        val results: List<TinyfishSearchResultItem> = emptyList(),
        @SerialName("total_results")
        val totalResults: Int? = null,
        val page: Int? = null,
    )

    @Serializable
    data class TinyfishSearchResultItem(
        val position: Int? = null,
        @SerialName("site_name")
        val siteName: String? = null,
        val title: String,
        val snippet: String = "",
        val url: String,
    )
}
