package com.aicode.feature.agent.domain.engine

/** 单条厂商路由：Claude Code 引擎直连用的端点与档位信息。 */
data class VendorAnthropicRoute(
    val vendorId: String,
    val vendorLabel: String,
    val baseUrl: String,
    val defaultModel: String?,
    val fastModel: String?,
    val extraEnv: Map<String, String>,
)

/**
 * 厂商 Anthropic 兼容端点表：App 内 OpenAI 型供应商按 baseUrl 的 host 命中后，
 * CC 引擎自动换算到厂商官方 Anthropic 端点，免另建 Anthropic 型供应商。
 * 端点、fastModel 与 extraEnv 移植自 cccodroid providers.mjs
 * （Apache-2.0，github.com/greyoak111/cccodroid，2026-10 逐厂商实测口径）。
 * 鉴权不区分头风格：凭据经 claude 的 apiKeyHelper 注入，其输出会同时放进
 * x-api-key 与 Authorization: Bearer，官方与第三方（Bearer）全部覆盖。
 */
object EngineVendorCompat {

    private fun route(
        vendorId: String,
        vendorLabel: String,
        baseUrl: String,
        defaultModel: String,
        fastModel: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): VendorAnthropicRoute = VendorAnthropicRoute(vendorId, vendorLabel, baseUrl, defaultModel, fastModel, extraEnv)

    private val routes = listOf(
        route("deepseek", "DeepSeek", "https://api.deepseek.com/anthropic", "deepseek-flash", "deepseek-flash"),
        route("glm", "智谱 GLM", "https://open.bigmodel.cn/api/anthropic", "glm-5.2", "glm-4.7", mapOf("API_TIMEOUT_MS" to "3000000")),
        route("glm-intl", "智谱 GLM 国际", "https://api.z.ai/api/anthropic", "glm-5.2", "glm-4.7", mapOf("API_TIMEOUT_MS" to "3000000")),
        route("qwen", "通义千问", "https://dashscope.aliyuncs.com/apps/anthropic", "qwen3.7-max", "qwen3.6-flash"),
        route("qwen-coding", "通义千问编程计划", "https://coding.dashscope.aliyuncs.com/apps/anthropic", "qwen3.7-plus", "qwen3.7-plus"),
        route("qwen-token", "通义千问 Token 计划", "https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic", "qwen3.8-max", "qwen3.8-flash"),
        route("kimi", "Kimi", "https://api.moonshot.cn/anthropic", "kimi-k3", "kimi-k2.7-code-highspeed"),
        route("kimi-intl", "Kimi 国际", "https://api.moonshot.ai/anthropic", "kimi-k3", "kimi-k2.7-code-highspeed"),
        route("minimax", "MiniMax", "https://api.minimax.cn/anthropic", "MiniMax-M3.1-Flash", "MiniMax-M3"),
        route("minimax-intl", "MiniMax 国际", "https://api.minimax.io/anthropic", "MiniMax-M3.1-Flash", "MiniMax-M3"),
        route("ark", "火山方舟", "https://ark.cn-beijing.volces.com/api/coding", "ark-code-latest", "doubao-seed-2.1-lite", mapOf("API_TIMEOUT_MS" to "3000000")),
        route("mimo", "小米 MiMo", "https://api.xiaomimimo.com/anthropic", "mimo-v2.6-pro", "mimo-v2.6-flash"),
        route("mimo-token", "小米 MiMo Token 计划", "https://token-plan-cn.xiaomimimo.com/anthropic", "mimo-v2.6-pro", "mimo-v2.6-flash"),
        route("stepfun", "阶跃星辰", "https://api.stepfun.com", "step-5-preview", "step-3.7-flash"),
        route("stepfun-intl", "阶跃星辰国际", "https://api.stepfun.ai", "step-5-preview", "step-3.7-flash"),
        route("qianfan", "百度千帆", "https://qianfan.baidubce.com/anthropic", "deepseek-v3.2", "deepseek-v3.2", mapOf("API_TIMEOUT_MS" to "600000")),
        route("hunyuan", "腾讯混元", "https://tokenhub.tencentmaas.com", "hy3-preview", "hy3-preview", mapOf("ENABLE_TOOL_SEARCH" to "false")),
        route("hunyuan-intl", "腾讯混元国际", "https://tokenhub-intl.tencentmaas.com", "hy3-preview", "hy3-preview", mapOf("ENABLE_TOOL_SEARCH" to "false")),
        route("longcat", "美团 LongCat", "https://api.longcat.chat/anthropic", "LongCat-2.5-Preview", "LongCat-2.5-Preview", mapOf("CLAUDE_CODE_MAX_OUTPUT_TOKENS" to "131072")),
        route("xai", "xAI Grok", "https://api.x.ai", "grok-4.7", "grok-4.20-0309-non-reasoning"),
        route("meta", "Meta Muse", "https://api.meta.ai", "muse-wonder-1.0", "muse-spark-1.3", mapOf("ENABLE_TOOL_SEARCH" to "true")),
    )

    private val byHost: Map<String, VendorAnthropicRoute> = routes.mapNotNull { r ->
        val host = runCatching { java.net.URI(r.baseUrl).host?.lowercase() }.getOrNull()
        if (host.isNullOrBlank()) null else host to r
    }.toMap()

    /** 按供应商 baseUrl 的 host 匹配厂商 Anthropic 兼容端点；未命中返回 null（该供应商无法直连 CC 引擎）。 */
    fun routeForHost(baseUrl: String): VendorAnthropicRoute? {
        val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull() ?: return null
        return byHost[host]
    }
}
