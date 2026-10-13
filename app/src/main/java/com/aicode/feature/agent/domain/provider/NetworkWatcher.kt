package com.aicode.feature.agent.domain.provider

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 默认网络变化监听：WiFi / 移动网络 / VPN 通道切换时，主动「打断」所有活跃的 LLM 流式请求。
 *
 * 网络切换会让已建立的 TCP 连接静默失效（不报错也不断开），不打断的话要等首字节/数据块
 * 兜底超时（分钟级）才会重试，用户侧表现为「一直转圈」。打断后底层流抛 IOException，
 * 由各请求自带的重试机制自动在新网络上重发（重新解析 DNS 与建连）。
 *
 * 构造即注册监听（由 Application 持有，进程级常驻）；一次切换会连续触发 lost/available/
 * linkProperties 多个回调，500ms 去抖窗口内只处理第一个。
 */
@Singleton
class NetworkWatcher @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val lastInterruptAtMillis = AtomicLong(0)

    init {
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = interruptActiveStreams()
                override fun onLost(network: Network) = interruptActiveStreams()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    interruptActiveStreams()
            })
        }.onFailure { FileLogger.w(TAG, "注册默认网络监听失败", it) }
    }

    private fun interruptActiveStreams() {
        val now = SystemClock.elapsedRealtime()
        val last = lastInterruptAtMillis.get()
        if (now - last < DEBOUNCE_MS) return
        if (!lastInterruptAtMillis.compareAndSet(last, now)) return
        FileLogger.i(TAG, "默认网络发生变化，打断活跃流式请求以触发快速重试")
        ActiveStreamGuardRegistry.interruptAll()
    }

    private companion object {
        const val TAG = "NetworkWatcher"

        /** 一次切换会连续触发多个回调，去抖窗口内只处理第一个。 */
        const val DEBOUNCE_MS = 500L
    }
}
