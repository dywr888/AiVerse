package com.aiverse.feature.plugins.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiverse.R
import com.aiverse.feature.plugins.data.InstalledPlugin
import com.aiverse.feature.plugins.data.PluginRepository
import com.aiverse.feature.plugins.data.remote.PluginRepoClient
import com.aiverse.feature.plugins.domain.PluginManager
import com.aiverse.feature.plugins.domain.PluginUpdateChecker
import com.aiverse.feature.plugins.domain.model.PluginManifest
import com.aiverse.feature.plugins.domain.model.PluginPermissions
import com.aiverse.feature.plugins.domain.model.PluginRepoEntry
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
    private val repoClient: PluginRepoClient,
    private val updateChecker: PluginUpdateChecker,
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

    /** 仓库拉取状态。 */
    private val _repoEntries = MutableStateFlow<List<PluginRepoEntry>>(emptyList())
    val repoEntries: StateFlow<List<PluginRepoEntry>> = _repoEntries.asStateFlow()

    private val _repoLoading = MutableStateFlow(false)
    val repoLoading: StateFlow<Boolean> = _repoLoading.asStateFlow()

    private val _repoError = MutableStateFlow<String?>(null)
    val repoError: StateFlow<String?> = _repoError.asStateFlow()

    /** 正在安装/更新的仓库插件名集合。 */
    private val _repoInstalling = MutableStateFlow<Set<String>>(emptySet())
    val repoInstalling: StateFlow<Set<String>> = _repoInstalling.asStateFlow()

    /** 仓库条目 → 本地是否已安装（用于「安装/更新」按钮切换）。 */
    private val _repoInstalled = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val repoInstalled: StateFlow<Map<String, Boolean>> = _repoInstalled.asStateFlow()

    /** 可更新插件集合（本地插件 id → 仓库条目），由 [updateChecker] 维护。 */
    val updates: StateFlow<Map<String, PluginRepoEntry>> = updateChecker.updates

    /** 待授权通知权限后重装的仓库条目（安装前权限检查，未授权先申请）。 */
    private val _pendingNotifyInstall = MutableStateFlow<PluginRepoEntry?>(null)
    val pendingNotifyInstall: StateFlow<PluginRepoEntry?> = _pendingNotifyInstall.asStateFlow()

    init {
        refresh()
        refreshUpdates()
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
                if (manifest.permissions.contains(PluginPermissions.ALL_FILES) && !hasAllFilesAccess()) {
                    // 需要所有文件访问权限：先引导开启，未开启不自动启用
                    requestAllFilesAccess()
                    refresh()
                    _message.value = context.getString(R.string.plugins_need_all_files_access, manifest.name)
                } else if (manifest.permissions.contains(PluginPermissions.NOTIFY) && !hasNotifyPermission()) {
                    // 通知权限：未授权则提示去开启，不自动启用
                    refresh()
                    _message.value = context.getString(R.string.plugins_need_notify_permission, manifest.name)
                } else if (!dangerous) {
                    manager.enable(manifest)
                    refresh()
                    _message.value = context.getString(R.string.plugins_installed_enabled, manifest.name)
                } else {
                    refresh()
                    _message.value = context.getString(R.string.plugins_installed_dangerous, manifest.name)
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

    /** 触发一次仓库更新检查（拉 index 比较本地版本），失败静默。 */
    fun refreshUpdates() {
        viewModelScope.launch { updateChecker.check() }
    }

    /** 拉取插件仓库列表。 */
    fun loadRepo() {
        viewModelScope.launch(Dispatchers.IO) {
            _repoLoading.value = true
            try {
                _repoEntries.value = repoClient.fetchIndex()
                _repoError.value = null
                _repoInstalled.value = _repoEntries.value.associate { e -> e.name to isInstalled(e) }
            } catch (e: Exception) {
                _repoError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _repoLoading.value = false
            }
        }
    }

    /** 从仓库安装或更新插件：下载 zip → 安装解压 → 保持/设置启用 → 刷新。 */
    fun installFromRepo(entry: PluginRepoEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            // 需要所有文件访问权限：未开启则先引导申请，不开不安装
            if (entry.permission.contains(PluginPermissions.ALL_FILES) && !hasAllFilesAccess()) {
                requestAllFilesAccess()
                _error.value = context.getString(R.string.plugins_need_all_files_access, entry.name)
                return@launch
            }
            if (entry.permission.contains(PluginPermissions.NOTIFY) && !hasNotifyPermission()) {
                _pendingNotifyInstall.value = entry
                _error.value = context.getString(R.string.plugins_need_notify_permission, entry.name)
                return@launch
            }
            _repoInstalling.value = _repoInstalling.value + entry.name
            try {
                val wasInstalled = isInstalled(entry)
                val zip = repoClient.downloadZip(entry, context.cacheDir)
                val manifest = try {
                    manager.install(zip)
                } finally {
                    zip.delete()
                }
                // 更新已启用插件：重载运行时（旧 runtime 持旧 JS 与 handler）。
                if (repository.enabledIds().contains(manifest.id)) {
                    manager.disable(manifest.id)
                    manager.enable(manifest)
                } else if (manifest.permissions.none { it in PluginPermissions.DANGEROUS }) {
                    manager.enable(manifest)
                }
                refresh()
                refreshUpdates()
                _repoInstalled.value = _repoInstalled.value + (entry.name to true)
                val dangerous = manifest.permissions.any { it in PluginPermissions.DANGEROUS }
                _message.value = when {
                    wasInstalled -> context.getString(R.string.plugins_updated, manifest.name)
                    dangerous -> context.getString(R.string.plugins_installed_dangerous, manifest.name)
                    else -> context.getString(R.string.plugins_installed_enabled, manifest.name)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _repoInstalling.value = _repoInstalling.value - entry.name
            }
        }
    }

    /** 仓库条目是否已在本地安装。 */
    private fun isInstalled(entry: PluginRepoEntry): Boolean =
        repository.listInstalled().any { entry.matchesLocal(it.manifest) }

    private fun PluginRepoEntry.matchesLocal(m: PluginManifest): Boolean =
        if (packageName.isNotBlank()) packageName == m.packageName else name == m.name

    /** Android 11+ 是否已开启「所有文件访问」；低版本视为已具备。 */
    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Android 13+ 是否已授予通知权限；低版本视为已具备。 */
    private fun hasNotifyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 用户授权通知权限后重试安装待装的仓库条目。 */
    fun notifyPermissionGranted() {
        val entry = _pendingNotifyInstall.value
        _pendingNotifyInstall.value = null
        if (entry != null) installFromRepo(entry)
    }

    fun clearPendingNotifyInstall() {
        _pendingNotifyInstall.value = null
    }

    /** 跳系统设置开启「所有文件访问」权限。 */
    private fun requestAllFilesAccess() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else null
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            intent?.let { context.startActivity(it) }
        } catch (e: Exception) {
            _error.value = e.message ?: e.javaClass.simpleName
        }
    }

    fun consumeError() {
        _error.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
