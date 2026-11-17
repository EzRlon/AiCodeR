package com.aicode.core.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MigrationLoader 排除列表契约（纯 JVM，读源码静态校验，工作目录 = 模块根 app/）。
 *
 * 背景：62 起的迁移改用幂等 Kotlin 版执行（[MigrationLoader.loadMigrations] 尾部注册），
 * 同名 .sql 文件靠 `removeAll` 排除列表挡住，避免同一 (start,end) 出现两条迁移
 * （Room 会打 "Overriding migration" 警告并依赖后注册者生效，行为脆弱）。
 *
 * 回归点：新增 Kotlin 迁移（如 Migration74To75）时忘了把排除列表的 `it.version == 75` 一起补上
 * ——2026-11-10 的 75 号就漏过一次。此测试钉住「Kotlin 注册的每个版本必须在排除列表里」。
 */
class MigrationLoaderExclusionTest {

    @Test
    fun kotlin_migrations_must_be_covered_by_file_migration_exclusion_list() {
        val source = File("src/main/java/com/aicode/core/db/MigrationLoader.kt").readText()
        val removeAllLine = source.lineSequence().firstOrNull { "removeAll" in it }
            ?: error("MigrationLoader 里找不到 FileMigration 排除列表行")

        val excluded = Regex("""it\.version == (\d+)""").findAll(removeAllLine)
            .map { it.groupValues[1].toInt() }
            .toSet()

        val kotlinVersions = Regex("""migrations\.add\(Migration\d+To(\d+)\(\)\)""").findAll(source)
            .map { it.groupValues[1].toInt() }
            .toSet()
        assertTrue("MigrationLoader 里没解析到任何 Kotlin 迁移注册", kotlinVersions.isNotEmpty())

        val missing = kotlinVersions - excluded
        assertTrue(
            "这些版本的 Kotlin 迁移与 .sql FileMigration 重复加载（排除列表没覆盖，Room 会警告 Overriding migration）: $missing",
            missing.isEmpty()
        )
    }
}
