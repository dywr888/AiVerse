package com.aiverse.feature.agent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiverse.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class FavoriteItem(
    val id: String,
    val content: String,
    val sessionTitle: String = "",
    val createdAt: Long
)

private val Context.favoriteDataStore by preferencesDataStore(
    name = "favorites",
    corruptionHandler = preferencesCorruptionHandler
)

/** 收藏的消息内容，持久化到 DataStore（JSON 列表）。 */
@Singleton
class FavoriteRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val KEY = stringPreferencesKey("favorites_json")

    private fun parse(raw: String?): List<FavoriteItem> =
        raw?.let {
            runCatching { json.decodeFromString(ListSerializer(FavoriteItem.serializer()), it) }.getOrNull()
        } ?: emptyList()

    val favoritesFlow: Flow<List<FavoriteItem>> = context.favoriteDataStore.data.map { parse(it[KEY]) }

    suspend fun add(content: String, sessionTitle: String) {
        context.favoriteDataStore.edit { prefs ->
            val list = parse(prefs[KEY])
            prefs[KEY] = json.encodeToString(
                ListSerializer(FavoriteItem.serializer()),
                list + FavoriteItem(UUID.randomUUID().toString(), content, sessionTitle, System.currentTimeMillis())
            )
        }
    }

    suspend fun delete(id: String) {
        context.favoriteDataStore.edit { prefs ->
            val list = parse(prefs[KEY])
            prefs[KEY] = json.encodeToString(
                ListSerializer(FavoriteItem.serializer()),
                list.filterNot { it.id == id }
            )
        }
    }

    /** 更新收藏内容（详情页编辑用）。 */
    suspend fun update(id: String, content: String) {
        context.favoriteDataStore.edit { prefs ->
            val list = parse(prefs[KEY])
            prefs[KEY] = json.encodeToString(
                ListSerializer(FavoriteItem.serializer()),
                list.map { if (it.id == id) it.copy(content = content) else it }
            )
        }
    }

    /** 一次性读取当前全部收藏（备份导出用）。 */
    suspend fun getAllOnce(): List<FavoriteItem> = favoritesFlow.first()

    /** 合并导入：按 id 去重，备份条目覆盖同 id 的现有条目（备份导入用）。 */
    suspend fun mergeAll(items: List<FavoriteItem>) {
        if (items.isEmpty()) return
        context.favoriteDataStore.edit { prefs ->
            val merged = LinkedHashMap<String, FavoriteItem>()
            parse(prefs[KEY]).forEach { merged[it.id] = it }
            items.forEach { merged[it.id] = it }
            prefs[KEY] = json.encodeToString(
                ListSerializer(FavoriteItem.serializer()),
                merged.values.toList()
            )
        }
    }
}
