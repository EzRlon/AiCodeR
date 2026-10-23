package com.aicode.feature.schedule.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScheduledTask] 的序列化兼容：旧版（E5 容器命令型）JSON 缺新字段能回落默认值，
 * 新增字段（AI 型 / 脚本型 / ONCE / 预授权类别）可完整往返。
 */
class ScheduledTaskSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacyCommandTask_decodesWithDefaults() {
        val legacy = """
            {"id":"1","name":"拉取代码","command":"git pull","enabled":true,
             "schedule":{"mode":"DAILY","dailyHour":9,"dailyMinute":0,"intervalMinutes":60},
             "lastRunAt":1700000000000,"lastExitCode":0,"lastOutput":"ok"}
        """.trimIndent()
        val task = json.decodeFromString<ScheduledTask>(legacy)
        assertEquals(ScheduledTaskType.COMMAND, task.type)
        assertNull(task.prompt)
        assertNull(task.planText)
        assertNull(task.runMode)
        assertNull(task.scriptName)
        assertTrue(task.authCategories.isEmpty())
        assertEquals("git pull", task.command)
        assertEquals(ScheduleMode.DAILY, task.schedule.mode)
        assertNull(task.schedule.startAt)
        assertNull(task.schedule.endAt)
        assertEquals("BUILD", task.effectiveRunMode)
    }

    @Test
    fun aiTask_roundTripsAllNewFields() {
        val task = ScheduledTask(
            id = "2",
            name = "夜间整理",
            type = ScheduledTaskType.AI_TASK,
            prompt = "整理构建日志",
            runMode = "BUILD",
            authCategories = listOf(
                ScheduledAuthCategory.READ,
                ScheduledAuthCategory.NETWORK,
                ScheduledAuthCategory.DELETE
            ),
            workspacePath = "/root/workspace/proj",
            schedule = TaskSchedule(
                mode = ScheduleMode.ONCE,
                startAt = 1_900_000_000_000L,
                endAt = 1_900_100_000_000L
            )
        )
        val decoded = json.decodeFromString<ScheduledTask>(json.encodeToString(task))
        assertEquals(task, decoded)
    }

    @Test
    fun planTask_roundTripsUnsetSchedule() {
        val task = ScheduledTask(
            id = "3",
            name = "长任务",
            type = ScheduledTaskType.AI_PLAN,
            planText = "1. 重构模块 A\n2. 补测试",
            sourceSessionId = "session-1",
            schedule = TaskSchedule(mode = ScheduleMode.UNSET)
        )
        val decoded = json.decodeFromString<ScheduledTask>(json.encodeToString(task))
        assertEquals(ScheduleMode.UNSET, decoded.schedule.mode)
        assertEquals("1. 重构模块 A\n2. 补测试", decoded.planText)
        assertTrue(decoded.isAiType)
    }

    @Test
    fun scriptTask_roundTripsScriptName() {
        val task = ScheduledTask(
            id = "5",
            name = "每日清理",
            type = ScheduledTaskType.SCRIPT,
            scriptName = "cleanup.sh",
            schedule = TaskSchedule(mode = ScheduleMode.DAILY, dailyHour = 3, dailyMinute = 30)
        )
        val decoded = json.decodeFromString<ScheduledTask>(json.encodeToString(task))
        assertEquals("cleanup.sh", decoded.scriptName)
        assertEquals(ScheduledTaskType.SCRIPT, decoded.type)
    }

    @Test
    fun isAiType_onlyTrueForAiTypes() {
        // 决定 Worker 分派（AI 型走 ScheduledAiTaskWorker，命令/脚本走 ScheduledTaskWorker）
        val command = ScheduledTask(id = "4", name = "x", command = "ls")
        val script = ScheduledTask(id = "6", name = "y", type = ScheduledTaskType.SCRIPT, scriptName = "a.sh")
        val ai = ScheduledTask(id = "7", name = "z", type = ScheduledTaskType.AI_TASK, prompt = "p")
        val plan = ScheduledTask(id = "8", name = "w", type = ScheduledTaskType.AI_PLAN, planText = "p")

        assertTrue(!command.isAiType)
        assertTrue(!script.isAiType)
        assertTrue(ai.isAiType)
        assertTrue(plan.isAiType)
    }
}
