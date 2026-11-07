package com.aiverse.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 系统自带 TextToSpeech 引擎封装：离线、免费、可调速/换音色（系统语言与引擎决定）。
 *
 * 初始化是异步的（[TextToSpeech] 构造后回调 INIT），就绪前 speak 会被排队缓冲。
 * UtteranceProgressListener 回调 onStart/onDone/onError 驱动 [TtsController] 状态。
 */
class SystemTtsEngine(context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var errorCallback: (() -> Unit)? = null
    private var pendingTexts = mutableListOf<Triple<String, String, Boolean>>()
    private var readyCallback: (() -> Unit)? = null
    private var progressListener: ((String, UtteranceEvent) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        progressListener?.invoke(utteranceId, UtteranceEvent.START)
                    }
                    override fun onDone(utteranceId: String) {
                        progressListener?.invoke(utteranceId, UtteranceEvent.DONE)
                    }
                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String) {
                        progressListener?.invoke(utteranceId, UtteranceEvent.ERROR)
                    }
                })
                ready = true
                pendingTexts.forEach { (text, id, flush) -> speakInternal(text, id, flush) }
                pendingTexts.clear()
                readyCallback?.invoke()
            } else {
                errorCallback?.invoke()
            }
        }
    }

    override fun setOnReady(callback: () -> Unit) {
        readyCallback = callback
        if (ready) callback()
    }

    override fun setOnUtteranceProgress(listener: (String, UtteranceEvent) -> Unit) {
        progressListener = listener
    }

    override fun setOnError(callback: () -> Unit) {
        errorCallback = callback
    }

    override fun speak(text: String, utteranceId: String, flush: Boolean) {
        if (ready) speakInternal(text, utteranceId, flush)
        else pendingTexts.add(Triple(text, utteranceId, flush))
    }

    private fun speakInternal(text: String, utteranceId: String, flush: Boolean) {
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, utteranceId)
    }

    override fun stop() {
        pendingTexts.clear()
        tts?.stop()
    }

    override fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingTexts.clear()
    }
}
