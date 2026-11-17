package com.aicode.feature.backup.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「含凭据/私钥的导出必须加密」这一安全不变量：
 * UI 依此禁用空口令确认，ViewModel 依此拒绝明文落盘。任何一项被改成默认关闭都应让本测试失败。
 */
class BackupOptionsSecretsTest {

    @Test
    fun `默认选项视为含敏感信息`() {
        assertTrue(BackupOptions().containsSecrets())
    }

    @Test
    fun `仅聊天记录与应用设置时允许不加密`() {
        val options = BackupOptions(
            providers = false,
            remoteConnections = false,
            chatHistory = true,
            mcpServers = true,
            permissionRules = true,
            appSettings = true,
            aiAssets = false,
            keys = false
        )
        assertFalse(options.containsSecrets())
    }

    @Test
    fun `任一项敏感段开启即要求加密`() {
        val base = BackupOptions(
            providers = false,
            remoteConnections = false,
            chatHistory = false,
            mcpServers = false,
            permissionRules = false,
            appSettings = false,
            aiAssets = false,
            keys = false
        )
        assertFalse(base.containsSecrets())
        assertTrue(base.copy(providers = true).containsSecrets())
        assertTrue(base.copy(remoteConnections = true).containsSecrets())
        assertTrue(base.copy(aiAssets = true).containsSecrets())
        assertTrue(base.copy(keys = true).containsSecrets())
    }
}
