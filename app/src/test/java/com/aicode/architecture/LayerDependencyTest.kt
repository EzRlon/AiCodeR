package com.aicode.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分层架构守卫：core（基础设施层）不得反向依赖 feature（业务层）。
 *
 * 例外只有 composition root：di/ 负责把 feature 实现装配给 core，天然要认识两边。
 * 已知历史债务用 [KNOWN_DEBT] 显式登记——新增违规会立刻失败，清偿后请同步删除条目。
 */
class LayerDependencyTest {

    private companion object {
        /** 已知 core→feature 依赖（历史债务，待逐步清偿）。 */
        val KNOWN_DEBT = setOf(
            "watch/FileChangeHub.kt"
        )

        val FEATURE_IMPORT = Regex("""^\s*import\s+com\.aicode\.feature\.""", RegexOption.MULTILINE)
    }

    private fun coreDir(): File {
        // Gradle 单测工作目录为模块根（app/）；兼容从仓库根运行的场景。
        val module = File("src/main/java/com/aicode/core")
        return if (module.exists()) module else File("app/src/main/java/com/aicode/core")
    }

    @Test
    fun `core 不得 import feature 包`() {
        val root = coreDir()
        assertTrue("找不到 core 源码目录: ${root.absolutePath}", root.isDirectory)

        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val hasFeatureImport = FEATURE_IMPORT.containsMatchIn(file.readText())
                if (hasFeatureImport && rel !in KNOWN_DEBT) "core/$rel" else null
            }
            .sorted()
            .toList()

        assertTrue(
            "core 层出现新的 feature 依赖（应下沉到 core 或用依赖倒置）：\n" +
                violations.joinToString("\n") { "  - $it" },
            violations.isEmpty()
        )
    }
}
