package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.core.net.ProxyConfig
import com.aicode.core.net.ProxyPrefs
import com.aicode.feature.settings.domain.model.ProxyType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局代理设置。用 SharedPreferences 存储（而非 DataStore）：
 * 需要支持 Application.attachBaseContext 阶段的同步读取（见 [AppProxy]），
 * 那里没有任何注入、也等不了异步首帧。
 *
 * 存储 schema 与读写实现统一在 core/net/ProxyPrefs.kt（启动早期 core 层要能直读），
 * 本类只额外提供 StateFlow 缓存与 setter，避免两处维护同一套 key。
 */
@Singleton
class ProxySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val _config = MutableStateFlow(ProxyPrefs.read(context))
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
        ProxyPrefs.write(context, next)
        _config.value = next
    }

    companion object {
        /** 同步读取当前配置，供非注入场景（如 Application.attachBaseContext）使用。 */
        fun readConfig(context: Context): ProxyConfig = ProxyPrefs.read(context)
    }
}
