package com.aiverse.core.tts

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** 在线 TTS 接口配置（OpenAI /audio/speech 兼容格式）。 */
data class OnlineTtsConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val voice: String,
    val speed: Float,
    val providerType: String = "auto"
) {
    val isUsable get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 在线 TTS 引擎：按 OpenAI `/audio/speech` 兼容协议 POST 请求拿 mp3，[MediaPlayer] 顺序播放。
 *
 * 适用于用户自填的 OpenAI 兼容端点（OpenAI 官方、硅基流动、第三方网关等）。文本超 4096 字符
 * 自动分段：逐段请求并播放，一段播完再请求下一段。stop 取消当前请求并停止播放。
 *
 * 与 [SystemTtsEngine] 的队列模型不同：这里用协程串行驱动（请求→播放→完成→下一段）。
 */
class OnlineTtsEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: MediaPlayer? = null
    private var job: Job? = null

    fun play(text: String, config: OnlineTtsConfig, onDone: () -> Unit, onError: (String) -> Unit) {
        stop()
        val segments = text.chunked(MAX_INPUT_CHARS)
        if (segments.isEmpty()) { onDone(); return }
        job = scope.launch {
            try {
                for (segment in segments) {
                    ensureActive()
                    val bytes = fetchAudio(segment, config) ?: run { onError("请求失败（HTTP 非 200，检查接口地址/API Key/模型）"); return@launch }
                    ensureActive()
                    playMp3Await(bytes)
                }
                onDone()
            } catch (_: CancellationException) {
                // stopped
            } catch (e: Exception) {
                onError(e.message ?: "网络异常")
            } finally {
                player?.let { runCatching { it.release() } }
                player = null
            }
        }
    }

    private suspend fun fetchAudio(text: String, config: OnlineTtsConfig): ByteArray? =
        withContext(Dispatchers.IO) {
            val base = config.baseUrl.trimEnd('/')
            val isFish = when (config.providerType) {
                "fish" -> true
                "openai" -> false
                else -> base.contains("fish.audio")
            }
            val url = when {
                base.endsWith("/v1/tts") -> base
                base.endsWith("/audio/speech") -> base
                base.endsWith("/compat/v1") -> base + "/audio/speech"
                isFish -> base + "/v1/tts"
                else -> base + "/audio/speech"
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Content-Type", "application/json")
                if (isFish) setRequestProperty("model", config.model)
                doOutput = true
            }
            try {
                val body = if (isFish) {
                    JSONObject().apply { put("text", text) }.toString()
                } else {
                    JSONObject().apply {
                        put("model", config.model)
                        put("input", text)
                        put("voice", config.voice)
                        put("speed", config.speed.toDouble())
                        put("response_format", "mp3")
                    }.toString()
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) return@withContext null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun playMp3Await(bytes: ByteArray) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val tmp = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3").apply { writeBytes(bytes) }
            val mp = MediaPlayer()
            var released = false
            try {
                mp.setDataSource(tmp.path)
                mp.setOnPreparedListener { it.start(); player = it }
                mp.setOnCompletionListener {
                    if (!released) { released = true; it.release(); player = null; tmp.delete() }
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { m, _, _ ->
                    if (!released) { released = true; m.release(); player = null; tmp.delete() }
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                mp.prepareAsync()
                player = mp
            } catch (e: Exception) {
                if (!released) { released = true; runCatching { mp.release() }; player = null; tmp.delete() }
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation {
                if (!released) { released = true; runCatching { mp.release() }; player = null; tmp.delete() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    fun release() {
        stop()
    }

    private companion object {
        // OpenAI /audio/speech 单次 input 上限 4096 字符。
        const val MAX_INPUT_CHARS = 4000
    }
}
