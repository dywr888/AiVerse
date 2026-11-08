package com.aiverse.feature.settings.data.local

import android.content.Context
import com.aiverse.core.util.FileLogger
import com.aiverse.feature.settings.domain.model.ModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义模型元数据的本地持久存储（存于应用文件目录，App 更新/系统清理缓存都不丢失）。
 * key 为「提供商ID:模型名」复合键；优先级高于拉取/内置元数据。
 */
@Singleton
class CustomModelMetadataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun all(): Map<String, ModelMetadata> = mutex.withLock {
        withContext(Dispatchers.IO) { read() }
    }

    suspend fun get(providerId: String, model: String): ModelMetadata? =
        all()[key(providerId, model)]

    suspend fun put(providerId: String, model: String, metadata: ModelMetadata) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            map[key(providerId, model)] = metadata
            write(map)
        }
    }

    suspend fun remove(providerId: String, model: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            if (map.remove(key(providerId, model)) != null) {
                write(map)
            }
        }
    }

    private fun key(providerId: String, model: String): String = "$providerId:$model"

    private fun read(): Map<String, ModelMetadata> {
        val f = file()
        if (f.isFile) {
            return runCatching {
                json.decodeFromString<Map<String, ModelMetadata>>(f.readText(Charsets.UTF_8))
            }.getOrElse {
                FileLogger.w(TAG, "读取自定义模型元数据失败", it)
                emptyMap()
            }
        }
        // 迁移旧版缓存目录数据：曾存于 cacheDir（可被系统清理，导致用户自定义参数丢失），
        // 首次读到旧文件时搬进 filesDir 并删除缓存副本。
        val legacy = File(context.cacheDir, FILE_NAME)
        if (legacy.isFile) {
            val data = runCatching {
                json.decodeFromString<Map<String, ModelMetadata>>(legacy.readText(Charsets.UTF_8))
            }.getOrNull()
            if (data != null && data.isNotEmpty()) {
                write(data)
                legacy.delete()
                return data
            }
        }
        return emptyMap()
    }

    private fun write(map: Map<String, ModelMetadata>) {
        runCatching {
            file().writeText(json.encodeToString(map), Charsets.UTF_8)
        }.onFailure {
            FileLogger.w(TAG, "写入自定义模型元数据失败", it)
        }
    }

    private fun file(): File = File(context.filesDir, FILE_NAME)

    private companion object {
        const val TAG = "CustomModelMetadataStore"
        const val FILE_NAME = "custom-model-metadata.json"
    }
}