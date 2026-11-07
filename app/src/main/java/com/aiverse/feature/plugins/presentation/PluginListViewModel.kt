package com.aiverse.feature.plugins.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiverse.R
import com.aiverse.feature.plugins.data.InstalledPlugin
import com.aiverse.feature.plugins.data.PluginRepository
import com.aiverse.feature.plugins.domain.PluginManager
import com.aiverse.feature.plugins.domain.model.PluginPermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class PluginListViewModel @Inject constructor(
    private val repository: PluginRepository,
    private val manager: PluginManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _plugins.value = repository.listInstalled()
    }

    /** 从 SAF 选择 zip 安装。无危险权限则自动启用；有危险权限等用户看权限后手动启用。 */
    fun install(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _installing.value = true
            try {
                val tmp = File(context.cacheDir, "plugin_install_${System.currentTimeMillis()}.zip")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(context.getString(R.string.plugins_error_read_zip))
                input.use { ins -> FileOutputStream(tmp).use { ins.copyTo(it) } }
                val manifest = manager.install(tmp)
                tmp.delete()
                val dangerous = manifest.permissions.any { it in PluginPermissions.DANGEROUS }
                if (!dangerous) manager.enable(manifest)
                refresh()
                _message.value = if (dangerous) {
                    context.getString(R.string.plugins_installed_dangerous, manifest.name)
                } else {
                    context.getString(R.string.plugins_installed_enabled, manifest.name)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _installing.value = false
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (enabled) {
                    val m = repository.getManifest(id) ?: throw IllegalStateException(context.getString(R.string.plugins_error_not_found))
                    manager.enable(m)
                } else {
                    manager.disable(id)
                }
                refresh()
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.uninstall(id)
            refresh()
            _message.value = context.getString(R.string.plugins_uninstalled)
        }
    }

    fun consumeError() {
        _error.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
