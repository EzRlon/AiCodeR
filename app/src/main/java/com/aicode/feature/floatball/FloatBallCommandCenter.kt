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
}
