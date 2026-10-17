package com.aicode.feature.store.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 国内可直连的免费翻译（微软 Edge 翻译的公开接口，无需 key）：
 * 先取短期 token，再批量 en→zh-Hans。作为默认模型翻译的补充/兜底——
 * 无 VPN 时商店不再因「默认模型不可用」而整体显示英文原文。
 */
@Singleton
class FreeTranslationService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpiresAt = 0L

    /** 当前活跃网络是否经 VPN（含代理类工具）。 */
    fun hasVpn(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    /** 批量翻译；返回与输入等长且同序的结果，失败返回 null。401 时清 token 重试一次。 */
    suspend fun translate(texts: List<String>): List<String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        requestTranslate(texts) ?: run {
            token = null
            tokenExpiresAt = 0L
            requestTranslate(texts)
        }
    }

    private fun requestTranslate(texts: List<String>): List<String>? = runCatching {
        val auth = validToken() ?: return@runCatching null
        val body = JSONArray().apply {
            texts.forEach { put(JSONObject().put("Text", it.take(MAX_TEXT_CHARS))) }
        }.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("https://api-edge.cognitive.microsofttranslator.com/translate?from=&to=zh-Hans&api-version=3.0")
            .header("Authorization", "Bearer $auth")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                FileLogger.w(TAG, "免费翻译 HTTP ${resp.code}")
                if (resp.code == 401) {
                    token = null
                    tokenExpiresAt = 0L
                }
                return@runCatching null
            }
            val arr = JSONArray(resp.body?.string().orEmpty())
            if (arr.length() != texts.size) return@runCatching null
            texts.indices.map { i ->
                arr.optJSONObject(i)?.optJSONArray("translations")?.optJSONObject(0)
                    ?.optString("text").orEmpty()
            }
        }
    }.getOrNull()

    private fun validToken(): String? {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpiresAt) return it }
        val request = Request.Builder().url("https://edge.microsoft.com/translate/auth").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val fresh = resp.body?.string()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            token = fresh
            tokenExpiresAt = now + TOKEN_TTL_MS
            return fresh
        }
    }

    private companion object {
        const val TAG = "FreeTranslationService"
        val JSON_MEDIA = "application/json".toMediaType()

        /** 接口单条文本上限 1000 字符。 */
        const val MAX_TEXT_CHARS = 1000
        const val TOKEN_TTL_MS = 8 * 60_000L
    }
}
