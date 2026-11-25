package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.workflow.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh headless `--json` NDJSON 事件流 → AgentEvent 翻译的单元测试。
 * 事件结构按 @deepseek-ai/dsh 0.2.2-rc.5 实测校准（phase=turn_start…、final.text、
 * reason.error.message、未知 --session-id 报 "does not exist"）。
 */
class DeepSeekEventTranslatorTest {

    private val translator = DeepSeekEventTranslator()
    private val acc = translator.turnAccumulator("app-session-1")

    @Test
    fun `session 事件记录 threadId 供续接映射`() {
        val events = translator.parseLine(
            """{"type":"session","sessionId":"session-5334205e-9f5c-4f95-a6f2-ec259dc03468","cwd":"/tmp"}""",
            acc
        )
        assertTrue(events.isEmpty())
        assertEquals("session-5334205e-9f5c-4f95-a6f2-ec259dc03468", acc.threadId)
    }

    @Test
    fun `tool_call 与 tool_result 按 id 配对生成 Started 与 Finished`() {
        val started = translator.parseLine(
            """{"type":"tool_call","id":"t1","name":"mcp__aicode_bridge__send_file","args":{"path":"/tmp/x"}}""",
            acc
        )
        assertEquals(1, started.size)
        val s = started[0] as AgentEvent.ToolCallStarted
        assertEquals("t1", s.id)
        assertEquals("mcp__aicode_bridge__send_file", s.toolName)
        assertTrue(s.argsPreview.contains("/tmp/x"))

        val finished = translator.parseLine(
            """{"type":"tool_result","id":"t1","status":"completed","result":"ok"}""",
            acc
        )
        assertEquals(1, finished.size)
        val f = finished[0] as AgentEvent.ToolCallFinished
        assertEquals("t1", f.id)
        assertEquals("mcp__aicode_bridge__send_file", f.toolName)
        assertEquals("ok", f.result)
        assertEquals(false, f.isError)
    }

    @Test
    fun `tool_result 缺 id 时按 FIFO 配对最近的 tool_call`() {
        translator.parseLine("""{"type":"tool_call","id":"a","name":"bash"}""", acc)
        translator.parseLine("""{"type":"tool_call","id":"b","name":"edit"}""", acc)
        val first = translator.parseLine(
            """{"type":"tool_result","status":"error","error":{"name":"ExecError","reason":"boom"}}""",
            acc
        )
        val f = first[0] as AgentEvent.ToolCallFinished
        assertEquals("a", f.id)
        assertEquals(true, f.isError)
        assertEquals("boom", f.result)
    }

    @Test
    fun `thinking 累积为 reasoning 增量 text 事件携带累积并清空`() {
        translator.parseLine("""{"type":"thinking","text":"先看结构"}""", acc)
        translator.parseLine("""{"type":"thinking","text":"再改代码"}""", acc)
        assertEquals("先看结构再改代码", acc.reasoning.toString())

        val events = translator.parseLine("""{"type":"text","text":"改完了"}""", acc)
        assertEquals(1, events.size)
        val t = events[0] as AgentEvent.AssistantText
        assertEquals("改完了", t.content)
        assertEquals("先看结构再改代码", t.reasoning)
        assertEquals("", acc.reasoning.toString())
    }

    @Test
    fun `final reason completed 产生 Completed 且无重复文本`() {
        translator.parseLine("""{"type":"text","text":"答案正文"}""", acc)
        val events = translator.parseLine(
            """{"type":"final","sessionId":"session-1","reason":{"kind":"completed"},"text":"答案正文"}""",
            acc
        )
        // text 已发过：final 不再补发 answer，只发 Completed
        assertEquals(1, events.size)
        assertEquals(AgentEvent.Completed, events[0])
    }

    @Test
    fun `整轮无 text 时 final text 兜底补发 AssistantText`() {
        val events = translator.parseLine(
            """{"type":"final","sessionId":"session-1","reason":{"kind":"completed"},"text":"短回合答案"}""",
            acc
        )
        assertEquals(2, events.size)
        val t = events[0] as AgentEvent.AssistantText
        assertEquals("短回合答案", t.content)
        assertEquals(AgentEvent.Completed, events[1])
    }

    @Test
    fun `final error 提取 reason error message 并增强为可操作引导`() {
        val events = translator.parseLine(
            """{"type":"final","sessionId":"s","reason":{"kind":"error","error":{"code":"MISSING_CREDENTIAL","name":"AuthError","message":"no API key for provider route"}},"text":""}""",
            acc
        )
        assertEquals(1, events.size)
        val f = events[0] as AgentEvent.Failed
        assertTrue(f.error.contains("认证失败"))
        assertTrue(f.error.contains("DEEPSEEK_API_KEY"))
        assertTrue(f.error.contains("MISSING_CREDENTIAL"))
    }

    @Test
    fun `error 事件文本进入 lastError 供 EOF 分类兜底`() {
        translator.parseLine("""{"type":"error","message":"session \"x\" does not exist"}""", acc)
        assertEquals("""session "x" does not exist""", acc.lastError)
    }

    @Test
    fun `未知 session 的 EOF 分类识别 does not exist 并转可重试异常`() {
        val e = translator.classifyEarlyExit(
            """dsh: session "ai-test-abc123" does not exist; omit --session-id to start a new Session"""
        )
        assertTrue(e is ResumeSessionMissingException)
    }

    @Test
    fun `preset 不匹配的续接失败同样转可重试异常`() {
        val e = translator.classifyEarlyExit("permission preset mismatch with original session")
        assertTrue(e is ResumeSessionMissingException)
    }

    @Test
    fun `无凭据 EOF 分类给出认证引导`() {
        val e = translator.classifyEarlyExit("MISSING_CREDENTIAL: no API key for provider route")
        assertTrue(e.message!!.contains("认证失败"))
        assertTrue(e.message!!.contains("DEEPSEEK_API_KEY"))
    }

    @Test
    fun `非 NDJSON 行被静默忽略`() {
        assertTrue(translator.parseLine("not json at all", acc).isEmpty())
        assertTrue(translator.parseLine("""["array"]""", acc).isEmpty())
    }

    @Test
    fun `status 事件的多字段名用量探测`() {
        val ok = translator.parseLine(
            """{"type":"status","phase":"step_start","usage":{"input_tokens":100,"output_tokens":20,"cached_input_tokens":50}}""",
            acc
        )
        assertTrue(ok.isEmpty())
        val usage = requireNotNull(acc.pendingUsage)
        assertEquals(100, usage.inputTokens)
        assertEquals(20, usage.outputTokens)
        assertEquals(50, usage.cachedInputTokens)

        val none = translator.parseLine("""{"type":"status","phase":"step_end"}""", acc)
        assertTrue(none.isEmpty())
    }
}
