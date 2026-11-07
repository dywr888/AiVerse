package com.aiverse.feature.settings.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 余额请求头：名称 + 值模板。值支持 `{{baseUrl}}`（提供商 baseUrl）与 `{{apiKey}}`（实际 Key）占位符。
 */
data class BalanceHeader(
    val name: String,
    val value: String
)

/**
 * 某个 AI 提供商的余额查询配置。
 *
 * [urlTemplate] 与 [headers] 的 value 支持 `{{baseUrl}}` / `{{apiKey}}` 占位符；
 * [jsonKey] 支持点号与数组下标，如 `balance_infos[0].total_balance`。
 */
data class BalanceConfig(
    /** 该提供商「获取余额」开关（胶囊左右滑动控制）。 */
    val enabled: Boolean = true,
    /** 余额接口 URL 模板，默认 deepseek 的 `{{baseUrl}}/user/balance`。 */
    val urlTemplate: String = "{{baseUrl}}/user/balance",
    /** 请求头列表（默认 Authorization: Bearer {{apiKey}}）。 */
    val headers: List<BalanceHeader> = listOf(BalanceHeader("Authorization", "Bearer {{apiKey}}")),
    /** 结果 JSON 键，如 `balance_infos[0].total_balance`。 */
    val jsonKey: String = "balance_infos[0].total_balance",
    /** 额外请求头（用户自定义，追加在 [headers] 之后）。 */
    val extraHeaders: List<BalanceHeader> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("url", urlTemplate)
        put("headers", headers.toJsonArray())
        put("jsonKey", jsonKey)
        put("extraHeaders", extraHeaders.toJsonArray())
    }

    companion object {
        fun fromJson(obj: JSONObject): BalanceConfig = BalanceConfig(
            enabled = obj.optBoolean("enabled", true),
            urlTemplate = obj.optString("url", "{{baseUrl}}/user/balance"),
            headers = obj.optJSONArray("headers")?.toHeaderList()
                ?: listOf(BalanceHeader("Authorization", "Bearer {{apiKey}}")),
            jsonKey = obj.optString("jsonKey", "balance_infos[0].total_balance"),
            extraHeaders = obj.optJSONArray("extraHeaders")?.toHeaderList() ?: emptyList()
        )
    }
}

private fun List<BalanceHeader>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { put(JSONObject().put("name", it.name).put("value", it.value)) }
}

private fun JSONArray.toHeaderList(): List<BalanceHeader> =
    (0 until length()).mapNotNull { i ->
        runCatching {
            val h = getJSONObject(i)
            BalanceHeader(h.optString("name"), h.optString("value"))
        }.getOrNull()
    }.filter { it.name.isNotBlank() || it.value.isNotBlank() }

/** 适配余额查询的提供商种类。 */
enum class BalanceProviderKind { DEEPSEEK, SILICONFLOW, NONE }

/**
 * 判断提供商是否适配余额查询及对应种类。
 * deepseek / 硅基流动 都是 OpenAI 兼容（OPENAI 类型），靠名称或 baseUrl 特征识别。
 */
fun balanceKindOf(provider: AIProviderConfig): BalanceProviderKind {
    val name = provider.name.lowercase()
    val base = provider.baseUrl.lowercase()
    return when {
        "deepseek" in name || "deepseek" in base -> BalanceProviderKind.DEEPSEEK
        "硅基" in provider.name || "silicon" in base || "siliconflow" in name -> BalanceProviderKind.SILICONFLOW
        else -> BalanceProviderKind.NONE
    }
}

/**
 * 任何提供商都有默认余额配置（默认关闭，仅 deepseek 默认开启）。
 * deepseek：`{{baseUrl}}/user/balance`，键 `balance_infos[0].total_balance`（默认开启）；
 * 硅基流动：`{{baseUrl}}/user/info`，键 `data.totalBalance`（该接口已下线，请求会失败，用户可自定义改掉）；
 * 其它（未知 / 中转 / 自定义）：通用 `{{baseUrl}}/user/balance` + `data.balance`，由用户在编辑页按实际接口改。
 */
fun defaultBalanceConfig(provider: AIProviderConfig): BalanceConfig = when (balanceKindOf(provider)) {
    BalanceProviderKind.DEEPSEEK -> BalanceConfig(
        enabled = true,
        urlTemplate = "{{baseUrl}}/user/balance",
        headers = listOf(BalanceHeader("Authorization", "Bearer {{apiKey}}")),
        jsonKey = "balance_infos[0].total_balance"
    )
    BalanceProviderKind.SILICONFLOW -> BalanceConfig(
        enabled = false,
        urlTemplate = "{{baseUrl}}/user/info",
        headers = listOf(BalanceHeader("Authorization", "Bearer {{apiKey}}")),
        jsonKey = "data.totalBalance"
    )
    BalanceProviderKind.NONE -> BalanceConfig(
        enabled = false,
        urlTemplate = "{{baseUrl}}/user/balance",
        headers = listOf(BalanceHeader("Authorization", "Bearer {{apiKey}}")),
        jsonKey = "data.balance"
    )
}
