package com.aicode.feature.agent.domain.provider

import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 多 Key 网络断流自动切换的判定层回归（bug 事故：断流/超时会死抱同一个 Key 打转，
 * 用户必须手动中断；修复要求多 Key 默认就切，用户可显式关闭）。
 */
class NetworkFailoverTest {

    @Test
    fun `超时-连接重置-断流-EOF 都识别为网络失败`() {
        assertTrue(SocketTimeoutException("connect timed out").isNetworkFailure())
        assertTrue(SocketException("Connection reset").isNetworkFailure())
        assertTrue(IOException("unexpected end of stream").isNetworkFailure())
        assertTrue(IOException("connection closed").isNetworkFailure())
        assertTrue(EOFException().isNetworkFailure())
        assertTrue(UnknownHostException("api.example.com").isNetworkFailure())
    }

    @Test
    fun `业务异常与包装链外的错误不算网络失败`() {
        assertFalse(IllegalStateException("boom").isNetworkFailure())
        assertFalse(IOException("disk full").isNetworkFailure())
        // 包装链：外层任意异常、cause 是网络异常 → 命中
        assertTrue(IllegalStateException("wrap", SocketTimeoutException("timeout")).isNetworkFailure())
    }

    @Test
    fun `网络断流默认开启自动换 Key，用户可显式关闭`() {
        val base = AIProviderConfig(
            id = "p",
            name = "n",
            type = ProviderType.OPENAI,
            apiKey = "k",
            baseUrl = "https://example.com",
            defaultModel = "m"
        )
        assertTrue("默认应为开启", base.keyNetworkFailoverEnabled)
        assertFalse(
            "显式关闭后不再因网络失败切换",
            base.copy(keyNetworkFailoverEnabled = false).keyNetworkFailoverEnabled
        )
    }
}
