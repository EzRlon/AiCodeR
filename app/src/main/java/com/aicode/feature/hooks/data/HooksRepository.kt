package com.aicode.feature.hooks.data

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.hooks.domain.HooksConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hooks 配置读取：全局 `~/.aicode/hooks.json`（容器内路径即 filesDir/aicode/hooks.json）。
 *
 * 第一版边界（与 VncServiceController 同款策略）：
 * - 仅本地 PRoot 模式生效（文件在 app 私有目录、经 bind 容器可见；远程模式的 hooks 文件在服务器，
 *   读取与执行链路未实现，调用方按模式自行跳过）；
 * - 仅全局层（项目级 hooks 会引入「AI 改写项目文件即静默执行命令」的提权面，暂不开放）。
 *
 * 读缓存按文件修改时间失效：每次工具执行前检查一次 mtime（本地 File.stat 开销可忽略），
 * 用户/AI 编辑 hooks.json 后立即生效，无需监听器。
 */
@Singleton
class HooksRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "HooksRepository"
        const val HOOKS_FILE = "hooks.json"
        val JSON = Json { ignoreUnknownKeys = true }
    }

    val hooksFile: File
        get() = File(File(context.filesDir, "aicode"), HOOKS_FILE)

    @Volatile
    private var cached: HooksConfig? = null

    @Volatile
    private var cachedMtime: Long = -1

    /** 当前生效的 hooks 配置；文件不存在或解析失败时返回空配置（钩子关机，工具链路照常）。 */
    fun current(): HooksConfig {
        val file = hooksFile
        val mtime = if (file.isFile) file.lastModified() else 0L
        val cache = cached
        if (cache != null && mtime == cachedMtime) return cache
        val loaded = try {
            if (file.isFile) {
                JSON.decodeFromString<HooksConfig>(file.readText())
            } else {
                HooksConfig()
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "hooks.json 解析失败，按无钩子处理", e)
            HooksConfig()
        }
        cachedMtime = mtime
        cached = loaded
        return loaded
    }
}
