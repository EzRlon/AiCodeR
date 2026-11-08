package com.aicode.feature.plugin.data

import com.aicode.core.util.FileLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 单个插件的持久化状态。
 *
 * [ownedFiles] 是**卸载的唯一依据**：插件内容是按既有约定直写进 `~/.aicode/skills` 等目录的
 * （这样管理页零改动就能显示），所以必须逐条记账，否则卸载时要么删不干净、要么误删用户自己的同名资源。
 * 记录的是相对 `~/.aicode` 的路径。
 *
 * [consentAt] 记录用户接受该插件免责声明的时刻，值为 null 表示尚未同意——此时不允许启用。
 */
@Serializable
data class PluginState(
    val enabled: Boolean = false,
    val consentAt: Long? = null,
    val installedAt: Long? = null,
    val version: String? = null,
    val ownedFiles: List<String> = emptyList()
)

@Serializable
private data class PluginStateFile(val plugins: Map<String, PluginState> = emptyMap())

/**
 * 插件状态的落盘（`<插件根>/state.json`）。
 *
 * 与 `SyncIndexStore` 同一套写法：临时文件 + rename 原子落盘，避免写一半掉电损坏；
 * 文件缺失或损坏一律按「空状态」处理（保守：插件视为未启用，用户重新启用即可，不会误删内容）。
 */
class PluginStateStore(private val rootDir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun load(): Map<String, PluginState> = synchronized(lock) { readState() }

    fun stateOf(id: String): PluginState? = load()[id]

    /** 读改写一次；返回更新后的状态。 */
    fun update(id: String, transform: (PluginState) -> PluginState): PluginState = synchronized(lock) {
        val all = readState().toMutableMap()
        // 归属清单是卸载的唯一依据，重复项会让卸载重复删同一个文件，在落盘这一步统一收口。
        val next = transform(all[id] ?: PluginState())
            .let { it.copy(ownedFiles = it.ownedFiles.distinct()) }
        all[id] = next
        writeState(all)
        next
    }

    /** 清掉某个插件的状态（卸载时与文件清理一起做）。 */
    fun remove(id: String) {
        synchronized(lock) {
            val all = readState().toMutableMap()
            if (all.remove(id) != null) writeState(all)
        }
    }

    private fun stateFile() = File(rootDir, FILE_NAME)

    private fun readState(): Map<String, PluginState> {
        val file = stateFile()
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<PluginStateFile>(file.readText()).plugins
        }.getOrElse { e ->
            FileLogger.w(TAG, "插件状态文件损坏，按空状态处理: ${file.absolutePath}: ${e.message}")
            emptyMap()
        }
    }

    private fun writeState(plugins: Map<String, PluginState>) {
        runCatching {
            rootDir.mkdirs()
            val payload = json.encodeToString(PluginStateFile(plugins))
            val tmp = File(rootDir, "$FILE_NAME.tmp")
            tmp.writeText(payload)
            val target = stateFile()
            if (!tmp.renameTo(target)) {
                // rename 失败（罕见）回退直接写，避免丢状态
                target.writeText(payload)
                tmp.delete()
            }
        }.onFailure { FileLogger.e(TAG, "保存插件状态失败: ${rootDir.absolutePath}", it) }
    }

    companion object {
        const val FILE_NAME = "state.json"
        private const val TAG = "PluginStateStore"
    }
}
