package com.aicode.feature.backup.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 资产条目白名单校验（导入侧防穿越第一道闸）的边界测试。 */
class BackupAicodeAssetPathTest {

    @Test
    fun allowedPathsPassWhitelist() {
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("agents.json"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("skills.json"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("git-credentials"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("hooks.json"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("skills/token-saver/SKILL.md"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("agents/explore.md"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("teams/dev-squad.md"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("prompts.custom/10-x.md"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("memory/profile.md"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("notes/handoff.md"))
        assertTrue(BackupManagerImpl.isAllowedAicodeAsset("keys/release.jks"))
    }

    @Test
    fun traversalAndUnknownPathsRejected() {
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset(""))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("../git-credentials"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("skills/../../etc/passwd"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("/etc/passwd"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("skills//SKILL.md"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("./agents.json"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("vnc/x.png"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("prompts/10-x.md"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("unknown.json"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("keys"))
        // 反斜杠不是 Linux 分隔符，不能借此伪造白名单段（canonical 校验兜底）
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("skills\\..\\..\\etc/passwd"))
        assertFalse(BackupManagerImpl.isAllowedAicodeAsset("aicode\\..\\x"))
    }
}
