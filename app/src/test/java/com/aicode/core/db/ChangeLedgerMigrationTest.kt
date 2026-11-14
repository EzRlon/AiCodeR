package com.aicode.core.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移 77（改动账本表）的静态契约。
 *
 * 与 [ReasoningWireMigrationTest] 同因：真正的 Room 迁移测试走 Robolectric，在本容器会被
 * `guardEnvironment` 跳过，故用纯 JVM 静态校验钉住三处必须一致的事实。
 */
class ChangeLedgerMigrationTest {

    private val root = File(".").absoluteFile

    private fun schemaVersion(): Int {
        val src = File(root, "src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt").readText()
        return Regex("""SCHEMA_VERSION\s*=\s*(\d+)""").find(src)!!.groupValues[1].toInt()
    }

    @Test
    fun `迁移到当前版本建出改动账本表`() {
        val version = schemaVersion()
        val idempotent = File(root, "src/main/java/com/aicode/core/db/IdempotentMigrations.kt").readText()

        assertTrue(
            "IdempotentMigrations 缺少 Migration${version - 1}To$version（迁移未接线）",
            idempotent.contains("Migration${version - 1}To$version")
        )
        assertTrue(
            "迁移 ${version - 1}→$version 应建 change_ledger 表",
            idempotent.contains("CREATE TABLE IF NOT EXISTS `change_ledger`")
        )
    }

    @Test
    fun `导出的当前版 schema 含 change_ledger 且与 entity 一致`() {
        val version = schemaVersion()
        val schema = File(
            root,
            "schemas/com.aicode.feature.agent.data.local.database.AgentDatabase/$version.json"
        )
        assertTrue("未找到导出的 schema ${version}.json（改 entity 后未重新编译导出）", schema.exists())
        val text = schema.readText()
        assertTrue("schema 缺 change_ledger 表", "\"change_ledger\"" in text)
        listOf("workspacePath", "humanSummary", "agentDetail", "evidence", "verifiedAt").forEach { col ->
            assertTrue("schema ${version}.json 的 change_ledger 缺列 $col", "\"columnName\": \"$col\"" in text)
        }
    }
}
