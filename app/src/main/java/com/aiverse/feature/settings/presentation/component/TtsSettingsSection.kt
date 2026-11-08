package com.aiverse.feature.settings.presentation.component

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiverse.R
import com.aiverse.core.theme.Spacing
import com.aiverse.core.ui.AppSwitch
import com.aiverse.core.ui.AppTextField
import com.aiverse.feature.settings.presentation.TtsSettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Volume2

@Composable
internal fun TtsSettingsSection(viewModel: TtsSettingsViewModel) {
    val context = LocalContext.current
    val rate by viewModel.rate.collectAsStateWithLifecycle()
    val pitch by viewModel.pitch.collectAsStateWithLifecycle()
    val autoRead by viewModel.autoRead.collectAsStateWithLifecycle()
    val codeBlockAnnounce by viewModel.codeBlockAnnounce.collectAsStateWithLifecycle()
    val onlineEnabled by viewModel.onlineEnabled.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val providerType by viewModel.providerType.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl)
    ) {
    SettingsGroupHeader(stringResource(R.string.settings_tts))
    SettingsGroup {
        SliderRow(
            title = stringResource(R.string.tts_rate),
            value = rate,
            valueText = "%.1fx".format(rate),
            onChange = viewModel::setRate
        )
        SettingsDivider()
        SliderRow(
            title = stringResource(R.string.tts_pitch),
            value = pitch,
            valueText = "%.1f".format(pitch),
            onChange = viewModel::setPitch
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.tts_auto_read),
            subtitle = stringResource(R.string.tts_auto_read_sub),
            trailing = { AppSwitch(checked = autoRead, onCheckedChange = viewModel::setAutoRead) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.tts_code_block_announce),
            subtitle = stringResource(R.string.tts_code_block_announce_sub),
            trailing = { AppSwitch(checked = codeBlockAnnounce, onCheckedChange = viewModel::setCodeBlockAnnounce) }
        )
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.Volume2,
            title = stringResource(R.string.tts_change_engine),
            subtitle = stringResource(R.string.tts_change_engine_sub),
            onClick = {
                runCatching {
                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                }
            }
        )
    }

    SettingsGroupHeader(stringResource(R.string.tts_online_section))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.tts_online_enabled),
            subtitle = stringResource(R.string.tts_online_enabled_sub),
            trailing = { AppSwitch(checked = onlineEnabled, onCheckedChange = viewModel::setOnlineEnabled) }
        )
        SettingsDivider()
        ProviderTypeDropdown(providerType = providerType, onChange = viewModel::setProviderType)
        SettingsDivider()
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            AppTextField(
                value = baseUrl,
                onValueChange = viewModel::setBaseUrl,
                label = stringResource(R.string.tts_base_url),
                placeholder = "https://api.openai.com/v1",
                singleLine = true
            )
            Spacer(Modifier.height(Spacing.sm))
            AppTextField(
                value = apiKey,
                onValueChange = viewModel::setApiKey,
                label = stringResource(R.string.tts_api_key),
                placeholder = "sk-...",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(Spacing.sm))
            AppTextField(
                value = model,
                onValueChange = viewModel::setModel,
                label = stringResource(R.string.tts_model),
                placeholder = "tts-1",
                singleLine = true
            )
            Spacer(Modifier.height(Spacing.sm))
            AppTextField(
                value = voice,
                onValueChange = viewModel::setVoice,
                label = stringResource(R.string.tts_voice),
                placeholder = "alloy",
                singleLine = true
            )
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTypeDropdown(providerType: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "auto" to stringResource(R.string.tts_provider_auto),
        "openai" to stringResource(R.string.tts_provider_openai),
        "fish" to stringResource(R.string.tts_provider_fish)
    )
    Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            AppTextField(
                value = options.firstOrNull { it.first == providerType }?.second ?: providerType,
                onValueChange = {},
                readOnly = true,
                label = stringResource(R.string.tts_provider_type),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (type, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onChange(type); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0.5f..2f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
        )
    }
}
