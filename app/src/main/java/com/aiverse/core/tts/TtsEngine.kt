package com.aiverse.core.tts

/** 系统 TTS 引擎抽象，由 [SystemTtsEngine] 实现（在线 TTS 用独立的 [OnlineTtsEngine]，不走此接口）。 */
interface TtsEngine {
    /** 引擎就绪回调（系统 TTS 异步初始化）。 */
    fun setOnReady(callback: () -> Unit)

    /** 设置朗读进度回调，utteranceId 对应 speak 时传入的 id。 */
    fun setOnUtteranceProgress(listener: (utteranceId: String, event: UtteranceEvent) -> Unit)

    /** 引擎初始化失败回调（如系统 TTS 未安装或不可用）。 */
    fun setOnError(callback: () -> Unit)

    /** 朗读一段文本。[utteranceId] 用于回调匹配；[flush] 为 true 时清空队列立即开始。 */
    fun speak(text: String, utteranceId: String, flush: Boolean)

    /** 停止当前朗读并清空队列。 */
    fun stop()

    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun release()
}

enum class UtteranceEvent { START, DONE, ERROR }
