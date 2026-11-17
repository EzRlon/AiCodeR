package com.aicode.core.net

import android.content.Context
import androidx.core.content.edit

/**
 * 全局代理配置的 SharedPreferences 读写（core 层唯一事实源）。
 *
 * 为什么在 core：AppProxy 在 Application.attachBaseContext 阶段就要能读到代理配置，
 * 那时 Hilt 尚未装配、拿不到 feature 的 Repository。故存储 schema 与读取实现放在 core，
 * feature/settings 的 ProxySettingsRepository 委托本对象读写并叠加 StateFlow 缓存。
 */
object ProxyPrefs {
    const val PREFS_NAME = "proxy_settings"

    const val KEY_ENABLED = "enabled"
    const val KEY_TYPE = "type"
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_NO_PROXY = "no_proxy"

    /** 旧版默认绕过列表（无 CIDR 网段）：读到此值视为未自定义，迁移为新默认。 */
    private const val LEGACY_DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1"

    fun read(context: Context): ProxyConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val type = runCatching {
            ProxyType.valueOf(prefs.getString(KEY_TYPE, ProxyType.HTTP.name) ?: ProxyType.HTTP.name)
        }.getOrDefault(ProxyType.HTTP)
        return ProxyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            type = type,
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 0),
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            // 旧版本默认值（无内网网段）迁移为新默认列表，让输入框直接显示完整绕过地址。
            noProxy = (prefs.getString(KEY_NO_PROXY, null) ?: "")
                .takeUnless { it.isBlank() || it == LEGACY_DEFAULT_NO_PROXY }
                ?: DEFAULT_NO_PROXY
        )
    }

    fun write(context: Context, config: ProxyConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_TYPE, config.type.name)
            putString(KEY_HOST, config.host)
            putInt(KEY_PORT, config.port)
            putString(KEY_USERNAME, config.username)
            putString(KEY_PASSWORD, config.password)
            putString(KEY_NO_PROXY, config.noProxy)
        }
    }
}
