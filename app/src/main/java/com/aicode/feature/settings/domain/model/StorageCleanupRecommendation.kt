package com.aicode.feature.settings.domain.model

import androidx.annotation.StringRes
import com.aicode.R
import kotlinx.serialization.Serializable

/** 推荐置信级：推荐级默认勾选（一键即清），需确认级默认不勾（逐条看过才删）。 */
enum class CleanupRecommendationLevel { RECOMMENDED, NEEDS_CONFIRM }

/** 推荐项的删除路由：UI 层只勾选/展示，执行一律走 [com.aicode.feature.settings.domain.service.StorageCleanupAdvisor.clean]。 */
enum class CleanupRecommendationKind {
    APP_CACHE, APP_LOGS, CONTAINER_CACHES,
    STALE_TOOL_OUTPUT, STALE_GENERATED_IMAGES,
    ORPHAN_CHECKPOINT, ORPHAN_AI_LOG, ORPHAN_IMAGE
}

/** 「为什么是垃圾」的人类可读原因，逐条展示在明细行。 */
enum class CleanupRecommendationReason(@param:StringRes val labelRes: Int) {
    REBUILDABLE(R.string.storage_rec_reason_rebuildable),
    UNUSED_30_DAYS(R.string.storage_rec_reason_unused),
    NO_SESSION(R.string.storage_rec_reason_no_session),
    NO_CONTAINER(R.string.storage_rec_reason_no_container)
}

/**
 * 一条清理推荐。持久化进 DataStore 缓存，打开存储页先展示上次结论。
 * kotlinx.serialization（勿改回 Gson）：Gson + R8 丢字段泛型签名会把嵌套 List 反序列化成
 * LinkedTreeMap，消费侧强转 ClassCastException（vCode117 真机崩溃，2026-10-24）。
 *
 * 聚合型条目（APP_CACHE / APP_LOGS / CONTAINER_CACHES / STALE_*）不在实例里携带具体路径，
 * 执行时按 kind 现算现删，避免缓存过期后误删新数据；孤儿型逐条对应一个磁盘对象。
 */
@Serializable
data class CleanupRecommendation(
    val id: String,
    val kind: CleanupRecommendationKind,
    val level: CleanupRecommendationLevel,
    val label: String,
    val bytes: Long,
    val reason: CleanupRecommendationReason,
    /** 孤儿型条目的磁盘定位：目录名/文件名/会话 id；聚合型为空。 */
    val detail: String = ""
)

/** 每周低频扫描落盘的推荐池缓存；items 为空且 scannedAt 非空表示「扫描过、确实没有推荐」。 */
@Serializable
data class CleanupRecommendationCache(
    val items: List<CleanupRecommendation> = emptyList(),
    val scannedAt: Long = 0L
)
