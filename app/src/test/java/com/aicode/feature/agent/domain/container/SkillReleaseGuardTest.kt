package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.container.SkillReleaseGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * 内置技能释放的用户改动保护。
 *
 * 回归点：`extractBuiltinSkills` 原先是「版本戳不同 → 先删同名目录再释放」，用户改过的同名内置
 * 技能会被静默覆盖。这里钉住「按内容指纹判断」的行为：没改过才更新，改过就跳过。
 */
class SkillReleaseGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun skillDir(name: String, body: String = "body"): File =
        tmp.newFolder(name).apply {
            File(this, "SKILL.md").writeText("# $name\n$body\n")
            File(this, "scripts").mkdirs()
            File(this, "scripts/task-start").writeText("#!/bin/sh\necho hi\n")
        }

    @Test
    fun 目标不存在时判定为新释放() {
        val dest = File(tmp.root, "brand-new")
        assertEquals(Decision.RELEASE_NEW, SkillReleaseGuard.decide(dest, recordedFingerprint = null))
    }

    @Test
    fun 已存在但无记录时保守跳过_不删用户自建的同名技能() {
        // 无记录 + 目录已存在：可能是用户自建的同名技能（绝不能删），也可能是本机制上线前
        // 旧版释放的。二者无法区分，一律按「用户改动」跳过。
        val dest = skillDir("my-own")
        assertEquals(Decision.SKIP_USER_MODIFIED, SkillReleaseGuard.decide(dest, recordedFingerprint = null))
        assertTrue("跳过就不能被删掉", dest.isDirectory)
        assertTrue(File(dest, "SKILL.md").isFile)
    }

    @Test
    fun 指纹一致时判定为随版本更新() {
        val dest = skillDir("executing-plans")
        val recorded = SkillReleaseGuard.directoryFingerprint(dest)
        assertEquals(Decision.RELEASE_UPDATE, SkillReleaseGuard.decide(dest, recorded))
    }

    @Test
    fun 用户改过时判定为跳过() {
        val dest = skillDir("executing-plans")
        val recorded = SkillReleaseGuard.directoryFingerprint(dest)
        // 用户改了技能正文
        File(dest, "SKILL.md").writeText("# 用户改过的内容\n")
        assertEquals(Decision.SKIP_USER_MODIFIED, SkillReleaseGuard.decide(dest, recorded))
    }

    @Test
    fun 用户删了文件时也判定为跳过_不把删掉的文件拉回来() {
        val dest = skillDir("writing-plans")
        val recorded = SkillReleaseGuard.directoryFingerprint(dest)
        File(dest, "scripts/task-start").delete()
        assertEquals(Decision.SKIP_USER_MODIFIED, SkillReleaseGuard.decide(dest, recorded))
    }

    @Test
    fun 指纹对内容敏感_改一个字节即变化() {
        val a = skillDir("a")
        val before = SkillReleaseGuard.directoryFingerprint(a)
        File(a, "SKILL.md").appendText("x")
        assertNotEquals(before, SkillReleaseGuard.directoryFingerprint(a))
    }

    @Test
    fun 指纹对文件创建顺序不敏感_同内容同指纹() {
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        for (dir in listOf(a, b)) {
            File(dir, "one.txt").writeText("1")
            File(dir, "two.txt").writeText("2")
        }
        assertEquals(
            SkillReleaseGuard.directoryFingerprint(a),
            SkillReleaseGuard.directoryFingerprint(b)
        )
    }

    @Test
    fun 空指纹不参与相等判定_空目录或不可读一律按不一致处理() {
        // 空目录指纹为空串；与任何记录比对都不得判为「一致」，否则会把用户清空的技能当成内置版覆盖。
        assertTrue(!SkillReleaseGuard.fingerprintsEqual("", "sha256:abc"))
        assertTrue(!SkillReleaseGuard.fingerprintsEqual("sha256:abc", ""))
        assertTrue(!SkillReleaseGuard.fingerprintsEqual("", ""))
        assertTrue(SkillReleaseGuard.fingerprintsEqual("sha256:abc", "sha256:abc"))
    }

    @Test
    fun 符号链接不进指纹且不下钻() {
        val dest = skillDir("with-link")
        val outside = tmp.newFolder("outside")
        File(outside, "important.txt").writeText("keep")
        val before = SkillReleaseGuard.directoryFingerprint(dest)
        // 在技能目录里放一个指向目录外的符号链接
        Files.createSymbolicLink(File(dest, "link").toPath(), outside.toPath())
        // 链接作为条目计入（L: 前缀），但不会读取外部文件内容——删掉外部文件指纹不变
        File(outside, "important.txt").writeText("changed")
        val after = SkillReleaseGuard.directoryFingerprint(dest)
        assertNotEquals(before, after) // 多了 link 条目
        // 再改外部内容，指纹应保持不变（证明没下钻读目标）
        File(outside, "important.txt").writeText("changed-again")
        assertEquals(after, SkillReleaseGuard.directoryFingerprint(dest))
    }

    @Test
    fun 执行位不参与指纹_避免每次升级误判为用户改动() {
        val dest = skillDir("exec-bit")
        val before = SkillReleaseGuard.directoryFingerprint(dest)
        File(dest, "scripts/task-start").setExecutable(true, false)
        assertEquals("APK 不保留执行位，权限不能进指纹", before, SkillReleaseGuard.directoryFingerprint(dest))
    }
}
