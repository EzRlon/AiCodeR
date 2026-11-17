package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 密钥文件分类：证书本体与签名配套口令配置同属发布材料，普通 properties 不得混入。 */
class KeyFileClassificationTest {

    @Test
    fun `口令配置归发布类别且可被精确识别`() {
        assertTrue(isSigningConfigName("keystore.properties"))
        assertTrue(isSigningConfigName("Signing.Properties"))
        assertTrue(isSigningConfigName(" keystore.properties "))
        assertEquals(KeyCategory.RELEASE, classifyKeyFile(KeySource.LIBRARY, "keystore.properties"))
        assertEquals(KeyCategory.RELEASE, classifyKeyFile(KeySource.WORKSPACE, "signing.properties"))
    }

    @Test
    fun `普通 properties 与证书扩展名判定不变`() {
        assertFalse(isSigningConfigName("gradle.properties"))
        assertEquals(KeyCategory.OTHER, classifyKeyFile(KeySource.WORKSPACE, "gradle.properties"))
        assertEquals(KeyCategory.RELEASE, classifyKeyFile(KeySource.WORKSPACE, "aicode.jks"))
        assertEquals(KeyCategory.SSH, classifyKeyFile(KeySource.SSH, "keystore.properties"))
    }

    @Test
    fun `条目默认不是口令配置`() {
        val cert = KeyFile(
            name = "aicode.jks",
            containerPath = "~/.aicode/keys/aicode.jks",
            source = KeySource.LIBRARY,
            category = KeyCategory.RELEASE,
            size = 1,
            lastModified = 1,
            fingerprint = null
        )
        assertFalse(cert.isSigningConfig)
    }
}
