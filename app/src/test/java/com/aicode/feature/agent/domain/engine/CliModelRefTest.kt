package com.aicode.feature.agent.domain.engine

import com.aicode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CliModelRefTest {

    @Test
    fun `official base uses builtin provider key`() {
        val ref = CliModelRef.opencodeModelRef("官方", "p1", ProviderType.OPENAI, "https://api.openai.com/v1", "gpt-4o")
        assertEquals("openai/gpt-4o", ref)
        val ant = CliModelRef.opencodeModelRef("官方", "p1", ProviderType.ANTHROPIC, "", "claude-x")
        assertEquals("anthropic/claude-x", ant)
    }

    @Test
    fun `custom base uses slug key with id hash`() {
        val ref = CliModelRef.opencodeModelRef("我的反代", "abc", ProviderType.OPENAI, "https://proxy.example.com/v1", "gpt-4o")
        assertEquals("${CliModelRef.providerKey("我的反代", "abc")}/gpt-4o", ref)
    }

    @Test
    fun `provider key is ascii safe and collision free for non-ascii names`() {
        val a = CliModelRef.providerKey("中文名", "id-1")
        val b = CliModelRef.providerKey("中文名", "id-2")
        assertNotEquals(a, b)
        assertEquals(a, CliModelRef.providerKey("中文名", "id-1"))
        assertEquals(a, a.map { if (it.code < 128) it else '?' }.joinToString(""))
    }

    @Test
    fun `v1 suffix and trailing slash ignored in official check`() {
        assertEquals(true, CliModelRef.isOfficialBase(ProviderType.OPENAI, "https://api.openai.com/v1/"))
        assertEquals(false, CliModelRef.isOfficialBase(ProviderType.OPENAI, "https://api.deepseek.com/v1"))
    }
}
