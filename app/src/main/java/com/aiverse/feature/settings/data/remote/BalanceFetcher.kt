package com.aiverse.feature.settings.data.remote

import com.aiverse.feature.settings.domain.model.AIProviderConfig
import com.aiverse.feature.settings.domain.model.BalanceConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拉取 AI 提供商余额：按 [BalanceConfig] 的 url/header/jsonKey 模板发 GET 请求并解析结果。
 *
 * 与设置里的「镜像源」无关，纯粹按提供商配置发请求；超时/失败返回 [Result.failure]。
 */
@Singleton
class BalanceFetcher @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取余额，成功返回余额文本（如 `110.00`），失败返回错误信息（HTTP 状态 / 网络 / 键缺失）。
     */
    suspend fun fetchBalance(config: BalanceConfig, provider: AIProviderConfig): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = provider.baseUrl.trimEnd('/')
                val key = provider.firstUsableApiKey
                val url = config.urlTemplate
                    .replace("{{baseUrl}}", base)
                    .replace("{{apiKey}}", key)
                if (url.isBlank()) error("余额 URL 为空")
                val builder = Request.Builder().url(url)
                (config.headers + config.extraHeaders).forEach { h ->
                    val n = h.name.trim()
                    val v = h.value.replace("{{baseUrl}}", base).replace("{{apiKey}}", key).trim()
                    if (n.isNotBlank() && v.isNotBlank()) builder.addHeader(n, v)
                }
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: error("空响应")
                    val json = JSONObject(body)
                    parseJsonKey(json, config.jsonKey) ?: error("未找到键 ${config.jsonKey}")
                }
            }
        }

    /** 解析点号 + 数组下标的 JSON 键，如 `balance_infos[0].total_balance` / `data.totalBalance`。 */
    private fun parseJsonKey(root: JSONObject, key: String): String? {
        var current: Any = root
        val parts = Regex("([^.\\[\\]]+)|\\[(\\d+)]").findAll(key).map { m ->
            if (m.groupValues[2].isNotBlank()) m.groupValues[2] else m.groupValues[1]
        }.toList()
        for (p in parts) {
            current = when (current) {
                is JSONObject -> if (current.has(p)) current.get(p) else return null
                is JSONArray -> p.toIntOrNull()?.let { current.opt(it) } ?: return null
                else -> return null
            }
        }
        return current?.toString()
    }
}
