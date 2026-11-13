package com.aicode.feature.store.data

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 把 OkHttp 的 Cookie 读写接到系统 WebView 的 CookieManager。
 *
 * 在线商店需要先让 WebView 通过 Vercel 的人机安全校验；校验通过后 Cookie 落在
 * CookieManager 里，OkHttp 只有共享这一份 Cookie 才不会再被拦。读写均静默失败，
 * 不因 Cookie 解析问题影响正常请求。
 */
class WebViewCookieJar : CookieJar {

    private val manager: CookieManager? = runCatching { CookieManager.getInstance() }.getOrNull()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = runCatching { manager?.getCookie(url.toString()) }.getOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            runCatching { Cookie.parse(url, part.trim()) }.getOrNull()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val target = url.toString()
        cookies.forEach { cookie ->
            runCatching { manager?.setCookie(target, cookie.toString()) }
        }
    }
}
