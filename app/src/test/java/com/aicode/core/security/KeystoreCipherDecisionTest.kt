package com.aicode.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主密钥重建决策的不变量：只有「alias 不可见 且 本机从未生成过主密钥（无哨兵）」才允许重建。
 * 其余组合一律拒绝——尤其是 alias 不可见但哨兵存在：keystore 服务说谎或主密钥丢失，
 * 重建会覆盖真实密钥、令全部存量密文永久不可解（本项目三次密钥丢失事故的共同根因）。
 */
class KeystoreCipherDecisionTest {

    /** alias 可见：主密钥在，直接用，永不重建（哪怕哨兵缺失——那是老版本升级，读到即补写）。 */
    @Test
    fun aliasVisible_neverRegenerate() {
        assertFalse(KeystoreCipher.shouldRegenerate(aliasVisible = true, sentinelExists = true))
        assertFalse(KeystoreCipher.shouldRegenerate(aliasVisible = true, sentinelExists = false))
    }

    /** alias 不可见 + 哨兵存在：曾经生成过主密钥，此刻不可见只能是服务异常或真丢失——绝不重建。 */
    @Test
    fun aliasInvisible_withSentinel_neverRegenerate() {
        assertFalse(KeystoreCipher.shouldRegenerate(aliasVisible = false, sentinelExists = true))
    }

    /** 唯一允许重建的组合：真正的首次使用（无 alias 也无哨兵）。 */
    @Test
    fun firstUseOnly_allowsRegenerate() {
        assertTrue(KeystoreCipher.shouldRegenerate(aliasVisible = false, sentinelExists = false))
    }
}
