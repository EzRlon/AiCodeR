package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoStatus
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部引擎原生待办 → App 待办镜像的解析行为。
 *
 * 回归点：三个外部引擎此前从不写 `todo_items` 表，待办卡片对它们永不出现。
 * 这里钉住三种 CLI 载荷的解析：Claude TodoWrite / Codex todo_list / OpenCode todowrite。
 */
class EngineTodoMirrorTest {

    private val mirror = EngineTodoMirror(
        todoItemDao = mockk<TodoItemDao>(relaxed = true)
    )

    private fun array(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `Claude TodoWrite 的 todos 数组按 content 与 status 解析`() {
        val todos = mirror.parseTodos(
            array(
                """[
                {"content":"分析项目结构","status":"in_progress","activeForm":"正在分析项目结构"},
                {"content":"修复构建","status":"pending"},
                {"content":"跑测试","status":"completed"}
            ]"""
            )
        )
        assertEquals(3, todos.size)
        assertEquals("分析项目结构", todos[0].subject)
        assertEquals(TodoStatus.IN_PROGRESS, todos[0].status)
        assertEquals("正在分析项目结构", todos[0].description)
        assertEquals(TodoStatus.PENDING, todos[1].status)
        assertEquals(TodoStatus.COMPLETED, todos[2].status)
    }

    @Test
    fun `Codex todo_list 的 items 数组按 step 与 status 解析`() {
        val todos = mirror.parseTodos(
            array(
                """[
                {"step":"定位根因","status":"completed"},
                {"step":"实施修复","status":"in_progress"},
                {"step":"验证回归","status":"pending"}
            ]"""
            )
        )
        assertEquals(3, todos.size)
        assertEquals("定位根因", todos[0].subject)
        assertEquals(TodoStatus.COMPLETED, todos[0].status)
        assertEquals("实施修复", todos[1].subject)
        assertEquals(TodoStatus.IN_PROGRESS, todos[1].status)
    }

    @Test
    fun `OpenCode todowrite 的 todos 数组按 content 解析且状态归一`() {
        val todos = mirror.parseTodos(
            array(
                """[
                {"id":"1","content":"写实现","status":"in_progress","priority":2},
                {"id":"2","content":"写测试","status":"completed"}
            ]"""
            )
        )
        assertEquals(2, todos.size)
        assertEquals("写实现", todos[0].subject)
        assertEquals(TodoStatus.IN_PROGRESS, todos[0].status)
        assertEquals(2, todos[0].priority)
        assertEquals(TodoStatus.COMPLETED, todos[1].status)
    }

    @Test
    fun `非标准状态值回落 PENDING 不抛异常`() {
        val todos = mirror.parseTodos(array("""[{"content":"任务","status":"weird-state"}]"""))
        assertEquals(1, todos.size)
        assertEquals(TodoStatus.PENDING, todos[0].status)
    }

    @Test
    fun `缺主题的条目跳过`() {
        val todos = mirror.parseTodos(
            array(
                """[
                {"status":"pending"},
                {"content":"正常任务","status":"pending"}
            ]"""
            )
        )
        assertEquals(1, todos.size)
        assertEquals("正常任务", todos[0].subject)
    }

    @Test
    fun `null 与空数组返回空清单`() {
        assertTrue(mirror.parseTodos(null).isEmpty())
        assertTrue(mirror.parseTodos(array("[]")).isEmpty())
    }
}
