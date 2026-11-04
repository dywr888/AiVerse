package com.aiverse.feature.web.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiverse.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.webServerDataStore by preferencesDataStore(
    name = "web_server_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 网页端服务器设置：监听端口（默认 7676）、仅本机访问（默认关）、身份验证（默认关）、访问密码。
 * 与 [com.aiverse.feature.settings.data.repository.GeneralSettingsRepository] 同款 DataStore 用法。
 */
@Singleton
class WebServerSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val DEFAULT_PORT = 7676
        private val PORT_KEY = intPreferencesKey("web_server_port")
        private val LOCALHOST_ONLY_KEY = booleanPreferencesKey("web_server_localhost_only")
        private val JWT_ENABLED_KEY = booleanPreferencesKey("web_server_jwt_enabled")
        private val ACCESS_PASSWORD_KEY = stringPreferencesKey("web_server_access_password")
    }

    val port: Flow<Int> = context.webServerDataStore.data.map { it[PORT_KEY] ?: DEFAULT_PORT }
    val localhostOnly: Flow<Boolean> = context.webServerDataStore.data.map { it[LOCALHOST_ONLY_KEY] ?: false }
    val jwtEnabled: Flow<Boolean> = context.webServerDataStore.data.map { it[JWT_ENABLED_KEY] ?: false }
    val accessPassword: Flow<String> = context.webServerDataStore.data.map { it[ACCESS_PASSWORD_KEY] ?: "" }

    suspend fun currentPort(): Int = port.first()
    suspend fun currentLocalhostOnly(): Boolean = localhostOnly.first()
    suspend fun currentJwtEnabled(): Boolean = jwtEnabled.first()
    suspend fun currentAccessPassword(): String = accessPassword.first()

    suspend fun setPort(value: Int) {
        context.webServerDataStore.edit { it[PORT_KEY] = value }
    }

    suspend fun setLocalhostOnly(value: Boolean) {
        context.webServerDataStore.edit { it[LOCALHOST_ONLY_KEY] = value }
    }

    suspend fun setJwtEnabled(value: Boolean) {
        context.webServerDataStore.edit { it[JWT_ENABLED_KEY] = value }
    }

    suspend fun setAccessPassword(value: String) {
        context.webServerDataStore.edit { it[ACCESS_PASSWORD_KEY] = value }
    }
}
