package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.core.net.RepoDataFetcher
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.feedback.FeedbackPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 反馈自动提交：POST 到项目反馈收集页（无需用户登录/GitHub）。
 *
 * 端点从仓库 `data` 分支的 `data/feedback-endpoint.json` 动态读取（可随仓库更新切换），
 * 内置 asset 作为离线兜底。端点为空或网络失败时写入 [FeedbackOutbox]，下次启动/提交自动重试。
 * 服务端可用 `tools/feedback-relay` 的 Worker 部署，由它持有 GitHub 令牌建 Issue。
 */
@Singleton
class FeedbackSubmitter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outbox: FeedbackOutbox
) {
    private companion object {
        const val TAG = "FeedbackSubmitter"
        const val ENDPOINT_PATH = "data/feedback-endpoint.json"
        const val ASSET_ENDPOINT = "feedback-endpoint.json"
    }

    enum class Result { SUBMITTED, QUEUED }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val fetcher = RepoDataFetcher(
        context = context,
        owner = "EzRlon",
        repo = "AiCodeR",
        branch = "data"
    )

    private data class Endpoint(val url: String, val key: String?)

    @Volatile
    private var cachedEndpoint: Endpoint? = null

    suspend fun submit(payload: FeedbackPayload): Result {
        val endpoint = resolveEndpoint()
        if (endpoint == null) {
            outbox.enqueue(payload)
            return Result.QUEUED
        }
        val ok = post(endpoint, payload)
        if (!ok) outbox.enqueue(payload)
        return if (ok) Result.SUBMITTED else Result.QUEUED
    }

    /** 启动/下次提交前重试本地队列；成功一条删一条，失败即停，避免无网时反复打端点。 */
    suspend fun flushOutbox() {
        for (file in outbox.pending()) {
            val payload = outbox.read(file) ?: run {
                outbox.delete(file)
                continue
            }
            val endpoint = resolveEndpoint() ?: return
            if (!post(endpoint, payload)) return
            outbox.delete(file)
        }
    }

    private suspend fun post(endpoint: Endpoint, payload: FeedbackPayload): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder()
                    .url(endpoint.url)
                    .header("User-Agent", "aicode-android-feedback")
                endpoint.key?.takeIf { it.isNotBlank() }?.let {
                    builder.header("X-Feedback-Key", it)
                }
                val request = builder
                    .post(
                        json.encodeToString(payload)
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()
                client.newCall(request).execute().use { response ->
                    FileLogger.i(TAG, "反馈提交结果 code=${response.code}")
                    response.isSuccessful
                }
            }.onFailure { FileLogger.w(TAG, "反馈提交失败", it) }.getOrDefault(false)
        }

    private suspend fun resolveEndpoint(): Endpoint? {
        cachedEndpoint?.let { return it }
        val remote = runCatching {
            when (val result = fetcher.fetch(ENDPOINT_PATH, maxAgeMs = 24 * 60 * 60 * 1000L)) {
                is RepoDataFetcher.FetchResult.Success -> result.content
                is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
                is RepoDataFetcher.FetchResult.Failure -> null
            }
        }.getOrNull()
        val asset = runCatching {
            context.assets.open(ASSET_ENDPOINT).bufferedReader().use { it.readText() }
        }.getOrNull()
        val endpoint = listOf(remote, asset)
            .asSequence()
            .filterNotNull()
            .mapNotNull { parseEndpoint(it) }
            .firstOrNull()
        cachedEndpoint = endpoint
        return endpoint
    }

    private fun parseEndpoint(text: String): Endpoint? = runCatching {
        val obj = JSONObject(text)
        val url = obj.optString("endpoint").trim()
        if (url.isBlank()) return@runCatching null
        Endpoint(url, obj.optString("key").trim().takeIf { it.isNotBlank() })
    }.getOrNull()
}
