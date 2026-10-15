package com.aicode.feature.store.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商店 DTO 解析与统一模型映射（以两个源的真实响应结构为样例）。
 */
class StoreModelsTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun cmSkillsResponse_parses() {
        val raw = """
            {"count":3,"total":810,"limit":3,"offset":0,"results":[
              {"slug":"built-in-apple-notes","name":"Apple Notes","description":"Manage notes",
               "category":"apple","source":"built-in","tags":["Notes"],"author":"Hermes","version":"1.0.0",
               "license":"MIT","url":"https://www.claudemarket.ai/skills/hermes/built-in-apple-notes"}
            ]}
        """.trimIndent()
        val resp = json.decodeFromString<CmSkillsResponse>(raw)
        assertEquals(810, resp.total)
        val item = resp.results.first().toStoreItem()
        assertEquals(StoreKind.SKILL, item.kind)
        assertEquals("built-in-apple-notes", item.id)
        assertEquals("Apple Notes", item.name)
        assertTrue(item.detailUrl.startsWith("https://"))
    }

    @Test
    fun cmSkillDetail_parsesRepoUrl() {
        val raw = """
            {"slug":"x","name":"X","description":"d","category":"c","tags":[],"platforms":["macos"],
             "author":"a","version":"1.0.0","license":"MIT",
             "docsUrl":"https://example.com/docs",
             "repoUrl":"https://github.com/NousResearch/hermes-agent/tree/main/skills/apple/apple-notes",
             "url":"https://www.claudemarket.ai/skills/hermes/x"}
        """.trimIndent()
        val detail = json.decodeFromString<CmSkillDetail>(raw)
        assertEquals("https://github.com/NousResearch/hermes-agent/tree/main/skills/apple/apple-notes", detail.repoUrl)
    }

    @Test
    fun cmSearchResponse_filtersByTypeField() {
        val raw = """
            {"query":"git","type":"all","count":2,"results":[
              {"type":"mcp_server","name":"GitHub MCP","description":"d","url":"https://www.claudemarket.ai/mcp/github/x","category":"Search","popularity":30943},
              {"type":"skill","name":"Git Helper","description":"d","url":"https://www.claudemarket.ai/skills/a/git-helper","category":"dev","popularity":12}
            ]}
        """.trimIndent()
        val resp = json.decodeFromString<CmSearchResponse>(raw)
        val skills = resp.results.filter { it.type == "skill" }
        assertEquals(1, skills.size)
        assertEquals("git-helper", skills.first().url.substringAfterLast('/'))
    }

    @Test
    fun mcpRegistryResponse_parsesRemotesAndCursor() {
        val raw = """
            {"servers":[
              {"server":{"name":"ac.inference.sh/mcp","description":"Run AI apps","title":"inference.sh",
                "version":"1.0.0","remotes":[{"type":"streamable-http","url":"https://sh.inference.ac"}]}},
              {"server":{"name":"io.example/local","description":"local only","title":"Local",
                "version":"0.1.0","remotes":[]}}
            ],"metadata":{"next_cursor":"abc","count":2}}
        """.trimIndent()
        val resp = json.decodeFromString<McpRegistryResponse>(raw)
        assertEquals("abc", resp.nextCursorOrNull())

        val remote = resp.servers[0].server.toStoreItem()
        assertEquals(StoreKind.MCP, remote.kind)
        assertEquals("https://sh.inference.ac", remote.remoteUrl)
        assertFalse(remote.stdioOnly)

        val local = resp.servers[1].server.toStoreItem()
        assertNull(local.remoteUrl)
        assertTrue(local.stdioOnly)
    }

    @Test
    fun mcpRegistryResponse_nullCursor() {
        val resp = json.decodeFromString<McpRegistryResponse>("""{"servers":[]}""")
        assertNull(resp.nextCursorOrNull())
    }
}
