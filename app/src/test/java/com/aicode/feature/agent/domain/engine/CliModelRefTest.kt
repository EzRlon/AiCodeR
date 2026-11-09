package com.aicode.feature.agent.domain.engine

import com.aicode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLI 模型引用与供应商键的生成规则。
 *
 * 这些是三个外部引擎的「参数值生成器」，bug 一旦出在这里，表现是引擎起不来或模型不存在——
 * 而且很难从现象反推回原因。三处最容易错的：
 *
 * 1. **模型名前缀剥离**：不能盲目按 `/` 切，`z-ai/glm-4` 这类是真实模型 id；
 * 2. **供应商键**：中文名/特殊字符必须转成 ASCII，且不同供应商不能撞键；
 * 3. **官方端点判定**：判定错会导致自建中转被当成官方，凭据注入到错的地方。
 */
class CliModelRefTest {

    // ── bareModelName：前缀剥离 ──────────────────────────────────

    @Test
    fun `供应商名前缀被剥离`() {
        assertEquals(
            "glm-4.6",
            CliModelRef.bareModelName("智谱/glm-4.6", providerName = "智谱", providerId = "zhipu")
        )
    }

    @Test
    fun `供应商 id 前缀被剥离`() {
        assertEquals(
            "gpt-5",
            CliModelRef.bareModelName("openai/gpt-5", providerName = null, providerId = "openai")
        )
    }

    @Test
    fun `真实含斜杠的模型 id 不被剥离`() {
        // z-ai/glm-5.3-flash 是模型自身的 id，不是供应商前缀
        assertEquals(
            "z-ai/glm-5.3-flash",
            CliModelRef.bareModelName("z-ai/glm-5.3-flash", providerName = "某供应商", providerId = "custom")
        )
    }

    @Test
    fun `deepseek-ai 前缀不被误剥离`() {
        assertEquals(
            "deepseek-ai/DeepSeek-V3",
            CliModelRef.bareModelName("deepseek-ai/DeepSeek-V3", providerName = "DeepSeek", providerId = "deepseek")
        )
    }

    @Test
    fun `无前缀的模型名原样返回`() {
        assertEquals(
            "claude-sonnet-5-5",
            CliModelRef.bareModelName("claude-sonnet-5-5", providerName = "Anthropic", providerId = "anthropic")
        )
    }

    @Test
    fun `供应商名为空时不移除前缀`() {
        assertEquals(
            "anything/here",
            CliModelRef.bareModelName("anything/here", providerName = "", providerId = "  ")
        )
    }

    // ── providerKey：ASCII 安全与唯一性 ──────────────────────────

    @Test
    fun `中文供应商名转成 ASCII 键`() {
        val key = CliModelRef.providerKey("智谱", "zhipu")

        assertTrue("键必须是 ASCII（TOML 段名与 CLI 参数都要求）", key.all { it.code < 128 })
        // 纯中文名逐字符转下划线后被 trim 清空，落到 app_ 兜底——这是正确行为，
        // 不能产出 "_xxx" 这种只有哈希没语义的键
        assertTrue(key.startsWith("app_"))
    }

    @Test
    fun `中英混合名保留英文部分`() {
        val key = CliModelRef.providerKey("智谱AI", "zhipu")

        assertTrue("英文部分应保留下来当语义前缀，实际 $key", key.startsWith("ai_"))
    }

    @Test
    fun `ASCII 供应商名保留字母数字`() {
        val key = CliModelRef.providerKey("MyProvider", "id1")

        assertTrue(key.startsWith("myprovider_"))
    }

    @Test
    fun `同名不同 id 的供应商键不冲突`() {
        val a = CliModelRef.providerKey("中转站", "id-a")
        val b = CliModelRef.providerKey("中转站", "id-b")

        assertFalse("同名供应商必须靠 id 哈希区分，否则配置互相覆盖", a == b)
    }

    @Test
    fun `全特殊字符的供应商名有兜底`() {
        val key = CliModelRef.providerKey("!!!", "id")

        assertTrue("空 base 要有兜底名，不能产出 '_xxx' 这种无意义键", key.startsWith("app_"))
    }

    @Test
    fun `超长供应商名被截断`() {
        val key = CliModelRef.providerKey("a".repeat(100), "id")

        // base 限 24 字符 + 下划线 + 哈希
        assertTrue("键过长会让 CLI 参数不可读", key.length <= 24 + 1 + 8)
    }

    // ── isOfficialBase：官方端点判定 ─────────────────────────────

    @Test
    fun `空 baseUrl 视为官方`() {
        assertTrue(CliModelRef.isOfficialBase(ProviderType.ANTHROPIC, ""))
        assertTrue(CliModelRef.isOfficialBase(ProviderType.OPENAI, "   "))
    }

    @Test
    fun `官方域名判定为官方`() {
        assertTrue(CliModelRef.isOfficialBase(ProviderType.ANTHROPIC, "https://api.anthropic.com"))
        assertTrue(CliModelRef.isOfficialBase(ProviderType.OPENAI, "https://api.openai.com/v1"))
    }

    @Test
    fun `自建中转判定为非官方`() {
        assertFalse(CliModelRef.isOfficialBase(ProviderType.OPENAI, "https://my-relay.example.com/v1"))
        assertFalse(CliModelRef.isOfficialBase(ProviderType.ANTHROPIC, "https://relay.local"))
    }

    @Test
    fun `末尾斜杠与 v1 后缀不影响判定`() {
        assertTrue(CliModelRef.isOfficialBase(ProviderType.OPENAI, "https://api.openai.com/v1/"))
        assertTrue(CliModelRef.isOfficialBase(ProviderType.OPENAI, "  https://api.openai.com/v1  "))
    }

    // ── opencodeModelRef：拼接 ───────────────────────────────────

    @Test
    fun `官方端点用内置 provider 前缀`() {
        assertEquals(
            "openai/gpt-5",
            CliModelRef.opencodeModelRef(
                providerName = "OpenAI",
                providerId = "openai",
                type = ProviderType.OPENAI,
                baseUrl = "",
                model = "gpt-5"
            )
        )
    }

    @Test
    fun `Anthropic 官方端点用 anthropic 前缀`() {
        assertEquals(
            "anthropic/claude-sonnet-5-5",
            CliModelRef.opencodeModelRef(
                providerName = "Anthropic",
                providerId = "anthropic",
                type = ProviderType.ANTHROPIC,
                baseUrl = "https://api.anthropic.com",
                model = "claude-sonnet-5-5"
            )
        )
    }

    @Test
    fun `自建端点用生成的 provider 键`() {
        val ref = CliModelRef.opencodeModelRef(
            providerName = "我的中转",
            providerId = "myrelay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
            model = "some-model"
        )

        val expectedKey = CliModelRef.providerKey("我的中转", "myrelay")
        assertEquals("$expectedKey/some-model", ref)
    }

    @Test
    fun `自定义 provider 段键与模型引用同源`() {
        // 两者必须一致，否则 -m 指向的段在配置里不存在
        val name = "中转站A"
        val id = "relay-a"
        val ref = CliModelRef.opencodeModelRef(
            providerName = name,
            providerId = id,
            type = ProviderType.OPENAI,
            baseUrl = "https://x.example.com",
            model = "m"
        )

        assertEquals(CliModelRef.providerKey(name, id), ref.substringBefore('/'))
    }
}
