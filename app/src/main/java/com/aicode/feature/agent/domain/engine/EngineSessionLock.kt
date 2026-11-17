package com.aicode.feature.agent.domain.engine

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 跨进程的「同会话只允许一个引擎回合」锁。
 *
 * 为什么需要：App 主进程被重启/LMK 后，新进程看不到旧进程内存里的 [EngineProcessRegistry]，
 * 用户一发消息就会再 spawn 一份同会话 CLI；旧进程留下的 CLI 还占着会话，于是出现
 * 「探测到另一个进程正在处理同一内容」，表现为消息被截断、回合空返回。
 *
 * 机制：
 * - 锁 = `filesDir/engine-locks/<sessionId>.lock`，内容记录持有者 pid + /proc 启动时间；
 * - 占用中（文件存在且 pid 仍存活）→ 调用方**只排队等待**，绝不杀进程；
 * - 持有者进程已死（pid 不存在或启动时间不符）→ 视为陈旧锁，删除后获取；
 * - 用户主动停止时调用 [forceRelease]，之后由停止逻辑负责杀进程。
 *
 * 不杀进程是硬约束：用户明确要求「发送消息不得打断正在跑的后台任务」。
 */
@Singleton
class EngineSessionLock @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "EngineSessionLock"
        const val DIR = "engine-locks"
    }

    @Serializable
    private data class LockRecord(val pid: Int, val startTicks: Long, val acquiredAt: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, DIR)
    private val probe: ProcessProbe = ProcFsProbe()

    private fun lockFile(sessionId: String) = File(dir, "${sessionId}.lock")

    /**
     * 尝试持有会话锁。返回 true 表示可以开始新回合；false 表示同会话还有活着的引擎回合
     * （本进程或其它进程），调用方应排队等待而不是起新进程。
     */
    fun tryAcquire(sessionId: String): Boolean = synchronized(this) {
        val file = lockFile(sessionId)
        val existing = read(file)
        if (existing != null) {
            // 本进程已持有：可重入（processNextInQueue 与 execute 各确认一次）。
            if (existing.pid == android.os.Process.myPid()) return true
            val stat = probe.stat(existing.pid)
            val alive = stat != null && stat.state != 'Z' && stat.startTicks == existing.startTicks
            if (alive) return false
            FileLogger.i(TAG, "清理陈旧会话锁: sid=$sessionId holder=${existing.pid}")
            file.delete()
        }
        val myPid = android.os.Process.myPid()
        val stat = probe.stat(myPid)
        val record = LockRecord(
            pid = myPid,
            startTicks = stat?.startTicks ?: 0L,
            acquiredAt = System.currentTimeMillis()
        )
        return runCatching {
            dir.mkdirs()
            file.writeText(json.encodeToString(record))
            true
        }.onFailure { FileLogger.w(TAG, "写会话锁失败: sid=$sessionId", it) }
            // 写不了锁文件时放行（宁可并发也不要卡死用户），日志留痕。
            .getOrDefault(true)
    }

    /**
     * 只查询、不获取：会话是否被**其它**存活进程占用。
     * 队列闸门用它避免「只检查却顺手加锁、又没真正开跑」导致的自锁。
     */
    fun isHeldByOther(sessionId: String): Boolean = synchronized(this) {
        val existing = read(lockFile(sessionId)) ?: return false
        if (existing.pid == android.os.Process.myPid()) return false
        val stat = probe.stat(existing.pid)
        stat != null && stat.state != 'Z' && stat.startTicks == existing.startTicks
    }

    /** 释放会话锁；只删除自己持有的那一份，避免误删接替回合的锁。 */
    fun release(sessionId: String) {
        synchronized(this) {
            val file = lockFile(sessionId)
            val existing = read(file)
            if (existing != null && existing.pid != android.os.Process.myPid()) return
            runCatching { file.delete() }
        }
    }

    /** 用户主动停止：无条件释放，让下一次发送可以立即开始。 */
    fun forceRelease(sessionId: String) = synchronized(this) {
        runCatching { lockFile(sessionId).delete() }
    }

    private fun read(file: File): LockRecord? = runCatching {
        if (!file.exists()) null else json.decodeFromString<LockRecord>(file.readText())
    }.getOrNull()
}
