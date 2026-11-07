package com.aiverse.core.tts

import androidx.compose.runtime.staticCompositionLocalOf

/** 提供 [TtsController] 给消息气泡；未注入时为 null（不渲染播放按钮）。 */
val LocalTtsController = staticCompositionLocalOf<TtsController?> { null }
