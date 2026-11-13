package com.aicode.core.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 当前前台 Activity 的弱引用。
 *
 * 少数需要「临时挂一个不可见 View 到窗口」的基础设施（如在线商店的人机校验 WebView）
 * 在 data 层也要拿到 Activity；用 Application 注册生命周期回调统一追踪，避免各处反射
 * ActivityThread 或把 Activity 一路透传进网络层。
 */
object ActivityTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    /** 当前处于前台（resumed）的 Activity；已 finish/destroy 的返回 null。 */
    val current: Activity?
        get() = currentRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        currentRef = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
