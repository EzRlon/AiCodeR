package com.aicode.core.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移 76（思考档位审计两列）的静态契约。
 *
 * 背景：真正的 Room 迁移测试 [MigrationTest] 走 Robolectric，在 Android PRoot 容器里会被
 * `guardEnvironment` 跳过（conscrypt native 加载失败），本容器验证不到该迁移。
 * 这里用纯 JVM 静态校验补位，钉住三处必须一致的事实：
 *  1. AgentDatabase.SCHEMA_VERSION 与迁移 x→SCHEMA_VERSION 的 Kotlin 迁移存在；
 *  2. 该迁移确实补了 reasoningWire / reasoningClampedFrom 两列；
 *  3. Room 导出的 SCHEMA_VERSION.json 里 llm_call_records 含这两列（entity 与迁移不脱节）。
 */
class ReasoningWireMigrationTest {

    private val root = File(".").absoluteFile

    private fun schemaVersion(): Int {
        val src = File(root, "src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt").readText()
        return Regex("""SCHEMA_VERSION\s*=\s*(\d+)""").find(src)!!.groupValues[1].toInt()
    }

    @Test
    fun 迁移到当前版本补上思考审计两列() {
        val version = schemaVersion()
        val idempotent = File(root, "src/main/java/com/aicode/core/db/IdempotentMigrations.kt").readText()

        assertTrue(
            "IdempotentMigrations 缺少 Migration${version - 1}To$version（迁移未接线）",
            idempotent.contains("Migration${version - 1}To$version")
        )
        assertTrue(
            "迁移 ${version - 1}→$version 应补 llm_call_records.reasoningWire 列",
            idempotent.contains("\"reasoningWire\"") && idempotent.contains("ADD COLUMN `reasoningWire`")
        )
        assertTrue(
            "迁移 ${version - 1}→$version 应补 llm_call_records.reasoningClampedFrom 列",
            idempotent.contains("\"reasoningClampedFrom\"") && idempotent.contains("ADD COLUMN `reasoningClampedFrom`")
        )
    }

    @Test
    fun `导出的当前版 schema 与 entity 一致含两列`() {
        val version = schemaVersion()
        val schema = File(
            root,
            "schemas/com.aicode.feature.agent.data.local.database.AgentDatabase/$version.json"
        )
        assertTrue("未找到导出的 schema ${version}.json（改 entity 后未重新编译导出）", schema.exists())
        val text = schema.readText()
        assertTrue("schema ${version}.json 的 llm_call_records 缺 reasoningWire 列", "\"reasoningWire\"" in text)
        assertTrue("schema ${version}.json 的 llm_call_records 缺 reasoningClampedFrom 列", "\"reasoningClampedFrom\"" in text)
    }
}
