package com.aicode.feature.agent.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为评测集护栏（1.3）：只校验样本本身合法、分类/难度分层齐全、期望字段可解析。
 *
 * 定位是「烟测 + 回归护栏」——捕获提示词改动导致的**明显变差**，不承担证明「更准了」的统计职责
 * （样本量不足以检出小幅度提升）。真正的长期裁判是运行时指标 failure_events（纠正率/失败率）。
 *
 * 本测试不调模型：模型行为无法在 JVM 单测里判定（本环境 Robolectric 不可用），
 * 所以这里守的是「样本库本身没坏」——字段缺失、分类漏项、期望动作写错都会在这里先失败。
 */
class BehaviorEvalSetTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val root: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("eval/behavior-samples.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("找不到评测样本 eval/behavior-samples.json")
        json.parseToJsonElement(text).jsonObject
    }

    private val samples: List<JsonObject> by lazy { root.getValue("samples").jsonArray.map { it.jsonObject } }

    /** 必备分类：新增分类时必须同步本集合与样本，防止「只加样本不加分层」。 */
    private val requiredCategories = setOf("intent", "safety", "output", "dispatch", "context")
    private val requiredDifficulties = setOf("low", "mid", "high")

    @Test
    fun samples_are_not_empty_and_ids_unique() {
        assertTrue("评测样本不应为空", samples.isNotEmpty())
        val ids = samples.map { it.str("id") }
        assertTrue("样本 id 不能重复", ids.size == ids.toSet().size)
        assertTrue("样本 id 不能为空", ids.all { it.isNotBlank() })
    }

    @Test
    fun every_sample_has_required_fields() {
        samples.forEach { sample ->
            val id = sample.str("id")
            assertTrue("$id: 缺 category", sample.str("category").isNotBlank())
            assertTrue("$id: 缺 difficulty", sample.str("difficulty").isNotBlank())
            assertTrue("$id: 缺 user", sample.str("user").isNotBlank())
            assertTrue("$id: 缺 rationale（无理由的样本会随迭代腐化）", sample.str("rationale").isNotBlank())
            val expect = sample["expect"]?.jsonObject ?: error("$id: 缺 expect")
            assertTrue("$id: expect 缺 action", expect.str("action").isNotBlank())
        }
    }

    @Test
    fun categories_and_difficulties_cover_required_layers() {
        val categories = samples.map { it.str("category") }.toSet()
        requiredCategories.forEach { required ->
            assertTrue("评测集缺少分类 $required", required in categories)
        }
        val difficulties = samples.map { it.str("difficulty") }.toSet()
        requiredDifficulties.forEach { required ->
            assertTrue("评测集缺少难度层 $required", required in difficulties)
        }
    }

    /** 意图三层制（1.2）与样本口径必须一致：低模糊不提问，中模糊复述后做，高模糊给选项。 */
    @Test
    fun intent_expectations_match_three_tier_policy() {
        samples.filter { it.str("category") == "intent" }.forEach { sample ->
            val id = sample.str("id")
            val expect = sample.getValue("expect").jsonObject
            val action = expect.str("action")
            when (sample.str("difficulty")) {
                "low" -> {
                    assertTrue("$id: 低模糊必须是直接做", action == "direct")
                    assertTrue("$id: 低模糊不该提问", expect["mustNotAsk"]?.jsonPrimitive?.content == "true")
                }
                "mid" -> assertTrue("$id: 中模糊应复述后动手", action == "restate_then_act")
                "high" -> {
                    assertTrue("$id: 高模糊应给选项", action == "offer_options")
                    val min = expect.str("minOptions").toIntOrNull() ?: 0
                    val max = expect.str("maxOptions").toIntOrNull() ?: 0
                    assertTrue("$id: 选项数区间非法", min in 2..4 && max in min..4)
                }
            }
        }
    }

    /** 建议类输出三要素（1.8）与样本口径一致。 */
    @Test
    fun advice_samples_require_three_parts() {
        val advice = samples.filter { sample ->
            sample.getValue("expect").jsonObject.str("action") == "advice_with_three_parts"
        }
        assertTrue("评测集应覆盖建议类输出", advice.isNotEmpty())
        advice.forEach { sample ->
            val parts = sample.getValue("expect").jsonObject.getValue("requiredParts").jsonArray
                .map { it.jsonPrimitive.content }
            assertTrue(
                "${sample.str("id")}: 建议类必须要求 影响范围/回退方式/风险提示",
                parts.containsAll(listOf("影响范围", "回退方式", "风险提示"))
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
}
