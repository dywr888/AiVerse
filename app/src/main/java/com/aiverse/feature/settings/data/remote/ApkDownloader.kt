package com.aiverse.feature.settings.data.remote

import android.content.Context
import com.aiverse.core.net.AppProxy
import com.aiverse.feature.settings.data.repository.buildDownloadUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 更新包下载结果。 */
sealed interface ApkDownloadResult {
    /** 下载完成且（若有摘要）校验通过；[file] 已落在 cacheDir，可直接交给安装器。 */
    data class Success(val file: File, val sha256: String) : ApkDownloadResult

    /** 镜像与直连都失败，[message] 为最后一次失败原因。 */
    data class Failure(val message: String) : ApkDownloadResult
}

/**
 * 更新包下载：**64 分片 Range 并发 + 断点续传**，全部片合并后校验 sha256。
 *
 * 默认先走镜像源加速，失败自动回退直连 GitHub 重试一次。与 [UpdateCheckService] 一样必须挂
 * [AppProxy.okHttpAuthenticator]——OkHttp 默认不响应代理的 407 质询，漏挂会让配了代理的用户
 * 下载必然失败（检测却正常，现象很迷惑）。
 *
 * 文件落 `cacheDir/update/`（`file_paths.xml` 已声明 cache-path，可直接 FileProvider 授权）。
 * 分片落 `cacheDir/update/<fileName>.parts/part-<i>`，某片已完整则下次跳过（断点续传）；
 * 全部完成合并到 `<fileName>`，合并时边写边算 sha256 并与 GitHub API 的 `digest` 核对。
 * 服务器不支持 Range（或拿不到总大小）时回退单流下载。
 */
@Singleton
class ApkDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    /**
     * 下载 [url] 指向的更新包。固定尝试三个地址（与设置里的镜像源无关）：
     * ① gitcode 镜像（[buildGitcodeUrl]）→ ② 直连 GitHub → ③ v6.gh-proxy.org 镜像。
     *
     * [expectedSize] / [expectedSha256] 任一不符都视为该地址失败（继续换下一个地址），
     * 因为镜像返回 HTML 错误页或半截文件时 HTTP 状态码可能仍是 200。
     */
    suspend fun download(
        url: String,
        fileName: String,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        // 清掉历史版本的包（保留当前文件与其分片目录，供断点续传），别在 cacheDir 里堆积
        dir.listFiles()?.forEach { f ->
            if (f.name != fileName && !f.name.startsWith("$fileName.parts")) f.delete()
        }
        val dest = File(dir, fileName)

        val candidates = buildList {
            buildGitcodeUrl(url)?.let(::add)
            add(url)
            buildDownloadUrl(url, V6_MIRROR).takeIf { it != url }?.let(::add)
        }

        var lastError = ""
        for (candidate in candidates) {
            val result = runCatching {
                downloadChunked(candidate, dir, dest, fileName, expectedSize, expectedSha256, onProgress)
            }
            result.getOrNull()?.let { return@withContext ApkDownloadResult.Success(dest, it) }
            lastError = result.exceptionOrNull()?.message.orEmpty().ifBlank { UNKNOWN_ERROR }
            // 换下一个地址前清掉半成品分片与目标文件，避免混用两个源的片
            File(dir, "$fileName.parts").deleteRecursively()
            dest.delete()
        }
        ApkDownloadResult.Failure(lastError)
    }

    /**
     * 分片下载主流程：探总大小与 Range 支持 → 64 片并发下载（断点续传）→ 合并 → sha256 校验。
     *
     * 服务器不支持 Range 或拿不到总大小时回退单流 [fetch]（镜像多为全量代理，Range 可能失效）。
     */
    private suspend fun downloadChunked(
        url: String,
        dir: File,
        dest: File,
        fileName: String,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): String {
        val head = probeHead(url)
        val total = head?.contentLength?.takeIf { it > 0 } ?: expectedSize
        // 拿不到总大小，或服务器明确不支持 Range → 只能单流下载
        if (total <= 0 || head?.rangeSupported != true) {
            return fetch(url, dest, expectedSize, expectedSha256, onProgress)
        }

        val partsDir = File(dir, "$fileName.parts").apply { mkdirs() }
        val chunkSize = (total + CHUNKS - 1) / CHUNKS
        val semaphore = Semaphore(CONCURRENCY)
        var downloaded = 0L
        var lastNotify = 0L
        val progressLock = Any()

        // 累加已完成字节并节流上报进度（大文件逐片回调会把进度状态刷成重组风暴）
        fun addProgress(bytes: Long) {
            synchronized(progressLock) {
                downloaded += bytes
                val now = System.currentTimeMillis()
                if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                    lastNotify = now
                    onProgress(downloaded, total)
                }
            }
        }

        coroutineScope {
            (0 until CHUNKS).map { index ->
                async(Dispatchers.IO) {
                    val start = index.toLong() * chunkSize
                    val end = (start + chunkSize - 1).coerceAtMost(total - 1)
                    val need = end - start + 1
                    val partFile = File(partsDir, "part-$index")
                    // 断点续传：该片已完整（大小正好）则直接计入进度，跳过重下
                    if (partFile.length() == need) {
                        addProgress(need)
                        return@async
                    }
                    semaphore.withPermit {
                        downloadRange(url, start, end, partFile, need) {
                            addProgress(it)
                        }
                    }
                }
            }.awaitAll()
        }

        // 全部片就位：合并前强制进度 100%（合并阶段不再走分片回调，避免进度停在低值就被判完成）
        onProgress(total, total)

        // 全部片就位后合并，边写边算 sha256
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var merged = 0L
        dest.outputStream().use { output ->
            for (index in 0 until CHUNKS) {
                val start = index.toLong() * chunkSize
                val end = (start + chunkSize - 1).coerceAtMost(total - 1)
                val need = end - start + 1
                val partFile = File(partsDir, "part-$index")
                if (partFile.length() != need) {
                    throw IOException("分片 $index 不完整（${partFile.length()}/$need）")
                }
                partFile.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        merged += n
                    }
                }
            }
        }
        if (merged != total) {
            throw IOException("安装包大小不符（$merged/$total）")
        }
        if (expectedSize > 0 && merged != expectedSize) {
            throw IOException("安装包大小不符（$merged/$expectedSize）")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!expectedSha256.isNullOrBlank() && !sha.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("安装包摘要校验未通过")
        }
        partsDir.deleteRecursively()
        return sha
    }

    /** HEAD 探测：总大小与是否支持 Range。网络异常时返回 null（调用方退回 API 的大小 / 单流）。 */
    private fun probeHead(url: String): HeadInfo? = runCatching {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: 0L
            val range = resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            HeadInfo(contentLength, range)
        }
    }.getOrNull()

    /** 单片 Range 下载：写 [partFile]（覆盖），返回实际字节数；大小不符或非 206 抛异常。 */
    private suspend fun downloadRange(
        url: String,
        start: Long,
        end: Long,
        partFile: File,
        need: Long,
        onProgress: (Long) -> Unit
    ): Unit = suspendCancellableCoroutine { cont ->
        val call = client.newCall(
            Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        )
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.code != HTTP_PARTIAL_CONTENT) {
                        throw IOException("Range 下载失败 HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("响应体为空")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    // 节流窗口内累计字节（不是最后一块），避免漏报导致进度虚低/速度失真
                    var windowBytes = 0L
                    var lastNotify = 0L
                    body.byteStream().use { input ->
                        partFile.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                written += n
                                windowBytes += n
                                val now = System.currentTimeMillis()
                                if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                                    lastNotify = now
                                    onProgress(windowBytes)
                                    windowBytes = 0L
                                }
                            }
                        }
                    }
                    if (windowBytes > 0) onProgress(windowBytes)
                    if (written != need) {
                        throw IOException("分片大小不符（$written/$need）")
                    }
                    onProgress(0) // 收尾上报（不新增字节，只触发一次进度刷新）
                    cont.resume(Unit)
                } catch (e: Exception) {
                    partFile.delete()
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            }
        })
    }

    /** 单地址整包下载（Range 不可用时的回退）：返回实际文件的 sha256，任一步不符抛异常。 */
    private suspend fun fetch(
        url: String,
        dest: File,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("响应体为空")
                    // 服务器可能不给 Content-Length，退回 API 报的大小用于进度显示
                    val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = 0L
                    var lastNotify = 0L
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                read += n
                                val now = System.currentTimeMillis()
                                if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                                    lastNotify = now
                                    onProgress(read, total)
                                }
                            }
                        }
                    }
                    onProgress(read, total)
                    if (expectedSize > 0 && read != expectedSize) {
                        throw IOException("安装包大小不符（$read/$expectedSize）")
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!expectedSha256.isNullOrBlank() && !sha.equals(expectedSha256, ignoreCase = true)) {
                        throw IOException("安装包摘要校验未通过")
                    }
                    cont.resume(sha)
                } catch (e: Exception) {
                    dest.delete()
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            }
        })
    }

    private data class HeadInfo(val contentLength: Long, val rangeSupported: Boolean)

    /**
     * 把 GitHub 原始 release 下载地址转成 gitcode 镜像（固定仓库 `qldwi/aiverse`，与设置镜像源无关）。
     * 仅当 URL 含 `/releases/download/` 时生效，否则返回 null（跳过该镜像）。
     */
    private fun buildGitcodeUrl(original: String): String? {
        val marker = "/releases/download/"
        val idx = original.indexOf(marker)
        if (idx < 0) return null
        return "https://gitcode.com/qldwi/aiverse" + original.substring(idx)
    }

    private companion object {
        const val UPDATE_DIR = "update"
        const val CHUNKS = 64
        const val CONCURRENCY = 16
        const val HTTP_PARTIAL_CONTENT = 206
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val PROGRESS_INTERVAL_MS = 100L
        const val UNKNOWN_ERROR = "下载失败"
        /** 固定备选镜像（与设置里的镜像源无关，仅当直连也失败时使用）。 */
        const val V6_MIRROR = "https://v6.gh-proxy.org/"
    }
}
