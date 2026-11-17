package com.aicode.feature.agent.domain.command

import com.aicode.R
import javax.inject.Inject

/**
 * /handoff —— 即时生成「接手快照」并复制到剪贴板：最近一次压缩摘要 + 其后的最新原文，
 * 零模型调用、毫秒级返回；聊天里的「准备交接」短语与 [HandoffCommandHandler] 同一入口。
 */
class HandoffCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "handoff"
    override val descriptionRes = R.string.slash_command_handoff_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.prepareHandoffSnapshot()
    }
}
