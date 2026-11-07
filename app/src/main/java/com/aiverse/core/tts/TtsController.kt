package com.aiverse.core.tts

import android.content.Context
import com.aiverse.feature.settings.data.repository.TtsCodeBlockMode
import com.aiverse.feature.settings.data.repository.TtsSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 朗读播放控制器：管理「正在朗读哪条消息 / 是否播放中」状态，协调 [SystemTtsEngine]，
 * 从 markdown 提取朗读文本（跳过代码块），按引擎长度上限分段入队。
 *
 * 状态以 [StateFlow] 暴露给 UI：正在播放的消息高亮、按钮图标切换。单例，App 生命周期内复用引擎。
 * 设置（语速/音调/代码块模式）通过后台 collect 缓存，play 时直接取用（避免同步读 DataStore）。
 */
@Singleton
class TtsController @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: TtsSettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = SystemTtsEngine(context)
    private val onlineEngine = OnlineTtsEngine(context)

    private val _currentMessageId = MutableStateFlow<String?>(null)
    val currentMessageId: StateFlow<String?> = _currentMessageId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    /** 是否自动朗读新回复（透传设置）。 */
    val autoReadFlow get() = settings.autoReadFlow

    @Volatile private var cachedRate = 1f
    @Volatile private var cachedPitch = 1f
    @Volatile private var cachedCodeBlockMode = TtsCodeBlockMode.SKIP
    @Volatile private var cachedOnlineEnabled = false
    @Volatile private var cachedBaseUrl = ""
    @Volatile private var cachedApiKey = ""
    @Volatile private var cachedModel = "tts-1"
    @Volatile private var cachedVoice = "alloy"
    @Volatile private var cachedProviderType = "auto"
    private var lastSegmentId: String? = null

    init {
        scope.launch { settings.rateFlow.collect { cachedRate = it; engine.setRate(it) } }
        scope.launch { settings.pitchFlow.collect { cachedPitch = it; engine.setPitch(it) } }
        scope.launch { settings.codeBlockModeFlow.collect { cachedCodeBlockMode = it } }
        scope.launch { settings.onlineEnabledFlow.collect { cachedOnlineEnabled = it } }
        scope.launch { settings.baseUrlFlow.collect { cachedBaseUrl = it } }
        scope.launch { settings.apiKeyFlow.collect { cachedApiKey = it } }
        scope.launch { settings.modelFlow.collect { cachedModel = it } }
        scope.launch { settings.voiceFlow.collect { cachedVoice = it } }
        scope.launch { settings.providerTypeFlow.collect { cachedProviderType = it } }

        engine.setOnUtteranceProgress { id, event ->
            when (event) {
                UtteranceEvent.START -> {}
                UtteranceEvent.DONE -> {
                    if (id == lastSegmentId) finish()
                }
                UtteranceEvent.ERROR -> finish()
            }
        }
        engine.setOnError { scope.launch { _errorMessage.emit("系统 TTS 不可用：未安装语音引擎或初始化失败") } }
        engine.setOnReady {
            engine.setRate(cachedRate)
            engine.setPitch(cachedPitch)
        }
    }

    /** 朗读一条消息（markdown）。会先停止当前朗读。 */
    fun play(messageId: String, markdown: String) {
        stop()
        val text = SpeechTextExtractor.extract(markdown, cachedCodeBlockMode)
        if (text.isBlank()) return
        _currentMessageId.value = messageId
        _isPlaying.value = true

        val onlineConfig = OnlineTtsConfig(cachedBaseUrl, cachedApiKey, cachedModel, cachedVoice, cachedRate, cachedProviderType)
        if (cachedOnlineEnabled && onlineConfig.isUsable) {
            onlineEngine.play(text, onlineConfig, onDone = { finish() }, onError = { msg ->
                scope.launch { _errorMessage.emit("在线 TTS 失败：$msg") }
                finish()
            })
            return
        }

        engine.setRate(cachedRate)
        engine.setPitch(cachedPitch)
        val chunks = text.chunked(MAX_TTS_CHARS)
        chunks.forEachIndexed { index, chunk ->
            val uid = "${messageId}_$index"
            if (index == chunks.lastIndex) lastSegmentId = uid
            engine.speak(chunk, uid, flush = index == 0)
        }
        if (chunks.isEmpty()) finish()
    }

    /** 停止朗读并清空状态。 */
    fun stop() {
        engine.stop()
        onlineEngine.stop()
        finish()
    }

    /** 切换：正在朗读本条则停止，否则开始朗读。 */
    fun toggle(messageId: String, markdown: String) {
        if (_currentMessageId.value == messageId && _isPlaying.value) stop()
        else play(messageId, markdown)
    }

    private fun finish() {
        lastSegmentId = null
        _isPlaying.value = false
        _currentMessageId.value = null
    }

    fun release() {
        engine.release()
        onlineEngine.release()
    }

    private companion object {
        // 系统 TTS 单次 speak 文本上限留余量（部分引擎 ~4000 字符），超长分段入队。
        const val MAX_TTS_CHARS = 3500
    }
}
