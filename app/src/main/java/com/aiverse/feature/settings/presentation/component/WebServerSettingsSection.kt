package com.aiverse.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiverse.core.theme.Spacing
import com.aiverse.core.ui.AppSwitch
import com.aiverse.core.ui.AppTextField
import com.aiverse.feature.web.WebServerViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Play
import compose.icons.feathericons.Square
import com.aiverse.R

/**
 * 网页服务器设置页：端口（默认 7676）、仅本机访问、身份验证（默认关）、访问密码；
 * 运行中展示可复制的访问地址与说明；右下角启动/停止。
 * 照 RikkaHub 的 SettingWebPage 交互，但服务器内嵌本进程（Ktor），无需前台服务与通知/本地网络权限。
 */
@Composable
internal fun WebServerSettingsSection(viewModel: WebServerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val serverState = state.server
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val copiedTemplate = stringResource(R.string.common_copied_with_title)
    val running = serverState.isRunning

    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }
    var passwordText by remember(state.password) { mutableStateOf(state.password) }
    var passwordVisible by remember { mutableStateOf(false) }

    fun copyUrl(url: String) {
        clipboardManager.setText(AnnotatedString(url))
        Toast.makeText(context, copiedTemplate.replace("%1\$s", url), Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_web_server_port),
                    subtitle = stringResource(R.string.settings_web_server_port_desc),
                    trailing = {
                        AppTextField(
                            value = portText,
                            onValueChange = { value ->
                                portText = value.filter { it.isDigit() }
                                val port = portText.toIntOrNull()
                                if (port != null && port in 1024..65535) {
                                    viewModel.setPort(port)
                                }
                            },
                            singleLine = true,
                            isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                            enabled = !running,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(110.dp)
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_web_server_localhost_only),
                    subtitle = stringResource(R.string.settings_web_server_localhost_only_desc),
                    trailing = {
                        AppSwitch(
                            checked = state.localhostOnly,
                            onCheckedChange = viewModel::setLocalhostOnly,
                            enabled = !running
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_web_server_jwt_enable),
                    subtitle = stringResource(R.string.settings_web_server_jwt_enable_desc),
                    trailing = {
                        AppSwitch(
                            checked = state.jwtEnabled,
                            onCheckedChange = viewModel::setJwtEnabled,
                            // 无密码时不可开启；已开启时允许关闭（需先有密码才能关闭）
                            enabled = state.jwtEnabled || passwordText.isNotBlank()
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_web_server_password),
                    subtitle = stringResource(R.string.settings_web_server_password_desc),
                    trailing = {
                        AppTextField(
                            value = passwordText,
                            onValueChange = {
                                passwordText = it
                                viewModel.setPassword(it)
                            },
                            singleLine = true,
                            isError = state.jwtEnabled && passwordText.isBlank(),
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.width(180.dp)
                        )
                    }
                )
            }

            if (running) {
                SettingsGroup {
                    if (!serverState.localhostOnly) {
                        serverState.lanAddress?.let { lanUrl ->
                            SettingsRow(
                                title = stringResource(R.string.settings_web_server_lan_address),
                                subtitle = lanUrl,
                                onClick = { copyUrl(lanUrl) }
                            )
                            SettingsDivider()
                        }
                    }
                    SettingsRow(
                        title = stringResource(R.string.settings_web_server_local_address),
                        subtitle = serverState.localAddress,
                        onClick = { copyUrl(serverState.localAddress) }
                    )
                }
            }

            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_web_server_address_note),
                    subtitle = stringResource(R.string.settings_web_server_address_note_desc)
                )
            }

            serverState.error?.let { error ->
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.settings_web_server_error),
                        subtitle = error
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = {
                if (serverState.isLoading) return@ExtendedFloatingActionButton
                if (running) {
                    viewModel.stop()
                } else {
                    viewModel.start()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.lg),
            containerColor = if (running) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            icon = {
                if (serverState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (running) FeatherIcons.Square else FeatherIcons.Play,
                        contentDescription = null
                    )
                }
            },
            text = {
                Text(
                    text = stringResource(
                        if (running) R.string.settings_web_server_stop
                        else R.string.settings_web_server_start
                    )
                )
            }
        )
    }
}
