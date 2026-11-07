package com.aicode.feature.agent.domain.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatGatewayTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `anthropic 请求翻译为 openai 请求`() {
        val anthropic = json.parseToJsonElement("""
            {
              "model": "glm-5.2", "max_tokens": 8192, "stream": true,
              "temperature": 0.7, "stop_sequences": ["END"],
              "system": [{"type":"text","text":"你是助手"}],
              "messages": [
                {"role":"user","content":"查一下天气"},
                {"role":"assistant","content":[
                    {"type":"text","text":"我来调用工具"},
                    {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"北京"}}
                ]},
                {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"toolu_1","content":"晴 25 度"},
                    {"type":"text","text":"谢谢"}
                ]}
              ],
              "tools": [{"name":"get_weather","description":"查天气","input_schema":{"type":"object"}}]
            }
        """).jsonObject
        val o = OpenAiCompatGateway.buildOpenAiRequest(anthropic).toString().let { json.parseToJsonElement(it).jsonObject }
        assertEquals("glm-5.2", o["model"]!!.jsonPrimitive.content)
        val messages = o["messages"]!!.jsonArray
        // system + user + assistant(tool_calls) + tool + user(text)
        assertEquals(5, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你是助手", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("我来调用工具", assistant["content"]!!.jsonPrimitive.content)
        val toolCalls = assistant["tool_calls"]!!.jsonArray
        assertEquals("get_weather", toolCalls[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val toolMsg = messages[3].jsonObject
        assertEquals("tool", toolMsg["role"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("晴 25 度", toolMsg["content"]!!.jsonPrimitive.content)
        assertEquals("谢谢", messages[4].jsonObject["content"]!!.jsonPrimitive.content)
        val tools = o["tools"]!!.jsonArray
        assertEquals("get_weather", tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val stop = o["stop"]!!.jsonArray
        assertEquals("END", stop[0].jsonPrimitive.content)
    }

    @Test
    fun `tool_choice any 映射为 required`() {
        val anthropic = json.parseToJsonElement("""
            {"model":"m","max_tokens":10,"tool_choice":{"type":"tool","name":"run"},
             "messages":[{"role":"user","content":"hi"}]}
        """).jsonObject
        val o = OpenAiCompatGateway.buildOpenAiRequest(anthropic).toString().let { json.parseToJsonElement(it).jsonObject }
        assertEquals("run", o["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai 非流式响应映射为 anthropic message`() {
        val openAi = json.parseToJsonElement("""
            {
              "id":"chatcmpl-1","model":"glm-5.2",
              "choices":[{"finish_reason":"tool_calls","message":{
                  "content":"好的",
                  "tool_calls":[{"id":"call_1","type":"function",
                      "function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}}]}}],
              "usage":{"prompt_tokens":10,"completion_tokens":20}
            }
        """).jsonObject
        val a = OpenAiCompatGateway.mapNonStreamResponse(openAi).toString().let { json.parseToJsonElement(it).jsonObject }
        assertEquals("message", a["type"]!!.jsonPrimitive.content)
        val content = a["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("好的", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("tool_use", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", content[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("北京", content[1].jsonObject["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals("tool_use", a["stop_reason"]!!.jsonPrimitive.content)
        assertEquals(10, a["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(20, a["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `sse 流式翻译产生完整 anthropic 事件序列`() {
        val state = OpenAiCompatGateway.SseState()
        fun feed(data: String) = OpenAiCompatGateway.translateSseLine(state, "data: $data") ?: emptyList()
        fun names(lines: List<String>) = lines.filter { it.startsWith("event: ") }.map { it.removePrefix("event: ") }

        val all = mutableListOf<String>()
        all += names(feed("""{"model":"m","choices":[{"delta":{"role":"assistant"}}]}"""))
        all += names(feed("""{"choices":[{"delta":{"content":"你好"}}]}"""))
        all += names(feed("""{"choices":[{"delta":{"content":"世界"}}]}"""))
        all += names(feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"run","arguments":"{\"x\":"}}]}}]}"""))
        all += names(feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}"""))
        all += names(feed("""{"choices":[{"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":9}}"""))
        all += names(feed("[DONE]"))

        assertEquals(
            listOf("message_start", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop"),
            all
        )
        // 校验状态机收尾（无前置文本时工具块从 index 0 起）+ 并行工具调用按 index 切块
        val st = OpenAiCompatGateway.SseState()
        st.translate(json.parseToJsonElement("""{"model":"m","choices":[{"delta":{"role":"assistant"}}]}""").jsonObject)
        st.translate(json.parseToJsonElement("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"run","arguments":"{\"x\":"}}]}}]}""").jsonObject)
        val parallel = st.translate(json.parseToJsonElement("""{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"stop"}}]}}]}""").jsonObject) ?: emptyList()
        st.translate(json.parseToJsonElement("""{"choices":[{"finish_reason":"tool_calls"}]}""").jsonObject)
        st.finish()
        // index 切换必须先关旧块再开新块（每个事件占 3 行：event/data/空行）
        assertEquals(listOf("event: content_block_stop", "event: content_block_start"),
            parallel.filter { it.startsWith("event: ") })
        val startDataLine = parallel[parallel.indexOf("event: content_block_start") + 1]
        assertTrue(startDataLine.contains("\"c2\"") && startDataLine.contains("\"stop\""))
        assertTrue(st.blockIndex == 1 && st.openType == null && st.finished)
        assertEquals("tool_use", st.stopReason)
    }

    @Test
    fun `未知行与非法数据不产生事件`() {
        val state = OpenAiCompatGateway.SseState()
        assertNull(OpenAiCompatGateway.translateSseLine(state, "event: ping"))
        assertNull(OpenAiCompatGateway.translateSseLine(state, ""))
        assertNull(OpenAiCompatGateway.translateSseLine(state, "data: not-json"))
        // 空对象块作为首块会触发 message_start，此后同样的空块不再产生事件
        assertNotNull(OpenAiCompatGateway.translateSseLine(state, "data: {}"))
        assertNull(OpenAiCompatGateway.translateSseLine(state, "data: {}"))
    }

    @Test
    fun `请求体按字节读取 中文不会被截断`() {
        // Content-Length 是字节数；若按字符读，中文会让读取少若干字符后阻塞（引擎无响应根因）
        val body = """{"messages":[{"role":"user","content":"你好世界"}]}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val raw = ("POST /cc-conv/k/v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n").toByteArray(Charsets.UTF_8) + bytes
        val req = OpenAiCompatGateway.readHttpRequest(java.io.ByteArrayInputStream(raw))
        assertNotNull(req)
        assertEquals("/cc-conv/k/v1/messages", req!!.path)
        assertEquals(body, req.body)
        assertEquals("POST", req.method)
    }

    @Test
    fun `chunked 请求体可解析`() {
        val body = """{"content":"测试分块"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val chunkSize = bytes.size.toString(16)
        val raw = ("POST /passthru/k/v1/messages HTTP/1.1\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n" +
            "$chunkSize\r\n").toByteArray(Charsets.UTF_8) + bytes +
            "\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8)
        val req = OpenAiCompatGateway.readHttpRequest(java.io.ByteArrayInputStream(raw))
        assertNotNull(req)
        assertEquals(body, req!!.body)
    }
}
