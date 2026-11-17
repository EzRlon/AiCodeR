package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验收输入组装的行为边界。
 *
 * 这组测试补的是之前的空白：证据优先改造（todo + 工具实际返回 + 助手自述）当时只过了编译，
 * 没有单测。重点验三件事：
 * 1. 证据只挑命令执行类工具，助手自述进不了证据段（否则等于让被验收方自己出证词）；
 * 2. 两类内容的预算各自封顶、超长时按约定方向截断；
 * 3. 空的/无内容的项不占位置。
 */
class VerificationContextBuilderTest {

    private fun assistant(text: String, calls: List<ToolCall> = emptyList()) =
        AgentMessage.AssistantMessage(id = "a-${text.hashCode()}", content = text, toolCalls = calls)

    private fun toolResult(id: String, name: String, result: String) =
        AgentMessage.ToolResultMessage(id = id, toolName = name, result = result)

    private fun call(id: String, name: String, vararg args: Pair<String, String>) =
        ToolCall(id = id, name = name, arguments = args.associate { it.first to JsonPrimitive(it.second) })

    // ── 证据段 ────────────────────────────────────────────────────

    @Test
    fun `只有命令类工具进证据段`() {
        val messages = listOf(
            assistant("我去跑了构建", listOf(call("c1", "Bash", "command" to "gradlew build"))),
            toolResult("c1", "Bash", "BUILD SUCCESSFUL"),
            assistant("我读了文件", listOf(call("c2", "readFile", "path" to "a.kt"))),
            toolResult("c2", "readFile", "文件内容……")
        )

        val evidence = VerificationContextBuilder.buildEvidence(messages)

        assertTrue("命令返回应进证据", evidence.contains("BUILD SUCCESSFUL"))
        assertFalse("读文件不是硬证据，不该进", evidence.contains("文件内容"))
    }

    @Test
    fun `证据带上命令原文`() {
        val messages = listOf(
            assistant("", listOf(call("c1", "Bash", "command" to "gradlew test"))),
            toolResult("c1", "Bash", "ok")
        )

        val evidence = VerificationContextBuilder.buildEvidence(messages)

        assertTrue("证据要连命令一起给出，否则验收员不知道跑的是什么", evidence.contains("gradlew test"))
    }

    @Test
    fun `子代理报告标为报告而非命令`() {
        val messages = listOf(
            toolResult("t1", "task", "子代理说：全部通过")
        )

        val evidence = VerificationContextBuilder.buildEvidence(messages)

        assertTrue(evidence.contains("子代理报告"))
    }

    @Test
    fun `超长命令输出保留头尾`() {
        val long = "HEAD-" + "x".repeat(2000) + "-TAIL"
        val messages = listOf(toolResult("c1", "Bash", long))

        val evidence = VerificationContextBuilder.buildEvidence(messages)

        assertTrue("开头应保留（回显）", evidence.contains("HEAD-"))
        assertTrue("结尾应保留（成败判定）", evidence.contains("-TAIL"))
        assertTrue("中间应被截断标记", evidence.contains("…"))
        assertFalse("不该把 2000 字符全塞进去", evidence.contains("x".repeat(1500)))
    }

    @Test
    fun `证据总量封顶`() {
        // 20 条各 800 字符的命令结果，总量远超 EVIDENCE_MAX_CHARS
        val messages = (1..20).map { i ->
            toolResult("c$i", "Bash", "r$i-" + "y".repeat(790))
        }

        val evidence = VerificationContextBuilder.buildEvidence(messages)

        assertTrue(
            "证据段不能超过总预算（否则会挤掉对话段）",
            evidence.length <= VerificationContextBuilder.EVIDENCE_MAX_CHARS
        )
    }

    @Test
    fun `超过条数上限时取最近的`() {
        // 造 10 条命令结果，上限是 6 条，应只留最后 6 条
        val messages = (1..10).map { i -> toolResult("c$i", "Bash", "结果$i") }

        val evidence = VerificationContextBuilder.buildEvidence(messages)

        assertTrue("最近的应保留", evidence.contains("结果10"))
        assertFalse("最早的应被裁掉", evidence.contains("结果1\n"))
    }

    @Test
    fun `空结果不进证据`() {
        val messages = listOf(
            toolResult("c1", "Bash", ""),
            toolResult("c2", "Bash", "有内容")
        )

        val evidence = VerificationContextBuilder.buildEvidence(messages)

        assertTrue(evidence.contains("有内容"))
        assertEquals("空结果不该产生条目", 1, evidence.split("--- ").size - 1)
    }

    @Test
    fun `没有工具调用时证据为空`() {
        val messages = listOf(assistant("我只说了一句话"))

        assertEquals("", VerificationContextBuilder.buildEvidence(messages))
    }

    // ── 对话段 ────────────────────────────────────────────────────

    @Test
    fun `对话段只收用户与助手文本`() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "帮我构建"),
            assistant("好的"),
            toolResult("c1", "Bash", "构建成功")
        )

        val conversation = VerificationContextBuilder.buildConversation(messages)

        assertTrue(conversation.contains("帮我构建"))
        assertTrue(conversation.contains("好的"))
        assertFalse("工具返回应走证据段，不在对话段重复", conversation.contains("构建成功"))
    }

    @Test
    fun `对话段跳过空内容消息`() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "  "),
            assistant("有内容")
        )

        val conversation = VerificationContextBuilder.buildConversation(messages)

        assertFalse("空白消息不该占位", conversation.contains("用户："))
        assertTrue(conversation.contains("助手：有内容"))
    }

    @Test
    fun `对话段超长时保留尾部`() {
        // 每条 1000 字符上限，造 30 条 → 远超 7000 上限，应保留最近的
        val messages = (1..30).map { i -> AgentMessage.UserMessage(content = "消息$i-" + "z".repeat(1000)) }

        val conversation = VerificationContextBuilder.buildConversation(messages)

        assertTrue(
            "对话段不能超过预算",
            conversation.length <= VerificationContextBuilder.CONVERSATION_MAX_CHARS
        )
        assertTrue("应保留最近的对话", conversation.contains("消息30"))
        assertFalse("最早的应被裁掉", conversation.contains("消息1-"))
    }

    @Test
    fun `对话段单条超长时截断`() {
        val messages = listOf(AgentMessage.UserMessage(content = "A".repeat(5000)))

        val conversation = VerificationContextBuilder.buildConversation(messages)

        // 正文被截到 MESSAGE_MAX_CHARS，另加「用户：」前缀与行尾换行
        assertTrue(
            "正文部分应被截到 $${VerificationContextBuilder.MESSAGE_MAX_CHARS} 字符以内，实际 $${conversation.length}",
            conversation.count { it == 'A' } <= VerificationContextBuilder.MESSAGE_MAX_CHARS
        )
        assertTrue("不该把 5000 字全带进去", conversation.length < 1100)
    }
}
