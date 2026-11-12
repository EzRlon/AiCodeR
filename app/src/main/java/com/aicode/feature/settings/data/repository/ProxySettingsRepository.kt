package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.core.content.edit
import com.aicode.core.net.DEFAULT_NO_PROXY
import com.aicode.core.net.ProxyConfig
import com.aicode.feature.settings.domain.model.ProxyType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// ProxyConfig / DEFAULT_NO_PROXY 的事实源在 core/net/ProxyModels.kt（AppProxy 不能反向
// 依赖 feature，见该文件注释），此处 typealias re-export 保兼容旧 import 路径。
/** @see com.aicode.core.net.ProxyConfig */
typealias ProxyConfig = com.aicode.core.net.ProxyConfig

/**
 * 全局代理设置。用 SharedPreferences 存储（而非 DataStore）：
 * 需要支持 Application.attachBaseContext 阶段的同步读取（见 [AppProxy]），
 * 那里没有任何注入、也等不了异步首帧。
 */
@Singleton
class ProxySettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig(prefs))
    val config: StateFlow<ProxyConfig> = _config.asStateFlow()

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setType(type: ProxyType) = update { it.copy(type = type) }
    fun setHost(host: String) = update { it.copy(host = host.trim()) }
    fun setPort(port: Int) = update { it.copy(port = port) }
    fun setUsername(username: String) = update { it.copy(username = username) }
    fun setPassword(password: String) = update { it.copy(password = password) }
    fun setNoProxy(noProxy: String) = update { it.copy(noProxy = noProxy) }

    private fun update(transform: (ProxyConfig) -> ProxyConfig) {
        val next = transform(_config.value)
        prefs.edit {
            putBoolean(KEY_ENABLED, next.enabled)
            putString(KEY_TYPE, next.type.name)
            putString(KEY_HOST, next.host)
            putInt(KEY_PORT, next.port)
            putString(KEY_USERNAME, next.username)
            putString(KEY_PASSWORD, next.password)
            putString(KEY_NO_PROXY, next.noProxy)
        }
        _config.value = next
    }

    companion object {
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

        /** 同步读取当前配置，供非注入场景（如 Application.attachBaseContext）使用。 */
        fun readConfig(context: Context): ProxyConfig =
            readConfig(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        private fun readConfig(prefs: android.content.SharedPreferences): ProxyConfig = ProxyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            type = runCatching { ProxyType.valueOf(prefs.getString(KEY_TYPE, ProxyType.HTTP.name)!!) }
                .getOrDefault(ProxyType.HTTP),
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
}
