package com.aicode.feature.store.domain

import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreKind
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StoreInstaller] 的纯逻辑单元覆盖：GitHub 仓库地址解析与 MCP 配置名 sanitize。
 * 网络与写盘依赖以 mock 注入（安装主流程由真机验收覆盖）。
 */
class StoreInstallerTest {

    private fun installer() = StoreInstaller(
        api = mockk(relaxed = true),
        skillRepository = mockk(relaxed = true),
        skillConfig = mockk(relaxed = true),
        mcpConfig = mockk(relaxed = true)
    )

    // ── parseGitHubSource ──────────────────────────────────────────

    @Test
    fun parseGitHubSource_treeUrl() {
        val src = installer().parseGitHubSource("https://github.com/NousResearch/hermes-agent/tree/main/skills/apple/apple-notes")
        assertEquals("NousResearch", src?.owner)
        assertEquals("hermes-agent", src?.repo)
        assertEquals("main", src?.ref)
        assertEquals("skills/apple/apple-notes", src?.path)
    }

    @Test
    fun parseGitHubSource_blobUrl() {
        val src = installer().parseGitHubSource("https://github.com/anthropics/skills/blob/main/pdf/SKILL.md")
        assertEquals("anthropics", src?.owner)
        assertEquals("skills", src?.repo)
        assertEquals("main", src?.ref)
        assertEquals("pdf/SKILL.md", src?.path)
    }

    @Test
    fun parseGitHubSource_stripsGitSuffix() {
        val src = installer().parseGitHubSource("https://github.com/foo/bar.git/tree/dev/a/b")
        assertEquals("bar", src?.repo)
        assertEquals("dev", src?.ref)
        assertEquals("a/b", src?.path)
    }

    @Test
    fun parseGitHubSource_nonGitHubReturnsNull() {
        assertNull(installer().parseGitHubSource("https://gitlab.com/foo/bar"))
        assertNull(installer().parseGitHubSource(""))
    }

    // ── sanitizeMcpName ────────────────────────────────────────────

    @Test
    fun sanitizeMcpName_fromTitle() {
        val item = StoreItem(id = "ac.inference.sh/mcp", kind = StoreKind.MCP, name = "GitHub MCP Server")
        assertEquals("github-mcp-server", installer().sanitizeMcpName(item))
    }

    @Test
    fun sanitizeMcpName_fallbackToIdWhenNameAllSymbols() {
        val item = StoreItem(id = "ac.inference.sh/mcp", kind = StoreKind.MCP, name = "中文名称")
        // 名称清洗后为空 → 回退源 id（点/斜杠转连字符）
        val name = installer().sanitizeMcpName(item)
        assertEquals("ac-inference-sh-mcp", name)
        assertTrue(name.isNotBlank())
    }

    @Test
    fun sanitizeMcpName_keepsValidAscii() {
        val item = StoreItem(id = "x", kind = StoreKind.MCP, name = "My_Server-2")
        assertEquals("my_server-2", installer().sanitizeMcpName(item))
    }
}
