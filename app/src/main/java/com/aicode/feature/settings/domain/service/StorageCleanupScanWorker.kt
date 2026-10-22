package com.aicode.feature.settings.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.StorageCleanupRepository
import com.aicode.feature.settings.domain.model.CleanupRecommendationCache
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 每周低频推荐扫描：只重算推荐池并落盘缓存，**绝不删除任何文件**。
 * 结果由存储页直接展示；用户勾选后才由 StorageCleanupAdvisor.clean 执行删除。
 */
@HiltWorker
class StorageCleanupScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val advisor: StorageCleanupAdvisor,
    private val repository: StorageCleanupRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val items = runCatching { advisor.scan() }
            .onFailure { FileLogger.w(TAG, "每周推荐扫描失败", it) }
            .getOrElse { return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure() }
        repository.save(CleanupRecommendationCache(items = items, scannedAt = System.currentTimeMillis()))
        return Result.success()
    }

    private companion object {
        private const val TAG = "StorageCleanupScanWorker"
        private const val MAX_RETRIES = 3
    }
}
