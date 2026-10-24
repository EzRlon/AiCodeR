package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.CleanupRecommendationCache
import com.aicode.feature.settings.domain.service.StorageCleanupScanWorker
import kotlinx.serialization.json.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.storageCleanupDataStore by preferencesDataStore(name = "storage_cleanup_prefs")

/**
 * 推荐池缓存仓库：每周低频扫描的结果落盘，打开存储页先展示上次结论（与商店「秒出缓存」同思路）。
 * 只存数据，不做任何删除。
 */
@Singleton
class StorageCleanupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    val cacheFlow: Flow<CleanupRecommendationCache> = context.storageCleanupDataStore.data.map { prefs ->
        val json = prefs[KEY_RECOMMENDATIONS] ?: return@map CleanupRecommendationCache()
        // kotlinx.serialization（勿改回 Gson）：Gson+R8 丢字段泛型致 LinkedTreeMap 强转崩溃（vCode117 真机）。
        // 键已换 V2：旧 Gson 格式缓存直接作废重扫。
        runCatching { this@StorageCleanupRepository.json.decodeFromString(CleanupRecommendationCache.serializer(), json) }
            .onFailure { FileLogger.w(TAG, "推荐池缓存解析失败，按空处理", it) }
            .getOrDefault(CleanupRecommendationCache())
    }

    suspend fun save(cache: CleanupRecommendationCache) {
        context.storageCleanupDataStore.edit { prefs ->
            prefs[KEY_RECOMMENDATIONS] = json.encodeToString(CleanupRecommendationCache.serializer(), cache)
            prefs[KEY_SCANNED_AT] = cache.scannedAt
        }
    }

    suspend fun lastScannedAt(): Long = context.storageCleanupDataStore.data.first()[KEY_SCANNED_AT] ?: 0L

    /**
     * 确保每周推荐扫描已注册（存储页首次打开时调用，不在 Application 里加）。
     * 用 KEEP：已注册则不重排，避免每次打开页面都重置下次执行时间。
     * 只更新推荐池缓存数据，Worker 内绝不删除任何文件；无网络约束、低电量不跑。
     */
    fun ensureWeeklyScanScheduled() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<StorageCleanupScanWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }.onFailure { FileLogger.w(TAG, "注册每周推荐扫描失败", it) }
    }

    companion object {
        private const val TAG = "StorageCleanupRepository"

        /** 唯一周期任务名；运行健康页用它查询 Worker 状态。 */
        const val UNIQUE_WORK_NAME = "storage-cleanup-weekly-scan"
        val KEY_RECOMMENDATIONS = stringPreferencesKey("recommendations_json_v2")
        val KEY_SCANNED_AT = longPreferencesKey("scanned_at")
    }
}
