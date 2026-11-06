package com.aiverse.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiverse.core.datastore.preferencesCorruptionHandler
import com.aiverse.feature.agent.domain.skill.SkillScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.skillInstallDataStore by preferencesDataStore(
    name = "skill_install_settings",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 技能默认安装作用域：AI 安装技能时默认装到哪。
 *
 * 默认「项目」：装到当前工作区 `<workspace>/.aiverse/skills/<名>/`，仅当前项目可用；
 * 改为「全局」后装到 `~/.aiverse/skills/<名>/`，所有项目共享。
 * 该值通过系统提示词 `{{AIVERSE_SKILL_DEFAULT_SCOPE}}` 注入，AI 安装技能时遵循。
 */
@Singleton
class SkillInstallSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val defaultInstallScopeFlow: Flow<SkillScope> = context.skillInstallDataStore.data.map { prefs ->
        parseScope(prefs[DEFAULT_INSTALL_SCOPE_KEY])
    }

    suspend fun setDefaultInstallScope(scope: SkillScope) {
        context.skillInstallDataStore.edit {
            it[DEFAULT_INSTALL_SCOPE_KEY] = scope.name
        }
    }

    companion object {
        private val DEFAULT_INSTALL_SCOPE_KEY = stringPreferencesKey("default_install_scope")

        /** 出厂默认：项目级（当前项目专用）。 */
        val DEFAULT_SCOPE: SkillScope = SkillScope.PROJECT

        /** 解析存储值；未知值（含首次无值）一律回落项目级。 */
        fun parseScope(raw: String?): SkillScope =
            if (raw == SkillScope.GLOBAL.name) SkillScope.GLOBAL else SkillScope.PROJECT
    }
}
