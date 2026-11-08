package com.aiverse.feature.plugins.data.remote

import com.aiverse.core.net.AppProxy
import com.aiverse.feature.plugins.domain.model.PluginRepoEntry
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件仓库客户端：拉取 index.json、下载插件 zip。
 *
 * 地址按「镜像优先 → 直连回退」尝试：先走 gh-proxy 镜像，失败再直连
 * raw.githubusercontent.com（与 APK 下载的镜像/直连思路一致）。
 * OkHttp 挂载 [AppProxy.okHttpAuthenticator] 走全局/提供商代理。
 */
@Singleton
class PluginRepoClient @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .proxyAuthenticator(AppProxy.okHttpAuthenticator)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 拉取仓库 index.json，返回插件条目列表。全部候选失败抛异常（调用方处理）。 */
    fun fetchIndex(): List<PluginRepoEntry> {
        fetch(indexUrls()).use { resp ->
            val body = resp.body?.string() ?: throw IllegalStateException("仓库响应为空")
            return json.decodeFromString<List<PluginRepoEntry>>(body)
        }
    }

    /** 下载插件 zip 到 [cacheDir]，返回临时文件（调用方负责删除与安装）。 */
    fun downloadZip(entry: PluginRepoEntry, cacheDir: File): File {
        val encoded = URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20")
        fetch(zipUrls(encoded)).use { resp ->
            val body = resp.body ?: throw IllegalStateException("仓库响应为空")
            val f = File(cacheDir, "repo_${System.currentTimeMillis()}.zip")
            f.outputStream().use { body.byteStream().copyTo(it) }
            return f
        }
    }

    /** 按候选顺序请求，返回第一个成功的响应；全部失败抛最后一个异常。 */
    private fun fetch(urls: List<String>): Response {
        var last: Exception? = null
        for (url in urls) {
            try {
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (resp.isSuccessful) return resp
                resp.close()
                last = IllegalStateException("HTTP ${resp.code}")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("仓库请求失败")
    }

    private fun indexUrls(): List<String> =
        listOf(MIRROR + RAW_BASE + "index.json", RAW_BASE + "index.json")

    private fun zipUrls(encoded: String): List<String> =
        listOf(MIRROR + RAW_BASE + "$encoded.zip", RAW_BASE + "$encoded.zip")

    companion object {
        private const val MIRROR = "https://v6.gh-proxy.org/"
        private const val RAW_BASE = "https://raw.githubusercontent.com/yhdmgf/aiverseRules/main/"
    }
}
