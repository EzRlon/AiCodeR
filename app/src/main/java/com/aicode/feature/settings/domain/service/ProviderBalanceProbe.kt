package com.aicode.feature.settings.domain.service

import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直接向供应商接口查询**真实余额**，覆盖「中转站 / 免费积分 / AI 网关」这几类没有统一协议的场景。
 *
 * ### 为什么要有它
 *
 * 自定义面板脚本（`dashboardScriptPath`）需要用户自己写脚本；对 one-api / new-api 这类中转站、
 * OpenRouter / DeepSeek 这类网关和官方服务，社区已经形成了几个事实上的接口，直接问一次就能拿到
 * 余额，不必让用户写脚本。顶栏状态条的「钱」位优先级为：
 * **面板脚本 → 本探测 → 本地估算**（用户 2026-11-11 定的 C 方案）。
 *
 * ### 安全边界
 *
 * - 只请求**用户自己配置的 baseUrl**，请求头只用该渠道的 API Key——与正常对话请求同源，不外发。
 * - **禁止跟随重定向**：否则一个 302 就能把 Authorization 头带去别的域名。
 * - 任何失败（401/404/超时/结构不符）一律返回 null，由调用方回落到估算，**不猜、不显示 0**。
 *
 * ### 支持的协议（按 baseUrl 域名/模型自动选择）
 *
 * | 形态 | 接口 | 取数方式 |
 * | --- | --- | --- |
 * | OpenRouter | `GET {base}/credits` | `total_credits - total_usage` |
 * | DeepSeek | `GET {root}/user/balance` | `balance_infos[0].total_balance` |
 * | OpenAI 兼容中转/网关 | `GET {root}/dashboard/billing/subscription` + `/usage` | 额度 − 用量/100 |
 * | one-api 系 | `GET {root}/api/user/self` | `data.quota / 500000` |
 *
 * 其中 `root` 是去掉末尾 `/v1` 的 baseUrl——billing 系列接口都挂在域名根下。
 */
@Singleton
class ProviderBalanceProbe @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 专用客户端：短超时 + 不跟随重定向。
     * 复用全局代理设置（与对话请求保持一致，否则开了代理的用户会探测失败）。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
            .build()
    }

    /** 查询余额；拿不到返回 null（调用方回落到估算）。 */
    suspend fun probe(provider: AIProviderConfig): String? = withContext(Dispatchers.IO) {
        val key = provider.apiKey.takeIf { it.isNotBlank() } ?: return@withContext null
        val base = provider.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext null
        val root = stripVersionSuffix(base)

        runCatching {
            when {
                isOpenRouter(base) -> probeOpenRouter(base, key)
                isDeepSeek(base) -> probeDeepSeek(root, key)
                provider.type == ProviderType.OPENAI -> probeOpenAiCompatible(root, key)
                // Anthropic / Gemini 官方没有任何余额接口，别浪费一次请求。
                else -> null
            }
        }.getOrNull()
    }

    // ── 各协议 ────────────────────────────────────────────────────────────

    private fun probeOpenRouter(base: String, key: String): String? {
        val body = get("$base/credits", key) ?: return null
        return parseOpenRouterCredits(body, json)
    }

    private fun probeDeepSeek(root: String, key: String): String? {
        val body = get("$root/user/balance", key) ?: return null
        return parseDeepSeekBalance(body, json)
    }

    /**
     * OpenAI 兼容的重转站/网关：先看 billing 两件套，再退回 one-api 的 user/self。
     * billing 的额度是总额、用量单位是「分」，两者相减才是余额。
     */
    private fun probeOpenAiCompatible(root: String, key: String): String? {
        val subscription = get("$root/dashboard/billing/subscription", key)
        if (subscription != null) {
            val limit = parseHardLimitUsd(subscription, json)
            if (limit != null) {
                val usage = get("$root/dashboard/billing/usage", key)
                    ?.let { parseTotalUsageCents(it, json) }
                    ?: 0.0
                val remaining = limit - usage / 100.0
                if (remaining >= 0) return formatUsd(remaining)
                // 额度为 0 通常是「无限额度/免费」或接口占位，显示 0 会误导，回落估算。
            }
        }
        val self = get("$root/api/user/self", key) ?: return null
        return parseOneApiQuota(self, json)
    }

    private fun get(url: String, key: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    private companion object {
        fun isOpenRouter(base: String) = base.contains("openrouter", ignoreCase = true)
        fun isDeepSeek(base: String) = base.contains("deepseek", ignoreCase = true)

        /** 去掉末尾的版本段：`https://api.x.com/v1` → `https://api.x.com`（billing 挂在根上）。 */
        fun stripVersionSuffix(base: String): String =
            base.removeSuffix("/v1").removeSuffix("/v1beta").trimEnd('/')
    }
}

// ── 纯解析函数（单测直接覆盖）────────────────────────────────────────────

/** OpenRouter `/credits`：`{"data":{"total_credits":10,"total_usage":3.5}}`。 */
internal fun parseOpenRouterCredits(body: String, json: Json): String? {
    val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull()
        ?: return null
    val credits = data["total_credits"]?.jsonPrimitive?.doubleOrNull ?: return null
    val usage = data["total_usage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val remaining = credits - usage
    return if (remaining >= 0) formatUsd(remaining) else null
}

/** DeepSeek `/user/balance`：`{"balance_infos":[{"currency":"CNY","total_balance":"110.00"}]}`。 */
internal fun parseDeepSeekBalance(body: String, json: Json): String? {
    val infos = runCatching {
        json.parseToJsonElement(body).jsonObject["balance_infos"]?.jsonArray
    }.getOrNull() ?: return null
    val first = infos.firstOrNull()?.jsonObject ?: return null
    val amount = first["total_balance"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
    val currency = first["currency"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
    return formatAmountWithCurrency(amount, currency)
}

/** OpenAI 兼容 `/dashboard/billing/subscription`：`{"hard_limit_usd":100}`。 */
internal fun parseHardLimitUsd(body: String, json: Json): Double? = runCatching {
    json.parseToJsonElement(body).jsonObject["hard_limit_usd"]?.jsonPrimitive?.doubleOrNull
}.getOrNull()?.takeIf { it > 0 }

/** OpenAI 兼容 `/dashboard/billing/usage`：`{"total_usage":12345}`，单位是「分」。 */
internal fun parseTotalUsageCents(body: String, json: Json): Double? = runCatching {
    json.parseToJsonElement(body).jsonObject["total_usage"]?.jsonPrimitive?.doubleOrNull
}.getOrNull()

/** one-api `/api/user/self`：`{"data":{"quota":500000}}`，quota 单位是 1/500000 美元。 */
internal fun parseOneApiQuota(body: String, json: Json): String? {
    val quota = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("quota")?.jsonPrimitive?.doubleOrNull
    }.getOrNull() ?: return null
    if (quota < 0) return null
    return formatUsd(quota / 500_000.0)
}

/**
 * 金额统一两位小数。
 *
 * - 恰好 0 → `$0.00`（真实余额为零，必须如实显示，不能糊成「小于一分」）；
 * - 0 与 1 分之间 → `<$0.01`，避免四舍五入成 0 让人误以为没钱；
 * - 负数 → 空串（调用方应在此之前判空）。
 */
internal fun formatUsd(value: Double): String = when {
    value < 0 -> ""
    value == 0.0 -> "\$0.00"
    value < 0.005 -> "<\$0.01"
    else -> String.format(Locale.US, "\$%.2f", value)
}

/** 带币种的余额：CNY → ¥、USD → $、其它原样附在后面。 */
internal fun formatAmountWithCurrency(amount: String, currency: String): String? {
    val trimmed = amount.trim()
    if (trimmed.isEmpty()) return null
    return when (currency.uppercase()) {
        "CNY", "RMB" -> if (trimmed.startsWith("¥")) trimmed else "¥$trimmed"
        "USD" -> if (trimmed.startsWith("$")) trimmed else "\$$trimmed"
        "" -> trimmed
        else -> "$trimmed $currency"
    }
}

/** `JsonPrimitive.content` 在非字符串字面量上也能取到文本形式，这里统一兜底为 null。 */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
