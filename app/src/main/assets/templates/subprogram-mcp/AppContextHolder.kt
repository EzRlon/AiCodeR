package com.aicoder.app.mcp

import android.content.Context

/** Application.onCreate 时 init，供 server 线程读取应用上下文。 */
object AppContextHolder {
    @Volatile private var ref: Context? = null

    fun init(context: Context) {
        ref = context.applicationContext
    }

    fun get(): Context = ref ?: throw IllegalStateException("AppContextHolder 未初始化：宿主 Application onCreate 需调用 init()")
}
