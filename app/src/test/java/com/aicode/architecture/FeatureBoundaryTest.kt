package com.aicode.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * feature 边界守卫（棘轮）：feature/presentation 不得新增跨 feature 的 import。
 *
 * 现状：UI 组合仍存在跨 feature 依赖（如 settings 组装 backup/editor 的页面区块），
 * 完整治理需要独立 UI 层，属长期重构。这里采用「冻结基线」策略——
 * app/src/test/resources/feature-boundary-baseline.txt 记录现存条目，
 * 新增条目立即失败；条目被修复后基线可删除，只允许收缩不允许扩张。
 */
class FeatureBoundaryTest {

    private companion object {
        const val BASELINE_RESOURCE = "/feature-boundary-baseline.txt"
        val IMPORT_PATTERN = Regex("""^import com\.aicode\.feature\.(\w+)\.presentation.*$""", RegexOption.MULTILINE)
    }

    @Test
    fun `不得新增跨 feature presentation 依赖`() {
        val featureRoot = locate("src/main/java/com/aicode/feature", "app/src/main/java/com/aicode/feature")
        val baseline = readBaseline()

        val current = sortedSetOf<String>()
        featureRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val owner = file.relativeTo(featureRoot).invariantSeparatorsPath.substringBefore('/')
                IMPORT_PATTERN.findAll(file.readText()).forEach { match ->
                    val target = match.groupValues[1]
                    if (target != owner) {
                        val rel = "app/src/main/java/com/aicode/feature/" +
                            file.relativeTo(featureRoot).invariantSeparatorsPath
                        current += "$rel|${match.value.trim()}"
                    }
                }
            }

        val added = (current - baseline).sorted()
        assertTrue(
            "出现新的跨 feature presentation 依赖（应下沉 core 或通过抽象解耦）：\n" +
                added.joinToString("\n") { "  + $it" },
            added.isEmpty()
        )
    }

    private fun locate(vararg candidates: String): File {
        for (c in candidates) {
            val f = File(c)
            if (f.isDirectory) return f
        }
        error("找不到 feature 源码目录，当前工作目录=${File(".").absolutePath}")
    }

    private fun readBaseline(): Set<String> {
        val stream = javaClass.getResourceAsStream(BASELINE_RESOURCE)
            ?: return emptySet()
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        }
    }
}
