package com.aiverse.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiverse.R
import com.aiverse.core.ui.AppSwitch
import com.aiverse.core.ui.AppTextField
import com.aiverse.feature.search.domain.SearchCommonOptions
import com.aiverse.feature.search.domain.SearchResult
import com.aiverse.feature.search.domain.SearchService
import com.aiverse.feature.search.domain.SearchServiceOptions
import com.aiverse.feature.search.presentation.SearchProviderViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 搜索服务详情编辑页：名字放顶栏标题（最左），按服务类型动态渲染配置字段（apiKey / url / depth /
 * model / mode 等），编辑即实时保存（写回仓库，无底部保存按钮）；下方提供搜索测试卡（查询输入 +
 * 测试按钮 + 结果展示）。模仿 RikkaHub 的 SettingSearchDetailPage。
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Composable
fun SearchProviderDetailSection(
    viewModel: SearchProviderViewModel,
    serviceId: kotlin.uuid.Uuid?,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val service = state.services.find { it.id == serviceId } ?: return
    var options by remember(service.id) { mutableStateOf(service) }

    fun update(newOptions: SearchServiceOptions) {
        options = newOptions
        viewModel.updateService(newOptions)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶栏标题：服务名靠左
        Text(
            text = options.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 配置卡：按类型渲染字段，编辑即实时保存
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.search_provider_config),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                when (options) {
                    is SearchServiceOptions.BingLocalOptions -> {
                        // Bing 本地搜索无需配置
                    }
                    is SearchServiceOptions.ZhipuOptions -> {
                        val opt = options as SearchServiceOptions.ZhipuOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.DoubaoOptions -> {
                        val opt = options as SearchServiceOptions.DoubaoOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                        ModeField(opt.mode.name) { mode ->
                            update(opt.copy(mode = com.aiverse.feature.search.domain.DoubaoSearchMode.valueOf(mode)))
                        }
                    }
                    is SearchServiceOptions.TavilyOptions -> {
                        val opt = options as SearchServiceOptions.TavilyOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                        DepthField(opt.depth) { update(opt.copy(depth = it)) }
                        SwitchField(
                            checked = opt.includeAnswer,
                            label = stringResource(R.string.search_provider_include_answer),
                            onCheckedChange = { update(opt.copy(includeAnswer = it)) }
                        )
                    }
                    is SearchServiceOptions.ExaOptions -> {
                        val opt = options as SearchServiceOptions.ExaOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.SearXNGOptions -> {
                        val opt = options as SearchServiceOptions.SearXNGOptions
                        UrlField(opt.url) { update(opt.copy(url = it)) }
                        PlainField(opt.engines, stringResource(R.string.search_provider_engines)) { update(opt.copy(engines = it)) }
                        PlainField(opt.language, stringResource(R.string.search_provider_language)) { update(opt.copy(language = it)) }
                        PlainField(opt.username, stringResource(R.string.search_provider_username)) { update(opt.copy(username = it)) }
                        PlainField(opt.password, stringResource(R.string.search_provider_password), password = true) { update(opt.copy(password = it)) }
                    }
                    is SearchServiceOptions.LinkUpOptions -> {
                        val opt = options as SearchServiceOptions.LinkUpOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                        DepthField(opt.depth) { update(opt.copy(depth = it)) }
                    }
                    is SearchServiceOptions.BraveOptions -> {
                        val opt = options as SearchServiceOptions.BraveOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.MetasoOptions -> {
                        val opt = options as SearchServiceOptions.MetasoOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.OllamaOptions -> {
                        val opt = options as SearchServiceOptions.OllamaOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.PerplexityOptions -> {
                        val opt = options as SearchServiceOptions.PerplexityOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                        IntField(opt.maxTokens, stringResource(R.string.search_provider_max_tokens)) { update(opt.copy(maxTokens = it)) }
                        IntField(opt.maxTokensPerPage, stringResource(R.string.search_provider_max_tokens_per_page)) { update(opt.copy(maxTokensPerPage = it)) }
                    }
                    is SearchServiceOptions.FirecrawlOptions -> {
                        val opt = options as SearchServiceOptions.FirecrawlOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.JinaOptions -> {
                        val opt = options as SearchServiceOptions.JinaOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                        UrlField(opt.searchUrl, stringResource(R.string.search_provider_search_url)) { update(opt.copy(searchUrl = it)) }
                        UrlField(opt.scrapeUrl, stringResource(R.string.search_provider_scrape_url)) { update(opt.copy(scrapeUrl = it)) }
                    }
                    is SearchServiceOptions.BochaOptions -> {
                        val opt = options as SearchServiceOptions.BochaOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                        SwitchField(
                            checked = opt.summary,
                            label = stringResource(R.string.search_provider_summary),
                            onCheckedChange = { update(opt.copy(summary = it)) }
                        )
                    }
                    is SearchServiceOptions.GrokOptions -> {
                        val opt = options as SearchServiceOptions.GrokOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                        PlainField(opt.model, stringResource(R.string.search_provider_model)) { update(opt.copy(model = it)) }
                        UrlField(opt.customUrl, stringResource(R.string.search_provider_custom_url)) { update(opt.copy(customUrl = it)) }
                        MultilineField(opt.systemPrompt, stringResource(R.string.search_provider_system_prompt)) { update(opt.copy(systemPrompt = it)) }
                    }
                    is SearchServiceOptions.TinyfishOptions -> {
                        val opt = options as SearchServiceOptions.TinyfishOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.SerperOptions -> {
                        val opt = options as SearchServiceOptions.SerperOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                    is SearchServiceOptions.DeepSeekOptions -> {
                        val opt = options as SearchServiceOptions.DeepSeekOptions
                        ApiKeyField(opt.apiKey) { update(opt.copy(apiKey = it)) }
                    }
                }

                // 有 API Key 的服务提供「点击获取密钥」入口
                apiKeyUrl(options)?.let { url ->
                    GetApiKeyLink(url)
                }
            }
        }

        // 搜索测试卡
        SearchTestSection(
            options = options,
            commonOptions = state.common
        )
    }
}

@Composable
private fun ApiKeyField(value: String, onValueChange: (String) -> Unit) {
    PlainField(value, stringResource(R.string.search_provider_api_key), password = true, onValueChange = onValueChange)
}

@Composable
private fun GetApiKeyLink(url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(url) }) {
        Text(stringResource(R.string.search_provider_get_api_key))
    }
}

@Composable
private fun UrlField(value: String, label: String = stringResource(R.string.search_provider_url), onValueChange: (String) -> Unit) {
    PlainField(value, label, onValueChange = onValueChange)
}

@Composable
private fun PlainField(
    value: String,
    label: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default
    )
}

@Composable
private fun MultilineField(value: String, label: String, onValueChange: (String) -> Unit) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = false,
        minLines = 3
    )
}

@Composable
private fun IntField(value: Int?, label: String, onValueChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    AppTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(6)
            onValueChange(text.toIntOrNull())
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun SwitchField(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DepthField(value: String, onValueChange: (String) -> Unit) {
    PlainField(value, stringResource(R.string.search_provider_depth), onValueChange = onValueChange)
}

@Composable
private fun ModeField(value: String, onValueChange: (String) -> Unit) {
    PlainField(value, stringResource(R.string.search_provider_mode), onValueChange = onValueChange)
}

/** 搜索测试卡：查询输入 + 测试按钮 + 结果展示，直接调用对应服务的 search()。 */
@Composable
private fun SearchTestSection(
    options: SearchServiceOptions,
    commonOptions: SearchCommonOptions
) {
    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SearchResult>?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.search_provider_test),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.search_provider_test_query_hint),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (query.isNotBlank() && !testing) {
                            testing = true
                            result = null
                            scope.launch {
                                val service = SearchService.getService(options)
                                val params = JsonObject(mapOf("query" to JsonPrimitive(query)))
                                result = service.search(params, commonOptions, options)
                                testing = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.search_provider_test_run))
                    }
                }
            }

            result?.let { res ->
                res.onSuccess { searchResult ->
                    searchResult.answer?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    searchResult.items.forEachIndexed { index, item ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${item.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.text.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                res.onFailure { error ->
                    Text(
                        text = error.message ?: stringResource(R.string.search_provider_unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 各服务获取 API Key 的官网地址；无需 API Key 的服务（Bing / SearXNG）返回 null。 */
private fun apiKeyUrl(options: SearchServiceOptions): String? = when (options) {
    is SearchServiceOptions.ZhipuOptions -> "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
    is SearchServiceOptions.DoubaoOptions -> "https://console.volcengine.com/search-infinity/api-key"
    is SearchServiceOptions.TavilyOptions -> "https://app.tavily.com/home"
    is SearchServiceOptions.ExaOptions -> "https://dashboard.exa.ai/api-keys"
    is SearchServiceOptions.LinkUpOptions -> "https://www.linkup.so/"
    is SearchServiceOptions.BraveOptions -> "https://api.search.brave.com/"
    is SearchServiceOptions.MetasoOptions -> "https://metaso.cn/"
    is SearchServiceOptions.OllamaOptions -> "https://ollama.com/settings/keys"
    is SearchServiceOptions.PerplexityOptions -> "https://www.perplexity.ai/settings/api"
    is SearchServiceOptions.FirecrawlOptions -> "https://docs.firecrawl.dev/features/search"
    is SearchServiceOptions.JinaOptions -> "https://jina.ai/"
    is SearchServiceOptions.BochaOptions -> "https://open.bochaai.com/"
    is SearchServiceOptions.GrokOptions -> "https://console.x.ai/"
    is SearchServiceOptions.TinyfishOptions -> "https://agent.tinyfish.ai/api-keys"
    is SearchServiceOptions.SerperOptions -> "https://serper.dev/api-keys"
    is SearchServiceOptions.DeepSeekOptions -> "https://platform.deepseek.com/api_keys"
    else -> null
}
