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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 国内可直连的免费翻译（无需 key）：
 * 主源腾讯交互翻译（transmart，支持整批 text_list 一次翻译）；失败由 MyMemory 逐条兜底。
 * 作为默认模型翻译的补充/兜底——无 VPN 时商店不再因「默认模型不可用」而整体显示英文原文。
 */
@Singleton
class FreeTranslationService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 当前活跃网络是否经 VPN（含代理类工具）。 */
    fun hasVpn(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    /** 批量翻译 en→zh；返回与输入等长且同序的结果，失败返回 null。 */
    suspend fun translate(texts: List<String>): List<String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        translateTencent(texts) ?: translateMyMemory(texts)
    }

    /** 腾讯交互翻译：一次请求翻整批（text_list）。 */
    private fun translateTencent(texts: List<String>): List<String>? = runCatching {
        val payload = JSONObject().apply {
            put("header", JSONObject().apply {
                put("fn", "auto_translation")
                put("client_key", "browser-chrome-110.0.0.0-1.0.0")
            })
            put("type", "plain")
            put("model_category", "normal")
            put("text_domain", "general")
            put("source", JSONObject().apply {
                put("lang", "en")
                put("text_list", JSONArray(texts.map { it.take(MAX_TEXT_CHARS) }))
            })
            put("target", JSONObject().put("lang", "zh"))
        }.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("https://transmart.qq.com/api/imt")
            .header("Content-Type", "application/json")
            .post(payload)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                FileLogger.w(TAG, "腾讯翻译 HTTP ${resp.code}")
                return@runCatching null
            }
            val obj = JSONObject(resp.body?.string().orEmpty())
            val code = obj.optJSONObject("header")?.optString("ret_code")
            if (code != "succ") {
                FileLogger.w(TAG, "腾讯翻译返回 ret_code=$code")
                return@runCatching null
            }
            val arr = obj.optJSONArray("auto_translation") ?: return@runCatching null
            if (arr.length() != texts.size) return@runCatching null
            texts.indices.map { i -> arr.optString(i).orEmpty() }
        }
    }.getOrNull()

    /** MyMemory 逐条兜底（匿名额度小，仅作腾讯失败时的最后防线）。 */
    private fun translateMyMemory(texts: List<String>): List<String>? = runCatching {
        val results = ArrayList<String>(texts.size)
        for (text in texts) {
            val q = URLEncoder.encode(text.take(MYMEMORY_MAX_CHARS), "UTF-8")
            val request = Request.Builder()
                .url("https://api.mymemory.translated.net/get?q=$q&langpair=en|zh-CN")
                .build()
            val ok = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val obj = JSONObject(resp.body?.string().orEmpty())
                val status = obj.optInt("responseStatus", 0)
                if (status != 200) return@runCatching null
                val translated = obj.optJSONObject("responseData")
                    ?.optString("translatedText").orEmpty()
                if (translated.isBlank()) return@runCatching null
                results.add(translated)
                true
            }
            if (!ok) return@runCatching null
        }
        results
    }.getOrNull()

    private companion object {
        const val TAG = "FreeTranslationService"
        val JSON_MEDIA = "application/json".toMediaType()

        /** 接口单条文本上限（腾讯长文本亦支持，此处保守截断）。 */
        const val MAX_TEXT_CHARS = 1000

        /** MyMemory 单条上限约 500 字节，中文按 480 字符保守截断。 */
        const val MYMEMORY_MAX_CHARS = 480
    }
}
