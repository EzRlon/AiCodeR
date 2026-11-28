package com.aicode.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingSignatureErrorTest {
    private val sourceIds: List<String> = (0 until 40).map { "m$it" }

    @Test
    fun normal_400_signature_error_resolves_source_id() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"messages.19.content.0: Invalid `signature` in `thinking` block"}}"""
        val e = thinkingSignatureError(body, sourceIds)
        assertNotNull(e)
        assertEquals("m19", e?.fromMessageId)
    }

    @Test
    fun masked_position_degrades_to_full_clear() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"***.***.content.0: Invalid `signature` in `thinking` block"}}"""
        val e = thinkingSignatureError(body, sourceIds)
        assertNotNull(e)
        assertNull(e?.fromMessageId)
    }

    @Test
    fun out_of_range_position_degrades_to_full_clear() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"messages.999.content.0: Invalid `signature` in `thinking` block"}}"""
        val e = thinkingSignatureError(body, sourceIds)
        assertNotNull(e)
        assertNull(e?.fromMessageId)
    }

    @Test
    fun unrelated_400_is_not_signature_error() {
        assertNull(thinkingSignatureError("""{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: field required"}}""", sourceIds))
    }
}
