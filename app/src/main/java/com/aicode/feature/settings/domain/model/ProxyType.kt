package com.aicode.feature.settings.domain.model

/**
 * 代理协议类型：HTTP（CONNECT 隧道，可代理全部 http/https 流量）或 SOCKS5（TCP 级转发）。
 *
 * 事实源已下沉 core/net（AppProxy 等基础设施不能反向依赖 feature），本类型退化为
 * typealias 保兼容：feature 内既有 import 不用改；新代码建议直接 import core 版本。
 */
typealias ProxyType = com.aicode.core.net.ProxyType
