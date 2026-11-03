package com.aiverse.feature.settings.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** 扩展市场条目（来自后端 `?action=get`）。 */
data class ExtensionItem(
    val name: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val type: String,
    val enabled: Boolean
)

/**
 * 扩展市场 API：拉取扩展列表 + 下载扩展 APK。
 *
 * 后端只暴露 `?action=get`（列表）——列表项里的 `download_url` 指向 APK 文件；
 * 下载走单流直连（扩展 APK 是用户自己托管的文件，不需要更新包那套分片/校验/镜像回退）。
 */
@Singleton
class ExtensionApiService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 拉取扩展列表（`?action=get`）。 */
    suspend fun fetchExtensions(apiUrl: String): Result<List<ExtensionItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (apiUrl.contains('?')) "$apiUrl&action=get" else "$apiUrl?action=get"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(160)}")
                val root = json.parseToJsonElement(body).jsonObject
                val code = root["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                if (code != 0) error(root["message"]?.jsonPrimitive?.contentOrNull ?: "API 返回错误")
                (root["data"]?.jsonArray ?: return@use emptyList()).map { el ->
                    val o = el.jsonObject
                    ExtensionItem(
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        version = o["version"]?.jsonPrimitive?.contentOrNull ?: "",
                        description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        downloadUrl = o["download_url"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "mcp",
                        enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                    )
                }
            }
        }
    }

    /**
     * 下载扩展 APK 到 cacheDir/extensions/（单流，边下边报进度）。文件名取 URL 尾段，
     * 无扩展名时补 `.apk`。
     */
    suspend fun downloadApk(
        url: String,
        fallbackName: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "extensions").apply { mkdirs() }
            var fileName = url.substringAfterLast('/').ifBlank { fallbackName }
            if (!fileName.contains('.')) fileName = "$fileName.apk"
            val target = File(dir, fileName)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val total = response.body?.contentLength() ?: -1L
                val body = response.body ?: error("响应体为空")
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            onProgress(downloaded, if (total > 0) total else downloaded)
                        }
                    }
                }
            }
            target
        }
    }
}
