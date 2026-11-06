package com.aiverse.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiverse.R
import com.aiverse.core.theme.AppThemePreset
import com.aiverse.core.theme.ChatBubbleStyle
import com.aiverse.core.theme.Spacing
import com.aiverse.core.theme.isDynamicColorSupported
import com.aiverse.core.theme.semanticColors
import com.aiverse.feature.settings.data.repository.AppThemeMode
import com.aiverse.feature.settings.data.repository.BackgroundSettingsRepository
import compose.icons.FeatherIcons
import compose.icons.feathericons.Image
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Moon
import compose.icons.feathericons.RotateCcw
import kotlin.math.roundToInt

/**
 * 外观设置二级页：把原来平铺在通用设置里的「主题 / 聊天气泡 / 背景图」收进来，
 * 并新增三档字体大小（聊天、输入框、界面缩放），均实时持久化。
 */
@Composable
internal fun AppearanceSettingsSection(
    themeMode: AppThemeMode,
    themePresetId: String?,
    dynamicColorEnabled: Boolean,
    bubbleStyle: ChatBubbleStyle,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    chatFontSizeSp: Int,
    inputFontSizeSp: Int,
    uiFontScale: Float,
    onOpenThemeSheet: () -> Unit,
    onOpenBackgroundSheet: () -> Unit,
    onOpenBubbleStyleSheet: () -> Unit,
    onSetChatFontSizeSp: (Int) -> Unit,
    onSetInputFontSizeSp: (Int) -> Unit,
    onSetUiFontScale: (Float) -> Unit,
    onRestoreDefaults: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_appearance_general))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Moon,
                title = stringResource(R.string.settings_theme_title),
                onClick = onOpenThemeSheet,
                trailing = {
                    val colorLabel = if (dynamicColorEnabled && isDynamicColorSupported) {
                        stringResource(R.string.theme_dynamic_color)
                    } else {
                        stringResource(AppThemePreset.findById(themePresetId).nameRes)
                    }
                    Text(
                        text = "${stringResource(themeMode.labelRes)} · $colorLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.settings_chat_bubble),
                subtitle = stringResource(bubbleStyle.labelRes),
                onClick = onOpenBubbleStyleSheet
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_background_image),
                onClick = onOpenBackgroundSheet,
                trailing = {
                    Text(
                        text = if (backgroundImagePath != null) {
                            "${BackgroundSettingsRepository.alphaToSlider(backgroundAlpha).toInt()}%"
                        } else {
                            stringResource(R.string.settings_background_image_none)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_appearance_fonts))
        SettingsGroup {
            FontSizeSliderRow(
                title = stringResource(R.string.settings_appearance_chat_font),
                value = chatFontSizeSp,
                min = AppearanceFontRanges.CHAT_MIN,
                max = AppearanceFontRanges.CHAT_MAX,
                onSet = onSetChatFontSizeSp
            )
            SettingsDivider()
            FontSizeSliderRow(
                title = stringResource(R.string.settings_appearance_input_font),
                value = inputFontSizeSp,
                min = AppearanceFontRanges.INPUT_MIN,
                max = AppearanceFontRanges.INPUT_MAX,
                onSet = onSetInputFontSizeSp
            )
            SettingsDivider()
            ScaleSliderRow(
                title = stringResource(R.string.settings_appearance_ui_scale),
                value = uiFontScale,
                onSet = onSetUiFontScale
            )
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.RotateCcw,
                title = stringResource(R.string.settings_appearance_reset),
                onClick = onRestoreDefaults
            )
        }
    }
}

private object AppearanceFontRanges {
    const val CHAT_MIN = 12
    const val CHAT_MAX = 22
    const val INPUT_MIN = 12
    const val INPUT_MAX = 22
}

/** 聊天 / 输入框字号滑块行：拖动实时预览，松手才落盘。 */
@Composable
private fun FontSizeSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onSet: (Int) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${local}sp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        Slider(
            value = local.toFloat(),
            onValueChange = { local = it.roundToInt() },
            onValueChangeFinished = { onSet(local) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1)
        )
    }
}

/** 界面缩放滑块行（0.85~1.30），拖动实时预览，松手才落盘。 */
@Composable
private fun ScaleSliderRow(
    title: String,
    value: Float,
    onSet: (Float) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(local * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onSet(local) },
            valueRange = 0.85f..1.30f
        )
    }
}
