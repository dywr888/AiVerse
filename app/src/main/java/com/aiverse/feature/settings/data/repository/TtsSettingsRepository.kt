package com.aiverse.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiverse.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ttsDataStore by preferencesDataStore(
    name = "tts_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 代码块朗读策略：跳过或提示「代码块请查看」。 */
enum class TtsCodeBlockMode { SKIP, ANNOUNCE }

/**
 * 语音朗读（TTS）设置：语速、音调、自动朗读新回复、代码块处理，以及在线 TTS 接口配置
 * （启用开关、接口地址、API Key、模型、音色、接口类型）。
 *
 * 引擎用系统 TextToSpeech（离线免费）或 [com.aiverse.core.tts.OnlineTtsEngine]（在线）。
 * DataStore 用法与 [KeepaliveSettingsRepository] 一致。
 */
@Singleton
class TtsSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val RATE_KEY = floatPreferencesKey("tts_rate")
        val PITCH_KEY = floatPreferencesKey("tts_pitch")
        val AUTO_READ_KEY = booleanPreferencesKey("tts_auto_read")
        val CODE_BLOCK_KEY = stringPreferencesKey("tts_code_block_mode")
        val ONLINE_ENABLED_KEY = booleanPreferencesKey("tts_online_enabled")
        val BASE_URL_KEY = stringPreferencesKey("tts_base_url")
        val API_KEY_KEY = stringPreferencesKey("tts_api_key")
        val MODEL_KEY = stringPreferencesKey("tts_model")
        val VOICE_KEY = stringPreferencesKey("tts_voice")
        val PROVIDER_TYPE_KEY = stringPreferencesKey("tts_provider_type")
    }

    val rateFlow: Flow<Float> =
        context.ttsDataStore.data.map { (it[RATE_KEY] ?: 1f).coerceIn(0.25f, 4f) }

    val pitchFlow: Flow<Float> =
        context.ttsDataStore.data.map { (it[PITCH_KEY] ?: 1f).coerceIn(0.5f, 2f) }

    val autoReadFlow: Flow<Boolean> =
        context.ttsDataStore.data.map { it[AUTO_READ_KEY] ?: false }

    val codeBlockModeFlow: Flow<TtsCodeBlockMode> =
        context.ttsDataStore.data.map {
            when (it[CODE_BLOCK_KEY]) {
                "announce" -> TtsCodeBlockMode.ANNOUNCE
                else -> TtsCodeBlockMode.SKIP
            }
        }

    val onlineEnabledFlow: Flow<Boolean> =
        context.ttsDataStore.data.map { it[ONLINE_ENABLED_KEY] ?: false }

    val baseUrlFlow: Flow<String> =
        context.ttsDataStore.data.map { it[BASE_URL_KEY] ?: "" }

    val apiKeyFlow: Flow<String> =
        context.ttsDataStore.data.map { it[API_KEY_KEY] ?: "" }

    val modelFlow: Flow<String> =
        context.ttsDataStore.data.map { it[MODEL_KEY] ?: "tts-1" }

    val voiceFlow: Flow<String> =
        context.ttsDataStore.data.map { it[VOICE_KEY] ?: "alloy" }

    val providerTypeFlow: Flow<String> =
        context.ttsDataStore.data.map { it[PROVIDER_TYPE_KEY] ?: "auto" }

    suspend fun setRate(rate: Float) =
        context.ttsDataStore.edit { it[RATE_KEY] = rate.coerceIn(0.25f, 4f) }

    suspend fun setPitch(pitch: Float) =
        context.ttsDataStore.edit { it[PITCH_KEY] = pitch.coerceIn(0.5f, 2f) }

    suspend fun setAutoRead(enabled: Boolean) =
        context.ttsDataStore.edit { it[AUTO_READ_KEY] = enabled }

    suspend fun setCodeBlockMode(mode: TtsCodeBlockMode) =
        context.ttsDataStore.edit { it[CODE_BLOCK_KEY] = mode.name.lowercase() }

    suspend fun setOnlineEnabled(enabled: Boolean) =
        context.ttsDataStore.edit { it[ONLINE_ENABLED_KEY] = enabled }

    suspend fun setBaseUrl(url: String) =
        context.ttsDataStore.edit { it[BASE_URL_KEY] = url.trim() }

    suspend fun setApiKey(key: String) =
        context.ttsDataStore.edit { it[API_KEY_KEY] = key.trim() }

    suspend fun setModel(model: String) =
        context.ttsDataStore.edit { it[MODEL_KEY] = model.trim() }

    suspend fun setVoice(voice: String) =
        context.ttsDataStore.edit { it[VOICE_KEY] = voice.trim() }

    suspend fun setProviderType(type: String) =
        context.ttsDataStore.edit { it[PROVIDER_TYPE_KEY] = type }
}
