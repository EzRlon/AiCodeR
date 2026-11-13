package com.aicode.feature.credentials.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** 设备码授权页最终由哪个通道打开。 */
enum class GitHubAuthChannel {
    /** 已安装 GitHub 客户端，交由客户端打开。 */
    CLIENT,

    /** 未安装客户端（或拉起失败），用系统浏览器打开。 */
    BROWSER,

    /** 没有任何可处理的 App。 */
    NONE
}

/**
 * GitHub 账号授权的外部跳转入口。
 *
 * 背景：GitHub 官方客户端（com.github.android）对 github.com 链接注册了 AutoVerify 深链，
 * 是 `github.com/login/device` 的默认处理方；它没有设备码页面，拉起后会立即 finish，
 * 表现为「点授权什么都没发生」。所以这里不能只发一个裸 ACTION_VIEW。
 *
 * 策略（用户 2026-11-12 定）：
 * 1. 设备装了 GitHub 客户端 → 优先显式拉起客户端，让已登录用户直接授权；
 * 2. 没装客户端、或客户端拉起失败 → 显式选择**浏览器**包打开（解析结果若指向客户端则跳过），
 *    避免再次被深链劫持；
 * 3. 都没有时返回 [GitHubAuthChannel.NONE]，由调用方提示用户自行打开链接。
 */
object GitHubAuthLauncher {

    private const val GITHUB_PACKAGE = "com.github.android"

    /** 常见浏览器包名，按优先级排列（厂商内置浏览器也能覆盖到）。 */
    private val KNOWN_BROWSERS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.mi.globalbrowser",
        "com.miui.core",
        "com.heytap.browser",
        "com.oppo.browser",
        "com.vivo.browser",
        "com.huawei.browser",
        "com.quark.browser",
        "com.UCMobile",
        "com.tencent.mtt",
        "com.android.browser"
    )

    /** 设备是否安装了 GitHub 官方客户端。 */
    fun isClientInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(GITHUB_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * 打开设备码授权页：装了客户端先拉起客户端，否则用浏览器。
     * [userCode] 会作为 `user_code` 查询参数带上（GitHub 页面支持时自动预填，不支持时忽略）。
     */
    fun openDeviceAuthorization(
        context: Context,
        verificationUri: String,
        userCode: String
    ): GitHubAuthChannel {
        val url = buildDeviceUrl(verificationUri, userCode)
        if (isClientInstalled(context) && launchInClient(context, url)) {
            return GitHubAuthChannel.CLIENT
        }
        return if (launchInBrowser(context, url)) GitHubAuthChannel.BROWSER else GitHubAuthChannel.NONE
    }

    /** 强制走浏览器（供「改用浏览器打开」的兜底入口）。 */
    fun openDeviceAuthorizationInBrowser(
        context: Context,
        verificationUri: String,
        userCode: String
    ): GitHubAuthChannel {
        val url = buildDeviceUrl(verificationUri, userCode)
        return if (launchInBrowser(context, url)) GitHubAuthChannel.BROWSER else GitHubAuthChannel.NONE
    }

    /** 打开 GitHub App 安装页，同样优先用浏览器避免被客户端抢链。 */
    fun openInstallPage(context: Context, url: String): GitHubAuthChannel =
        if (launchInBrowser(context, url)) GitHubAuthChannel.BROWSER else GitHubAuthChannel.NONE

    private fun buildDeviceUrl(verificationUri: String, userCode: String): String {
        if (userCode.isBlank()) return verificationUri
        return runCatching {
            Uri.parse(verificationUri)
                .buildUpon()
                .appendQueryParameter("user_code", userCode)
                .build()
                .toString()
        }.getOrDefault(verificationUri)
    }

    private fun launchInClient(context: Context, url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(GITHUB_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)

    /**
     * 用浏览器打开：先从能处理 https 链接的包中排除 GitHub 客户端，优先系统默认浏览器，
     * 其次常见浏览器白名单，最后退回任一可用处理方。找不到时返回 false。
     */
    private fun launchInBrowser(context: Context, url: String): Boolean {
        val pm = context.packageManager
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val candidates = pm.queryIntentActivities(viewIntent, flags)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != GITHUB_PACKAGE }
        if (candidates.isEmpty()) return false

        val defaultPackage = pm.resolveActivity(viewIntent, flags)?.activityInfo?.packageName
        val target = defaultPackage?.takeIf { it in candidates }
            ?: KNOWN_BROWSERS.firstOrNull { it in candidates }
            ?: candidates.first()

        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(target)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
    }
}
