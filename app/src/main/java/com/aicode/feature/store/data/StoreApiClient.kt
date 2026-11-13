package com.aicode.feature.store.data

import com.aicode.core.util.FileLogger
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
import okhttp3.Response

/**
 * 在线商店的网络客户端：claudemarket.ai（技能）+ 官方 MCP Registry（MCP 服务器）。
 * 均无鉴权、无需 Key；返回 DTO 由调用方映射为统一的 [StoreItem]。
 *
 * 基于共享 OkHttpClient 克隆出专用实例（共享连接池）：全局 client 的 readTimeout=0 是为
 * 流式 SSE 设计的，商店请求必须带明确超时，避免站点半死时界面永久转圈。
 */
@Singleton
class StoreApiClient @Inject constructor(
    baseClient: OkHttpClient,
    private val challengeSolver: StoreChallengeSolver
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        // 与 WebView 共享 Cookie：人机校验通过后 OkHttp 后续请求直接复用校验态。
        .cookieJar(WebViewCookieJar())
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val TAG = "StoreApiClient"
        const val CM_BASE = "https://claudemarket.ai"
        const val MCP_BASE = "https://registry.modelcontextprotocol.io"
        const val USER_AGENT = "AiCodeR-Android"
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
                    resp.throwIfUnsuccessful()
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
                    resp.throwIfUnsuccessful()
                    val body = resp.body ?: error("empty body")
                    val bytes = body.bytes()
                    if (bytes.size > maxBytes) error("file too large: ${bytes.size}")
                    bytes
                }
            }
        }.onFailure { FileLogger.w(TAG, "拉取字节失败: $url", it) }
    }

    private suspend inline fun <reified T> getJson(url: String): Result<T> = withContext(Dispatchers.IO) {
        val direct = withRetry {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { resp ->
                    resp.throwIfUnsuccessful()
                    val body = resp.body ?: error("empty body")
                    json.decodeFromString<T>(body.string())
                }
            }
        }
        // Vercel 人机校验只有浏览器内核能通过：改走 WebView 取回同一份 JSON。
        val error = direct.exceptionOrNull()
        if (error is HttpStatusException && error.challenge) {
            FileLogger.w(TAG, "命中安全校验，改走 WebView 取数: $url")
            val body = challengeSolver.fetchJsonViaWebView(url)
            if (body != null) {
                val solved = runCatching { json.decodeFromString<T>(body) }
                if (solved.isSuccess) return@withContext solved
                FileLogger.w(TAG, "WebView 返回内容解析失败: $url", solved.exceptionOrNull())
            }
        }
        direct.onFailure { FileLogger.w(TAG, "商店请求失败: $url", it) }
    }

    /** 非 2xx 抛出带状态码与 `Retry-After` 的异常，交由 [withRetry] 判断是否重试。 */
    private fun Response.throwIfUnsuccessful() {
        if (isSuccessful) return
        throw HttpStatusException(
            code = code,
            retryAfterMillis = StoreRetryPolicy.parseRetryAfterMillis(header("Retry-After")),
            challenge = isBotChallenge()
        )
    }

    /**
     * 是否是「人机安全校验」拦截。
     *
     * 判定依据：非 2xx 且正文是 HTML（正常接口一律返回 JSON），或 Vercel 明确标了
     * `x-vercel-mitigated: challenge`。识别出来后界面会给「服务端安全校验」的提示，
     * 而不是让用户去查网络。
     */
    private fun Response.isBotChallenge(): Boolean {
        val contentType = header("Content-Type").orEmpty()
        val vercelChallenge = header("x-vercel-mitigated").equals("challenge", ignoreCase = true)
        return vercelChallenge || (code == 429 && contentType.startsWith("text/html", ignoreCase = true))
    }

    /**
     * 瞬时错误自动重试：GitHub / gh-proxy 等上游存在间歇性连接失败（连接 reset 等），
     * 首次请求失败后重试即可恢复。具体退避与 429 限流处理见 [StoreRetryPolicy]：
     * 429 不再按短退避硬撞，没有 `Retry-After` 时立即失败。
     */
    private suspend inline fun <T> withRetry(block: () -> Result<T>): Result<T> {
        var attempt = 1
        while (true) {
            val result = block()
            val error = result.exceptionOrNull() ?: return result
            val delayMs = StoreRetryPolicy.nextDelayMillis(error, attempt) ?: return result
            FileLogger.w(
                TAG,
                "瞬时错误，${delayMs}ms 后重试（第 ${attempt + 1}/${StoreRetryPolicy.MAX_ATTEMPTS} 次）: $error"
            )
            if (delayMs > 0) delay(delayMs)
            attempt++
        }
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
