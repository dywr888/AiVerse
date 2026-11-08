package com.aiverse.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiverse.core.ui.AppSwitch
import com.aiverse.core.ui.AppTextField
import com.aiverse.feature.search.domain.SearchServiceOptions
import com.aiverse.feature.search.presentation.SearchProviderViewModel
import com.aiverse.R

/**
 * 搜索服务详情编辑页：按服务类型动态渲染配置字段（apiKey / url / depth / model / mode 等），
 * 保存写回仓库。模仿 RikkaHub 的 SettingSearchDetailPage。
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = options.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        when (options) {
            is SearchServiceOptions.BingLocalOptions -> {
                // Bing 本地搜索无需配置
            }
            is SearchServiceOptions.ZhipuOptions -> {
                val opt = options as SearchServiceOptions.ZhipuOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.DoubaoOptions -> {
                val opt = options as SearchServiceOptions.DoubaoOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
                ModeField(opt.mode.name) { mode ->
                    options = opt.copy(mode = com.aiverse.feature.search.domain.DoubaoSearchMode.valueOf(mode))
                }
            }
            is SearchServiceOptions.TavilyOptions -> {
                val opt = options as SearchServiceOptions.TavilyOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
                DepthField(opt.depth) { options = opt.copy(depth = it) }
                SwitchField(
                    checked = opt.includeAnswer,
                    label = stringResource(R.string.search_provider_include_answer),
                    onCheckedChange = { options = opt.copy(includeAnswer = it) }
                )
            }
            is SearchServiceOptions.ExaOptions -> {
                val opt = options as SearchServiceOptions.ExaOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.SearXNGOptions -> {
                val opt = options as SearchServiceOptions.SearXNGOptions
                UrlField(opt.url) { options = opt.copy(url = it) }
                PlainField(opt.engines, stringResource(R.string.search_provider_engines)) { options = opt.copy(engines = it) }
                PlainField(opt.language, stringResource(R.string.search_provider_language)) { options = opt.copy(language = it) }
                PlainField(opt.username, stringResource(R.string.search_provider_username)) { options = opt.copy(username = it) }
                PlainField(opt.password, stringResource(R.string.search_provider_password), password = true) { options = opt.copy(password = it) }
            }
            is SearchServiceOptions.LinkUpOptions -> {
                val opt = options as SearchServiceOptions.LinkUpOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
                DepthField(opt.depth) { options = opt.copy(depth = it) }
            }
            is SearchServiceOptions.BraveOptions -> {
                val opt = options as SearchServiceOptions.BraveOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.MetasoOptions -> {
                val opt = options as SearchServiceOptions.MetasoOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.OllamaOptions -> {
                val opt = options as SearchServiceOptions.OllamaOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.PerplexityOptions -> {
                val opt = options as SearchServiceOptions.PerplexityOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
                IntField(opt.maxTokens, stringResource(R.string.search_provider_max_tokens)) { options = opt.copy(maxTokens = it) }
                IntField(opt.maxTokensPerPage, stringResource(R.string.search_provider_max_tokens_per_page)) { options = opt.copy(maxTokensPerPage = it) }
            }
            is SearchServiceOptions.FirecrawlOptions -> {
                val opt = options as SearchServiceOptions.FirecrawlOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.JinaOptions -> {
                val opt = options as SearchServiceOptions.JinaOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
                UrlField(opt.searchUrl, stringResource(R.string.search_provider_search_url)) { options = opt.copy(searchUrl = it) }
                UrlField(opt.scrapeUrl, stringResource(R.string.search_provider_scrape_url)) { options = opt.copy(scrapeUrl = it) }
            }
            is SearchServiceOptions.BochaOptions -> {
                val opt = options as SearchServiceOptions.BochaOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
                SwitchField(
                    checked = opt.summary,
                    label = stringResource(R.string.search_provider_summary),
                    onCheckedChange = { options = opt.copy(summary = it) }
                )
            }
            is SearchServiceOptions.GrokOptions -> {
                val opt = options as SearchServiceOptions.GrokOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
                PlainField(opt.model, stringResource(R.string.search_provider_model)) { options = opt.copy(model = it) }
                UrlField(opt.customUrl, stringResource(R.string.search_provider_custom_url)) { options = opt.copy(customUrl = it) }
                MultilineField(opt.systemPrompt, stringResource(R.string.search_provider_system_prompt)) { options = opt.copy(systemPrompt = it) }
            }
            is SearchServiceOptions.TinyfishOptions -> {
                val opt = options as SearchServiceOptions.TinyfishOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.SerperOptions -> {
                val opt = options as SearchServiceOptions.SerperOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
            is SearchServiceOptions.DeepSeekOptions -> {
                val opt = options as SearchServiceOptions.DeepSeekOptions
                ApiKeyField(opt.apiKey) { options = opt.copy(apiKey = it) }
            }
        }

        Button(
            onClick = { viewModel.updateService(options) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.search_provider_save))
        }
    }
}

@Composable
private fun ApiKeyField(value: String, onValueChange: (String) -> Unit) {
    PlainField(value, stringResource(R.string.search_provider_api_key), password = true, onValueChange = onValueChange)
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
    Card(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
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
