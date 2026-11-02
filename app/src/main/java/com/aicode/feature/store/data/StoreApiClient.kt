package com.aicode.feature.store.data

import com.aicode.core.util.FileLogger
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 在线商店的网络客户端：claudemarket.ai（技能）+ 官方 MCP Registry（MCP 服务器）。
 * 均无鉴权、无需 Key；返回 DTO 由调用方映射为统一的 [StoreItem]。
 *
 * 基于共享 OkHttpClient 克隆出专用实例（共享连接池）：全局 client 的 readTimeout=0 是为
 * 流式 SSE 设计的，商店请求必须带明确超时，避免站点半死时界面永久转圈。
 */
@Singleton
class StoreApiClient @Inject constructor(
    baseClient: OkHttpClient
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val TAG = "StoreApiClient"
        const val CM_BASE = "https://claudemarket.ai"
        const val MCP_BASE = "https://registry.modelcontextprotocol.io"
        const val USER_AGENT = "AiCodeR-Android"
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 500L
        val HTTP_CODE_REGEX = Regex("^HTTP (\\d{3})$")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** claudemarket 技能列表（分页）。[baseUrl] 为空时用内置源。 */
    suspend fun listSkills(page: Int, limit: Int = 20, baseUrl: String? = null): Result<CmSkillsResponse> =
        getJson("${baseUrl ?: CM_BASE}/api/skills?page=$page&limit=$limit")

    /** claudemarket 技能详情。 */
    suspend fun skillDetail(slug: String, baseUrl: String? = null): Result<CmSkillDetail> =
        getJson("${baseUrl ?: CM_BASE}/api/skills/${urlEncode(slug)}")

    /** claudemarket 全库搜索（含技能与 MCP；调用方按 type 过滤）。 */
    suspend fun search(query: String, limit: Int = 30, baseUrl: String? = null): Result<CmSearchResponse> =
        getJson("${baseUrl ?: CM_BASE}/api/search?q=${urlEncode(query)}&limit=$limit")

    /** 官方 MCP Registry 服务器列表（cursor 分页）。 */
    suspend fun listMcpServers(
        cursor: String? = null,
        limit: Int = 50,
        baseUrl: String? = null,
        search: String? = null,
    ): Result<McpRegistryResponse> {
        val cursorPart = cursor?.takeIf { it.isNotBlank() }?.let { "&cursor=${urlEncode(it)}" }.orEmpty()
        val searchPart = search?.takeIf { it.isNotBlank() }?.let { "&search=${urlEncode(it)}" }.orEmpty()
        return getJson("${baseUrl ?: MCP_BASE}/v0/servers?limit=$limit$cursorPart$searchPart")
    }

    /** 拉取任意 URL 的文本内容（GitHub raw 文件下载等，带体积上限）。 */
    suspend fun fetchText(url: String, maxBytes: Int = 1 shl 20): Result<String> = withContext(Dispatchers.IO) {
        withRetry {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty body")
                    val bytes = body.bytes()
                    if (bytes.size > maxBytes) error("file too large: ${bytes.size}")
                    bytes.decodeToString()
                }
            }
        }.onFailure { FileLogger.w(TAG, "拉取文本失败: $url", it) }
    }

    /** 拉取任意 URL 的字节内容（GitHub 文件下载等，带体积上限）。 */
    suspend fun fetchBytes(url: String, maxBytes: Int = 2 shl 20): Result<ByteArray> = withContext(Dispatchers.IO) {
        withRetry {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty body")
                    val bytes = body.bytes()
                    if (bytes.size > maxBytes) error("file too large: ${bytes.size}")
                    bytes
                }
            }
        }.onFailure { FileLogger.w(TAG, "拉取字节失败: $url", it) }
    }

    private suspend inline fun <reified T> getJson(url: String): Result<T> = withContext(Dispatchers.IO) {
        withRetry {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty body")
                    json.decodeFromString<T>(body.string())
                }
            }
        }.onFailure { FileLogger.w(TAG, "商店请求失败: $url", it) }
    }

    /**
     * 瞬时错误自动重试：GitHub / gh-proxy 等上游存在间歇性连接失败（连接 reset 等），
     * 首次请求失败后重试即可恢复。最多 [MAX_ATTEMPTS] 次尝试，失败按 [RETRY_BACKOFF_MS]
     * 指数退避（500ms、1000ms）；仅 IOException 与 HTTP 5xx/429 重试，404 等其它错误立即返回。
     */
    private suspend inline fun <T> withRetry(block: () -> Result<T>): Result<T> {
        var attempt = 1
        while (true) {
            val result = block()
            val error = result.exceptionOrNull()
            if (error == null || attempt >= MAX_ATTEMPTS || !isTransientError(error)) return result
            val backoffMs = RETRY_BACKOFF_MS shl (attempt - 1)
            FileLogger.w(TAG, "瞬时错误，${backoffMs}ms 后重试（第 ${attempt + 1}/$MAX_ATTEMPTS 次）: $error")
            delay(backoffMs)
            attempt++
        }
    }

    /** IOException（连接失败、超时、reset）与 HTTP 5xx/429 视为瞬时错误。 */
    private fun isTransientError(error: Throwable): Boolean {
        if (error is IOException) return true
        val code = HTTP_CODE_REGEX.find(error.message ?: return false)?.groupValues?.get(1)?.toIntOrNull()
            ?: return false
        return code >= 500 || code == 429
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
