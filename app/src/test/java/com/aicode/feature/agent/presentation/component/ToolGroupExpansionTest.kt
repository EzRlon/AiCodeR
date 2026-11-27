package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续工具调用分组的展开判定 + 整轮任务折叠。
 *
 * 分组展开与否：**上层持久化的手动选择**（[AIAgentViewModel.toolExpansionOverrides]，按 [toolGroupKey] 取）优先；
 * 没有手动记录时，运行中的轮只铺开**当前思考链**（最后一条带思考的助手消息及其后的工具组），
 * 更早的思考链与工具调用自动收起；没有思考的轮退化为只铺开最后一组；完成后随轮头收进「构造过程」。
 *
 * 整轮折叠（最外层）只认 [AIAgentViewModel.turnExpansionOverrides]；没记录时「运行中展开、
 * 完成后自动收起进『构造过程』」——完成后每轮只常显用户消息与最终实施结果，会话保持清爽。
 *
 * 结构：工具分组与过程项都挂在轮头 item 的 [ChatRenderItem.turnProcess] 里（与折叠头同一 Composable，
 * 展开时整体动画）；顶层 items 的顺序为「用户消息 / 结果区（常显项与结果 + 产物块）/ 轮头（过程区）」。
 */
class ToolGroupExpansionTest {

    private fun user(id: String) =
        AgentUIMessage(id = id, role = MessageRole.USER, content = "hi")

    private fun tool(id: String) = AgentUIMessage(id = id, role = MessageRole.TOOL, content = "done")

    private fun assistant(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "看一下")

    private val groupKey = "toolgroup:t1"

    private fun items(
        messages: List<AgentUIMessage>,
        overrides: Map<String, Boolean> = emptyMap(),
        turnOverrides: Map<String, Boolean> = emptyMap(),
        activeTurnKey: String? = null,
    ) = buildChatItems(messages, overrides, turnOverrides, activeTurnKey)

    /** 顶层 item 里唯一的轮头 item。 */
    private fun List<ChatRenderItem>.header() = first { it.turnHeader != null }

    /** 所有轮头携带的工具分组项（按轮头顺序展开）。 */
    private fun List<ChatRenderItem>.groupItems(): List<ChatRenderItem> =
        flatMap { it.turnProcess }.filter { it.toolGroup != null }

    @Test
    fun compactionMarkerDoesNotStartNewTurn() {
        val marker = user("marker").copy(isCompactionMarker = true)
        val summary = assistant("summary").copy(isContextSummary = true)
        val rendered = items(
            listOf(user("u0"), tool("t1"), marker, summary, assistant("answer")),
            activeTurnKey = "turn:u0"
        )
        assertEquals(1, rendered.count { it.turnHeader != null })
        assertEquals("turn:u0", rendered.header().turnHeader?.key)
        assertTrue(rendered.any { it.message.id == "marker" })
        assertTrue(rendered.any { it.message.id == "summary" })
    }

    // ---- 连续工具调用分组 ----

    @Test
    fun latestTurn_groupExpandsByDefault() {
        // 运行中的轮：工具分组随轮头默认铺开，过程直接可见
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val groups = items.groupItems()
        assertEquals(1, groups.size)
        assertTrue(groups.first().groupExpanded)
    }

    @Test
    fun singleTool_isNotGrouped() {
        // 只有一条工具时不折成「1 次工具调用」，直接当普通工具行显示
        val items = items(listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0")
        assertEquals(0, items.groupItems().size)
        assertTrue(items.header().turnProcess.any { it.key == "t1" })
    }

    @Test
    fun earlierTurnGroup_collapses_latestTurnGroup_expands() {
        // 同一运行轮内：只有当前（最后）一批工具调用默认展开，更早的历史调用自动收起
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, activeTurnKey = "turn:u0").groupItems()
        assertEquals(2, groups.size)
        assertFalse("同轮更早的分组收起", groups[0].groupExpanded)
        assertTrue("同轮最新一组展开", groups[1].groupExpanded)

        // 两轮场景：第一轮收起（非最新）、第二轮展开
        val twoTurns = listOf(
            user("u0"), tool("t1"), tool("t2"),
            user("u1"), tool("t3"), tool("t4"),
        )
        val groups2 = items(twoTurns, activeTurnKey = "turn:u1").groupItems()
        assertEquals(2, groups2.size)
        assertFalse("更早轮的分组收起", groups2[0].groupExpanded)
        assertTrue("最新轮的分组展开", groups2[1].groupExpanded)
    }

    @Test
    fun runningTurn_onlyCurrentReasoningChainExpands() {
        // 思考链边界：上一条思考链的工具组随之收起，当前思考链的工具组展开
        val messages = listOf(
            user("u0"),
            assistant("a1").copy(reasoning = "思考一"),
            tool("t1"), tool("t2"),
            assistant("a2").copy(reasoning = "思考二"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, activeTurnKey = "turn:u0").groupItems()
        assertEquals(2, groups.size)
        assertFalse("上一条思考链的工具组收起", groups[0].groupExpanded)
        assertTrue("当前思考链的工具组展开", groups[1].groupExpanded)
    }

    @Test
    fun manualExpand_survivesRebuild() {
        val messages = listOf(user("u0"), tool("t1"), tool("t2"))
        val expanded = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(expanded.groupItems().first().groupExpanded)
        // 同一份覆盖重建（列表滚动回收 / 切页返回后重组走的就是这条路径）：状态必须一致
        val rebuilt = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(rebuilt.groupItems().first().groupExpanded)
        assertEquals(expanded.size, rebuilt.size)
    }

    @Test
    fun manualCollapse_isRespected() {
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false),
            activeTurnKey = "turn:u0",
        )
        assertFalse("手动收起过：保持收起", items.groupItems().first().groupExpanded)
    }

    @Test
    fun userMessageBreaksGrouping() {
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            user("u1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, turnOverrides = mapOf("turn:u0" to true, "turn:u1" to true)).groupItems()
        assertEquals(2, groups.size)
        assertEquals("toolgroup:t1", groups[0].key)
        assertEquals("toolgroup:t3", groups[1].key)
    }

    @Test
    fun toolWithAttachments_isNotGrouped() {
        // sendFile / generateImage 产出的文件行就是结果本身：不参与「N 次工具调用」折叠，
        // 也不参与整轮折叠（常显）。它自己是一级 item，同时把左右两批工具切开。
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            tool("send").copy(toolName = "sendFile", attachments = listOf(file)),
            tool("t3"), tool("t4"),
        )
        val items = items(messages, activeTurnKey = "turn:u0")
        // send 是常显项：顶层独立 item
        assertTrue("带附件的工具是顶层常显项", items.any { it.key == "send" })
        val groups = items.groupItems()
        assertEquals(listOf("toolgroup:t1", "toolgroup:t3"), groups.map { it.key })
        assertFalse("更早的历史调用收起", groups[0].groupExpanded)
        assertTrue("最新一组工具调用展开", groups[1].groupExpanded)
        // send 不是任何分组的成员
        assertFalse(groups.any { group -> group.toolGroup.orEmpty().any { it.id == "send" } })
    }

    // ---- 整轮任务折叠 ----

    @Test
    fun activeTurn_expandsByDefault() {
        // 末轮且 agent 忙：折叠头显示「执行中」并默认展开，过程项照常挂载
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val header = items.header().turnHeader!!
        assertTrue(header.running)
        assertTrue(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun activeTurn_keepsAssistantBeforeFollowingTools() {
        val items = items(
            listOf(user("u0"), assistant("a1"), tool("t1"), tool("t2")),
            activeTurnKey = "turn:u0",
        )
        assertEquals(listOf("a1", "toolgroup:t1"), items.header().turnProcess.map { it.key })
        assertFalse(items.any { it.key == "a1" })
    }

    @Test
    fun activeTurn_keepsSuccessiveAssistantMessagesInOrder() {
        val messages = listOf(user("u0"), assistant("a1"), tool("t1"), assistant("a2"))
        val running = items(messages, activeTurnKey = "turn:u0")
        assertEquals(listOf("a1", "t1", "a2"), running.header().turnProcess.map { it.key })
        assertFalse(running.any { it.key == "a2" })

        val finished = items(messages, turnOverrides = mapOf("turn:u0" to true))
        assertEquals(listOf("a1", "t1"), finished.header().turnProcess.map { it.key })
        assertEquals(1, finished.count { it.key == "a2" })
    }

    @Test
    fun finishedTurn_autoCollapses() {
        // 收工（activeTurnKey 归 null）：本轮自动收进「构造过程」——只留用户消息与最终结果，
        // 过程数据仍挂在轮头里可手动展开
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = null)
        val header = items.header().turnHeader!!
        assertFalse(header.running)
        assertFalse("完成轮默认收起（构造过程）", header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun finishedTurn_manualExpandSurvives() {
        // 手动展开过的轮：完成后仍保持用户的选择（不会被「自动收起」覆盖）
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            turnOverrides = mapOf("turn:u0" to true),
            activeTurnKey = null,
        )
        assertTrue(items.header().turnHeader!!.expanded)
    }

    @Test
    fun finishedEarlierTurn_autoCollapses() {
        // 完成的轮一律默认收起：会话里每轮只常显用户消息与最终实施结果
        val messages = listOf(
            user("u0"), tool("t1"), tool("t2"),
            user("u1"), tool("t3"), tool("t4"),
        )
        val items = items(messages, activeTurnKey = null)
        val headers = items.filter { it.turnHeader != null }
        assertEquals(2, headers.size)
        assertFalse("更早的轮收起", headers[0].turnHeader!!.expanded)
        assertFalse("最新轮也收起（构造过程）", headers[1].turnHeader!!.expanded)
    }

    @Test
    fun resultPrecedesProcessArea() {
        // 结果置前：常显项与结果排在轮头（过程区）之前，轮末才是折叠头
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "想一下", reasoning = "思考"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "最终答案"),
        )
        val items = items(messages, turnOverrides = mapOf("turn:u0" to true), activeTurnKey = null)
        val resultIndex = items.indexOfFirst { it.key == "a2" }
        val headerIndex = items.indexOfFirst { it.turnHeader != null }
        assertTrue(resultIndex >= 0 && headerIndex >= 0)
        assertTrue("结果排在过程区（轮头）之前", resultIndex < headerIndex)
    }

    // ---- 右侧时间线节点 ----

    @Test
    fun timelineNodes_carryChatItemIndices() {
        // 时间线节点：提问 / 结果各一个；轮头 item 不是节点；itemIndex 指向 chatItems 下标
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "答案一", reasoning = "思考一"),
            user("u1"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "答案二"),
        )
        val chatItems = items(messages, activeTurnKey = null)
        val nodes = buildTimelineNodes(messages, chatItems)
        // 提问/结果锚消息 item；TOOL 锚过程区入口（折叠轮头/工具分组/常显工具行），产出在其所属结果之后
        assertEquals(
            listOf(
                TimelineNodeType.USER,
                TimelineNodeType.ASSISTANT_RESULT,
                TimelineNodeType.TOOL,
                TimelineNodeType.USER,
                TimelineNodeType.ASSISTANT_RESULT,
            ),
            nodes.map { it.type }
        )
        nodes.forEach { node ->
            if (node.type != TimelineNodeType.TOOL) {
                assertTrue("提问/结果节点不能锚在轮头 item 上", chatItems[node.itemIndex].turnHeader == null)
            }
        }
        assertEquals("u0", chatItems[nodes[0].itemIndex].key)
        assertEquals("a1", chatItems[nodes[1].itemIndex].key)
    }

    @Test
    fun timelineNodes_includeVerificationReport() {
        // 验收报告单独成节点（特标色）
        val report = AgentUIMessage(
            id = "v1", role = MessageRole.TOOL, content = "验收：通过", isVerificationReport = true
        )
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "答案"),
            report,
        )
        val chatItems = items(messages, activeTurnKey = null)
        val nodes = buildTimelineNodes(messages, chatItems)
        assertEquals(
            listOf(TimelineNodeType.USER, TimelineNodeType.ASSISTANT_RESULT, TimelineNodeType.VERIFICATION),
            nodes.map { it.type }
        )
    }

    @Test
    fun manualTurnExpand_survivesFinish() {
        // 用户手动展开过：任务收工也不自动收起
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            turnOverrides = mapOf("turn:u0" to true),
            activeTurnKey = null,
        )
        val header = items.header().turnHeader!!
        assertTrue(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    // ---- 窗口按条数截断：最前面那一截半轮 ----

    @Test
    fun truncatedWindow_foldsLeadingHalfTurn() {
        // 窗口从某一轮中间切开时，最前面那段缺的是轮首用户消息、不是折叠头：
        // 过程项同样要折进折叠头，不能摊在历史最前面
        val messages = listOf(
            assistant("a1"),
            tool("t1"), tool("t2"),
            user("u0"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "答"),
        )
        val rendered = items(messages)
        val heads = rendered.filter { it.turnHeader != null }
        assertEquals("只有截断那一截需要补折叠头", 1, heads.size)
        val leading = heads.first().turnHeader!!
        assertEquals("turn:head:t2", leading.key)
        assertFalse(leading.running)
        assertFalse("默认收起", leading.expanded)
        assertEquals(listOf("a1", "toolgroup:t1"), heads.first().turnProcess.map { it.key })
        assertFalse("过程项不再占顶层 item", rendered.any { it.key == "a1" || it.key == "toolgroup:t1" })
    }

    @Test
    fun truncatedWindow_keepsTrailingAnswerVisible() {
        // 这一截的结尾通常就是上一轮的最终回复：它照旧常显，不跟着过程一起折起来
        val messages = listOf(
            tool("t1"), tool("t2"),
            AgentUIMessage(id = "a9", role = MessageRole.ASSISTANT, content = "上一轮的答案"),
            user("u0"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "本轮答案"),
        )
        val rendered = items(messages)
        val leadingHead = rendered.first { it.turnHeader?.key == "turn:head:a9" }
        assertEquals(listOf("toolgroup:t1"), leadingHead.turnProcess.map { it.key })
        assertTrue("上一轮答案常显", rendered.any { it.key == "a9" })
        assertFalse(rendered.first { it.key == "a9" }.reasoningVisible)
    }

    @Test
    fun truncatedWindow_headKeySurvivesOlderMessagesPrepended() {
        // 窗口继续向前补加载（新消息插在块首）时块尾不变、key 不变，手动展开态跟着保住
        val older = listOf(assistant("m0"), tool("m1"))
        val tail = listOf(assistant("a1"), tool("t1"), tool("t2"), user("u0"))
        val before = items(tail, turnOverrides = mapOf("turn:head:t2" to true))
        val after = items(older + tail, turnOverrides = mapOf("turn:head:t2" to true))
        assertTrue(before.first { it.turnHeader?.key == "turn:head:t2" }.turnHeader!!.expanded)
        assertTrue(after.first { it.turnHeader?.key == "turn:head:t2" }.turnHeader!!.expanded)
    }

    @Test
    fun truncatedWindow_persistentLeadingStaysTopLevel() {
        // 压缩卡片 / 后台通知 / 带附件工具在这一截里同样不参与折叠
        val notice = AgentUIMessage(id = "n1", role = MessageRole.USER, content = "done")
            .copy(isBackgroundNotification = true)
        val messages = listOf(
            assistant("a1"),
            tool("t1"), tool("t2"),
            notice,
            user("u0"),
        )
        val rendered = items(messages)
        assertTrue("后台通知常显", rendered.any { it.key == "n1" })
        assertEquals("turn:head:n1", rendered.first { it.turnHeader != null }.turnHeader!!.key)
    }

    @Test
    fun plainTextTurn_hasNoHeader() {
        // 无过程（无思考、无工具）的纯文本轮：退化为现状，不加折叠头
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "直接回答"),
        )
        val items = items(messages, activeTurnKey = null)
        assertTrue(items.none { it.turnHeader != null })
        assertTrue(items.any { it.key == "a1" })
    }

    @Test
    fun resultReasoning_becomesProcessItem() {
        // 轮末助手的思考抽为过程项；结果正文块不再重复渲染思考
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "最终答案", reasoning = "想一下"),
        )
        val items = items(messages, turnOverrides = mapOf("turn:u0" to true), activeTurnKey = null)
        assertTrue("思考成为轮头过程项", items.header().turnProcess.any { it.key == "a1#reasoning" })
        assertFalse("结果正文块不重复渲染思考", items.first { it.key == "a1" }.reasoningVisible)
    }
}
