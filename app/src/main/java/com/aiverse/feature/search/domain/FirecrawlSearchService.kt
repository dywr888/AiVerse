package com.aiverse.feature.search.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiverse.feature.search.domain.SearchResult.SearchResultItem
import com.aiverse.feature.search.domain.SearchService.Companion.httpClient
import com.aiverse.feature.search.domain.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Firecrawl 搜索。 */
object FirecrawlSearchService : SearchService<SearchServiceOptions.FirecrawlOptions> {
    override val name: String = "Firecrawl"

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.FirecrawlOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val sources = params["sources"].asStringList()
            val categories = params["categories"].asStringList()

            val body = buildJsonObject {
                put("query", query)
                put("limit", commonOptions.resultSize)
                sources?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("sources", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
                categories?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("categories", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
            }

            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v2/search")
                .post(body.toString().toRequestBody())
                .addHeader("Content-Type", "application/json")
                .apply {
                    if (serviceOptions.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                    }
                }
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                error("response failed #${response.code}")
            }

            val bodyString = response.body!!.string()
            val payload = json.parseToJsonElement(bodyString).jsonObject
            val data = payload["data"]?.jsonObject ?: error("empty response data")
            val resultData = json.decodeFromJsonElement<FirecrawlSearchResultData>(data)
            val result = buildList {
                resultData.web?.forEach { item ->
                    add(SearchResultItem(title = item.title, url = item.url, text = item.description))
                }

                resultData.news?.forEach { item ->
                    add(
                        SearchResultItem(
                            title = item.title,
                            url = item.url,
                            text = """
                                ${item.snippet}
                                ${item.date}
                            """.trimIndent()
                        )
                    )
                }
            }
            SearchResult(
                items = result
            )
        }
    }

    private fun JsonElement?.asStringList(): List<String>? {
        return when (this) {
            is JsonArray -> this.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
            }

            is JsonPrimitive -> this.contentOrNull?.split(',')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

            else -> null
        }
    }
}

@Serializable
data class FirecrawlSearchResultWebItem(
    val url: String,
    val title: String,
    val description: String,
)

@Serializable
data class FirecrawlSearchResultNewsItem(
    val title: String,
    val url: String,
    val snippet: String,
    val date: String,
)

@Serializable
data class FirecrawlSearchResultData(
    val web: List<FirecrawlSearchResultWebItem>? = emptyList(),
    val news: List<FirecrawlSearchResultNewsItem>? = emptyList(),
)
