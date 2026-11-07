package com.aiverse.feature.settings.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 输入框快捷栏的可开关项快照（加号/模式必留，不在此）。
 */
data class InputBarPrefs(
    val showMcp: Boolean = true,
    val showThinking: Boolean = true,
    val showFolder: Boolean = false,
    val showModel: Boolean = true,
    val showUsageCard: Boolean = true,
    val showClear: Boolean = false,
    val showWebSearch: Boolean = false,
    val showBalance: Boolean = true
)

/**
 * 输入框快捷栏的按钮显示偏好：哪些快捷功能在输入框上方显示。
 *
 * 加号（上传文件/图片）与模式切换是必留项，不在此存储；这里只存可开关项。
 * 默认全部开启（保持原布局，新增功能默认可见），用户可在设置里关闭不用的。
 *
 * 单一真源：[inputBarPrefs] StateFlow；设置页与对话页订阅同一流，改动即时同步。
 */
@Singleton
class InputBarSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("input_bar_prefs", Context.MODE_PRIVATE)

    private val _inputBarPrefs = MutableStateFlow(loadFromPrefs())

    /** 全部可开关项快照（实时）。 */
    val inputBarPrefs: StateFlow<InputBarPrefs> = _inputBarPrefs.asStateFlow()

    private fun loadFromPrefs(): InputBarPrefs = InputBarPrefs(
        showMcp = prefs.getBoolean(KEY_SHOW_MCP, true),
        showThinking = prefs.getBoolean(KEY_SHOW_THINKING, true),
        showFolder = prefs.getBoolean(KEY_SHOW_FOLDER, false),
        showModel = prefs.getBoolean(KEY_SHOW_MODEL, true),
        showUsageCard = prefs.getBoolean(KEY_SHOW_USAGE_CARD, true),
        showClear = prefs.getBoolean(KEY_SHOW_CLEAR, false),
        showWebSearch = prefs.getBoolean(KEY_SHOW_WEB_SEARCH, false),
        showBalance = prefs.getBoolean(KEY_SHOW_BALANCE, true)
    )

    /** 当前快照。 */
    fun load(): InputBarPrefs = _inputBarPrefs.value

    /** 写回全部可开关项快照（持久化 + 即时发布）。 */
    fun updateInputBarPrefs(prefs: InputBarPrefs) {
        persistPrefs(prefs)
        _inputBarPrefs.value = prefs
    }

    private fun persistPrefs(prefs: InputBarPrefs) {
        this.prefs.edit()
            .putBoolean(KEY_SHOW_MCP, prefs.showMcp)
            .putBoolean(KEY_SHOW_THINKING, prefs.showThinking)
            .putBoolean(KEY_SHOW_FOLDER, prefs.showFolder)
            .putBoolean(KEY_SHOW_MODEL, prefs.showModel)
            .putBoolean(KEY_SHOW_USAGE_CARD, prefs.showUsageCard)
            .putBoolean(KEY_SHOW_CLEAR, prefs.showClear)
            .putBoolean(KEY_SHOW_WEB_SEARCH, prefs.showWebSearch)
            .putBoolean(KEY_SHOW_BALANCE, prefs.showBalance)
            .apply()
    }

    /** MCP 快捷按钮。 */
    var showMcp: Boolean
        get() = _inputBarPrefs.value.showMcp
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showMcp = value)) }

    /** 思考强度快捷按钮。 */
    var showThinking: Boolean
        get() = _inputBarPrefs.value.showThinking
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showThinking = value)) }

    /** 切换文件夹（工作区）快捷按钮。 */
    var showFolder: Boolean
        get() = _inputBarPrefs.value.showFolder
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showFolder = value)) }

    /** 切换模型快捷按钮。 */
    var showModel: Boolean
        get() = _inputBarPrefs.value.showModel
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showModel = value)) }

    /** 发送键外圈上下文用量环（点击出明细浮层）。 */
    var showUsageCard: Boolean
        get() = _inputBarPrefs.value.showUsageCard
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showUsageCard = value)) }

    /** 清空当前对话快捷按钮。 */
    var showClear: Boolean
        get() = _inputBarPrefs.value.showClear
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showClear = value)) }

    /** 联网搜索开关快捷按钮（websearch 工具启用）。 */
    var showWebSearch: Boolean
        get() = _inputBarPrefs.value.showWebSearch
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showWebSearch = value)) }

    /** 余额胶囊快捷栏显示（独立于模型编辑页开关，只控制输入框快捷栏）。 */
    var showBalance: Boolean
        get() = _inputBarPrefs.value.showBalance
        set(value) { updateInputBarPrefs(_inputBarPrefs.value.copy(showBalance = value)) }

    /** 联网搜索工具是否启用（默认开启，保持原有 websearch 行为）。 */
    var webSearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_WEB_SEARCH_ENABLED, value).apply() }

    private companion object {
        const val KEY_SHOW_MCP = "show_mcp"
        const val KEY_SHOW_THINKING = "show_thinking"
        const val KEY_SHOW_FOLDER = "show_folder"
        const val KEY_SHOW_MODEL = "show_model"
        const val KEY_SHOW_USAGE_CARD = "show_usage_card"
        const val KEY_SHOW_CLEAR = "show_clear"
        const val KEY_SHOW_WEB_SEARCH = "show_web_search"
        const val KEY_SHOW_BALANCE = "show_balance"
        const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
    }
}
