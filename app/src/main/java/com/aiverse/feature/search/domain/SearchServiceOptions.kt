@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.aiverse.feature.search.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/** 搜索 query 参数的提示文案（供上层生成工具描述时使用）。 */
internal const val SEARCH_QUERY_DESCRIPTION =
    "Concise search keywords, not a full sentence. Write them in the language the best sources on the topic " +
        "are written in: usually English for technology, science and international topics; the local language " +
        "for region-specific topics such as local news, policies, communities or products. " +
        "Keep proper nouns, product names and error messages verbatim."

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
    /** Local time at which this result was retrieved; not a publication date. */
    val retrievedAt: String? = null,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
        /** Provider-supplied publication date. Null means that no date was supplied. */
        val publishedDate: String? = null,
        val highlights: List<String> = emptyList(),
    )
}

@Serializable
enum class DoubaoSearchMode {
    @SerialName("global")
    GLOBAL,

    @SerialName("custom")
    CUSTOM,
}

/**
 * 搜索服务配置（可多态序列化）。每个子类对应一种外部搜索服务，字段与默认值由各服务决定；
 * [id] 是实例唯一标识，用于区分同一服务的多份配置。
 */
@Serializable
sealed class SearchServiceOptions {
    abstract val id: Uuid

    open val displayName: String
        get() = TYPES[this::class] ?: "Unknown"

    companion object {
        val DEFAULT = BingLocalOptions()

        val TYPES = mapOf(
            BingLocalOptions::class to "Bing",
            ZhipuOptions::class to "智谱",
            DoubaoOptions::class to "豆包",
            TavilyOptions::class to "Tavily",
            ExaOptions::class to "Exa",
            SearXNGOptions::class to "SearXNG",
            LinkUpOptions::class to "LinkUp",
            BraveOptions::class to "Brave",
            MetasoOptions::class to "秘塔",
            OllamaOptions::class to "Ollama",
            PerplexityOptions::class to "Perplexity",
            FirecrawlOptions::class to "Firecrawl",
            JinaOptions::class to "Jina",
            BochaOptions::class to "博查",
            GrokOptions::class to "Grok",
            TinyfishOptions::class to "Tinyfish",
            SerperOptions::class to "Serper",
            DeepSeekOptions::class to "DeepSeek",
        )

        /** 新建各类型的默认配置，避免依赖 kotlin-reflect 调用构造函数。 */
        val FACTORIES: Map<KClass<out SearchServiceOptions>, () -> SearchServiceOptions> = mapOf(
            BingLocalOptions::class to { BingLocalOptions() },
            ZhipuOptions::class to { ZhipuOptions() },
            DoubaoOptions::class to { DoubaoOptions() },
            TavilyOptions::class to { TavilyOptions() },
            ExaOptions::class to { ExaOptions() },
            SearXNGOptions::class to { SearXNGOptions() },
            LinkUpOptions::class to { LinkUpOptions() },
            BraveOptions::class to { BraveOptions() },
            MetasoOptions::class to { MetasoOptions() },
            OllamaOptions::class to { OllamaOptions() },
            PerplexityOptions::class to { PerplexityOptions() },
            FirecrawlOptions::class to { FirecrawlOptions() },
            JinaOptions::class to { JinaOptions() },
            BochaOptions::class to { BochaOptions() },
            GrokOptions::class to { GrokOptions() },
            TinyfishOptions::class to { TinyfishOptions() },
            SerperOptions::class to { SerperOptions() },
            DeepSeekOptions::class to { DeepSeekOptions() },
        )
    }

    @Serializable
    @SerialName("bing_local")
    data class BingLocalOptions(
        override val id: Uuid = Uuid.random()
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("zhipu")
    data class ZhipuOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("doubao")
    data class DoubaoOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val mode: DoubaoSearchMode = DoubaoSearchMode.CUSTOM,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "advanced",
        val includeAnswer: Boolean = true,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: Uuid = Uuid.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("linkup")
    data class LinkUpOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("brave")
    data class BraveOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("metaso")
    data class MetasoOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("ollama")
    data class OllamaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("perplexity")
    data class PerplexityOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val maxTokens: Int? = null,
        val maxTokensPerPage: Int? = null,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("firecrawl")
    data class FirecrawlOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("jina")
    data class JinaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val searchUrl: String = "https://s.jina.ai/",
        val scrapeUrl: String = "https://r.jina.ai/",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("bocha")
    data class BochaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val summary: Boolean = true,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("grok")
    data class GrokOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val model: String = "grok-4-1-fast-non-reasoning",
        val customUrl: String = "https://api.x.ai/v1/responses",
        val systemPrompt: String = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations.",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tinyfish")
    data class TinyfishOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("serper")
    data class SerperOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("deepseek")
    data class DeepSeekOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()
}
