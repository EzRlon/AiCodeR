package com.aicode.feature.agent.domain.workflow

/**
 * 旁路助手的准入与提示词。
 *
 * 场景：主代理正在跑长任务，用户发来一句**问题/反馈**。直接排队会让用户等很久，
 * 但打断主代理又会丢进度。此时在父会话下开一个只读子会话（独立 CLI session id），
 * 由旁路助手先回答用户，主代理继续干活；用户可在侧栏点进子会话查看。
 *
 * 只接管「问题/反馈」类短消息；带明确任务动词或很长的需求仍走原队列，交给主代理，
 * 避免旁路助手用只读权限接了一个需要改文件的任务。
 */
object ReplySidecar {

    private val QUESTION_HINTS = listOf(
        "？", "?", "为什么", "怎么", "如何", "能否", "可以吗", "是不是",
        "解释", "看一下", "反馈", "正常吗", "什么情况"
    )

    /**
     * 任务动词出现在句中也不能交给只读旁路——「能不能把按钮改成红色？」带问号，
     * 但本质是施工需求。宁可多排一条队，也不要让只读子会话把任务静默搁置。
     */
    private val TASK_VERBS = listOf(
        "帮我", "请帮", "帮忙", "实现", "新增", "添加", "删除", "移除",
        "重构", "部署", "提交", "执行", "跑一下", "做一个", "写一个", "写一下",
        "补上", "接上", "改成", "改一下", "修一下", "修了", "换成", "加上"
    )

    /** 过长的需求通常不是一句反馈，交给主代理排队更稳妥。 */
    private const val MAX_CHARS = 500

    fun shouldHandle(request: String): Boolean {
        val text = request.trim()
        if (text.isEmpty() || text.length > MAX_CHARS) return false
        if (ContinuationDirective.isContinuation(text)) return false
        if (TASK_VERBS.any { text.contains(it) }) return false
        return QUESTION_HINTS.any { text.contains(it) }
    }

    /**
     * 旁路助手的系统指令：只读、只回答，不抢主代理的活。
     * 这是给模型的提示词，不是用户界面文案。
     */
    fun prompt(request: String): String =
        "【旁路助手】主代理正在执行一个长任务，暂时不能切过来。请只回答用户的这条问题/反馈，" +
            "不要修改任何文件、不要执行会改变工作区的命令、不要派发子代理，也不要假装已经完成了任务。" +
            "如果用户是在要求修改代码或继续施工，请说明主代理仍在运行，建议等主代理完成后把需求发到主会话；" +
            "你现在可以基于已有上下文给出诊断、建议或风险提示。\n\n" +
            "用户消息：$request"
}
