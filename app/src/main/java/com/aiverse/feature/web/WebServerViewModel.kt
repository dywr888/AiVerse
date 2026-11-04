package com.aiverse.feature.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiverse.feature.web.data.WebServerSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 网页服务器设置页 UI 状态。 */
data class WebServerUiState(
    val port: Int = WebServerSettingsRepository.DEFAULT_PORT,
    val localhostOnly: Boolean = false,
    val jwtEnabled: Boolean = false,
    val password: String = "",
    val server: WebServerState = WebServerState()
)

@HiltViewModel
class WebServerViewModel @Inject constructor(
    private val manager: WebServerManager,
    private val settingsRepository: WebServerSettingsRepository
) : ViewModel() {

    val uiState: StateFlow<WebServerUiState> = combine(
        settingsRepository.port,
        settingsRepository.localhostOnly,
        settingsRepository.jwtEnabled,
        settingsRepository.accessPassword,
        manager.state
    ) { port, localhostOnly, jwtEnabled, password, server ->
        WebServerUiState(port, localhostOnly, jwtEnabled, password, server)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WebServerUiState()
    )

    fun start() {
        viewModelScope.launch { manager.start() }
    }

    fun stop() {
        manager.stop()
    }

    fun setPort(value: Int) {
        viewModelScope.launch { settingsRepository.setPort(value) }
    }

    fun setLocalhostOnly(value: Boolean) {
        viewModelScope.launch { settingsRepository.setLocalhostOnly(value) }
    }

    fun setJwtEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setJwtEnabled(value) }
    }

    fun setPassword(value: String) {
        viewModelScope.launch {
            settingsRepository.setAccessPassword(value)
            // 密码清空时同步关闭身份验证，避免开启状态却无密码可用。
            if (value.isBlank()) settingsRepository.setJwtEnabled(false)
        }
    }
}
