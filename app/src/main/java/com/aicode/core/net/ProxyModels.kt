package com.aicode.core.net

/**
 * 代理领域模型（core 层唯一事实源）。
 *
 * 原本定义在 feature/settings 的 data/repository 包，导致 core/net/AppProxy（Application
 * attachBaseContext 阶段就要工作的基础设施）反向依赖业务 feature——分层违规且有初始化时序
 * 风险。现下沉到 core：AppProxy 只依赖本文件；settings 的读写实现（ProxySettingsRepository、
 * ProviderProxyRegistry）通过 [AppProxy.registerProviderProxyLookup] 在启动装配时挂载。
 */

/** 代理协议类型：HTTP（CONNECT 隧道，可代理全部 http/https 流量）或 SOCKS5（TCP 级转发）。 */
enum class ProxyType {
    HTTP, SOCKS5
}

/** 默认不走代理的主机/网段列表（含内网 CIDR）。 */
const val DEFAULT_NO_PROXY = "localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1"

/** 全局 HTTP/SOCKS5 代理配置快照。 */
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** 不走代理的主机列表（逗号分隔，精确匹配 host）。 */
    val noProxy: String = DEFAULT_NO_PROXY
) {
    /** 配置是否完整可用：仅当开启且 host 非空、port 合法时才会真正走代理。 */
    val valid: Boolean
        get() = host.isNotBlank() && port in 1..65535
}

/** 提供商级代理条目：按目标 URL 查表得到。 */
data class ProviderProxyEntry(
    val providerId: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** baseUrl 的路径前缀（已归一化：去尾斜杠；根路径为空串，匹配该主机全部请求）。 */
    val basePath: String = ""
) {
    val valid: Boolean get() = host.isNotBlank() && port in 1..65535
}

/**
 * AppProxy 对「提供商级代理分派」的最小依赖面。feature/settings 的 ProviderProxyRegistry
 * 实现本接口并在 DI 装配后注册进 AppProxy；core 层不感知任何 feature 类型。
 */
interface ProviderProxyLookup {
    /** 按目标 host + 路径前缀查找提供商代理；未命中返回 null。 */
    fun findByUrl(host: String, path: String?): ProviderProxyEntry?

    /** 按代理服务器地址（而非目标 host）查找：SOCKS5 握手认证时按连接所用的代理匹配凭据。 */
    fun findByProxyHost(proxyHost: String): ProviderProxyEntry?
}
