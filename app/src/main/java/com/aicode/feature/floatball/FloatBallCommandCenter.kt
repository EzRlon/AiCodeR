package com.aicode.feature.floatball

/**
 * 悬浮窗 → 主会话的命令桥（同进程内存回调）。
 * AIAgentViewModel 在 init 注册回调；悬浮球面板的「发送」经此桥进入正常入队流程
 * （享受暂停拦截、队列、权限等全部既有保护）。
 */
object FloatBallCommandCenter {

    /** 把文本作为当前会话的用户消息发送；返回 false 表示被拦截（如会话暂停）；null 表示尚未注册（ViewModel 未就绪）。 */
    @Volatile
    var send: ((String) -> Boolean)? = null

    /** 返回 App 主界面（打开会话）。 */
    @Volatile
    var openApp: (() -> Unit)? = null

    /** 打开 App 并直达诊断与自测分区；null 表示 ViewModel 未就绪。 */
    @Volatile
    var openDiagnostics: (() -> Unit)? = null

    /** 停止当前正在运行的任务；null 表示未注册。返回是否真正执行了停止。 */
    @Volatile
    var stop: (() -> Boolean)? = null

    /** 新建会话（复用空会话逻辑同 App 内「新会话」按钮）；null 表示 ViewModel 未就绪。 */
    @Volatile
    var newSession: (() -> Unit)? = null

    /**
     * 处理最早待决的工具授权：choice 为用户的允许/拒绝。
     * 走带审计的 resolve 路径（source=FLOAT_BALL）；返回是否命中了挂起请求。
     */
    @Volatile
    var resolveApproval: ((com.aicode.feature.agent.domain.permission.PermissionChoice) -> Boolean)? = null

    /** FB2：切换当前会话工作模式（BUILD/PLAN/AUTO），与 App 内模式按钮同逻辑。 */
    @Volatile
    var setMode: ((com.aicode.feature.agent.domain.model.AgentMode) -> Unit)? = null

    /** FB2：悬浮窗输入 → App 草稿（同一份草稿，App 底栏与面板实时同步）。 */
    @Volatile
    var updateDraft: ((String) -> Unit)? = null

    /** FB3：润色面板输入框文本（复用会话输入框润色链路）；null 表示 ViewModel 未就绪。 */
    var polishText: (suspend (String) -> String?)? = null

    /** FB2：悬浮窗内嵌模型选择的数据快照（按需拉取，随 App 模型列表变化）。 */
    data class FloatModelPickerData(
        val currentProviderId: String?,
        val currentModel: String?,
        val providers: List<com.aicode.feature.settings.domain.model.AIProviderConfig>,
        val metadata: Map<String, com.aicode.feature.settings.domain.model.ModelMetadata>
    )

    /** FB2：拉取模型选择数据（providers/当前选中/元数据）。 */
    @Volatile
    var modelData: (() -> FloatModelPickerData?)? = null

    /** FB2：选定模型（providerId, modelId），与 App 内模型 sheet 同一落点。 */
    @Volatile
    var selectModel: ((String, String) -> Unit)? = null
}
