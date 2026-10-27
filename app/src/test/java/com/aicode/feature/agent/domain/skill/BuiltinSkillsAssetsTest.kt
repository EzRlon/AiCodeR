package com.aicode.feature.agent.domain.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置技能资产静态校验（纯 JVM，工作目录 = 模块根 app/）。
 *
 * 内置技能随 App 分发给所有用户，写坏的 frontmatter 不会报错，只会让技能描述在清单里静默变空、
 * 该技能从此不被 AI 识别到用途。这里在打包前把这类错误拦下来。
 */
class BuiltinSkillsAssetsTest {

    private val skillsDir = File("src/main/assets/skills")

    private fun skillDirs(): List<File> =
        skillsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun 每个内置技能都有可解析的SKILL_md与名称() {
        assertTrue("找不到内置技能目录 ${skillsDir.absolutePath}", skillsDir.isDirectory)
        val dirs = skillDirs()
        assertTrue("内置技能目录为空", dirs.isNotEmpty())

        for (dir in dirs) {
            val file = File(dir, "SKILL.md")
            assertTrue("${dir.name} 缺少 SKILL.md", file.isFile)

            val skill = SkillParser.parseText(file.readText(), dir.name)
            assertEquals("${dir.name} 的 frontmatter name 应与目录名一致", dir.name, skill.name)
            assertTrue("${dir.name} 的 description 为空，AI 无法判断何时使用", skill.description.isNotBlank())
            assertTrue("${dir.name} 的正文为空", skill.instructions.isNotBlank())
        }
    }
}
