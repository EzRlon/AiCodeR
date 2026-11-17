package com.aicode.feature.plugin.data

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 单个插件的持久化状态。
 *
 * [ownedFiles] 是**卸载的唯一依据**：插件内容是按既有约定直写进 `~/.aicode/skills` 等目录的
 * （这样管理页零改动就能显示），所以必须逐条记账，否则卸载时要么删不干净、要么误删用户自己的同名资源。
 * 记录的是相对 `~/.aicode` 的路径。
 *
 * [consentAt] 记录用户接受该插件免责声明的时刻，值为 null 表示尚未同意——此时不允许启用。
 *
 * [ownedMcpServers] / [ownedHooks] / [ownedProvisionMarkers] 是同一份「归属清单」在非文件物品上的延伸：
 * MCP 条目和钩子写在宿主的共享配置文件里，marker 是插件自己创建的标记文件，三者都无法用 [ownedFiles]
 * 表达（不是插件直写的内容），必须单独记账，卸载才摘得干净、又不会误删用户自己的同名配置。
 */
@Serializable
data class PluginState(
    val enabled: Boolean = false,
    val consentAt: Long? = null,
    val installedAt: Long? = null,
    val version: String? = null,
    val ownedFiles: List<String> = emptyList(),
    val ownedMcpServers: List<String> = emptyList(),
    val ownedHooks: List<String> = emptyList(),
    val ownedProvisionMarkers: List<String> = emptyList()
)

@Serializable
private data class PluginStateFile(val plugins: Map<String, PluginState> = emptyMap())

/**
 * 插件状态的落盘（`<插件根>/state.json`）。
 *
 * 读写经 [FileAccessProvider]：本地模式落 App 私有目录，远程 SSH 模式落服务器上的
 * `~/.aicode/plugins/state.json`，与插件列表同源——否则远程会话看到的是本地一份空的旧状态。
 * 原子落盘交给 provider 实现（本地与远程都先写临时文件再替换），这里不再自己拼临时文件。
 *
 * 文件缺失或损坏一律按「空状态」处理（保守：插件视为未启用，用户重新启用即可，不会误删内容）。
 */
class PluginStateStore(
    private val fileAccess: FileAccessProvider,
    private val rootPath: String
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun load(): Map<String, PluginState> = synchronized(lock) { readState() }

    fun stateOf(id: String): PluginState? = load()[id]

    /** 读改写一次；返回更新后的状态。 */
    fun update(id: String, transform: (PluginState) -> PluginState): PluginState = synchronized(lock) {
        val all = readState().toMutableMap()
        // 归属清单是卸载的唯一依据，重复项会让卸载重复删同一个文件，在落盘这一步统一收口。
        val next = transform(all[id] ?: PluginState()).let {
            it.copy(
                ownedFiles = it.ownedFiles.distinct(),
                ownedMcpServers = it.ownedMcpServers.distinct(),
                ownedHooks = it.ownedHooks.distinct(),
                ownedProvisionMarkers = it.ownedProvisionMarkers.distinct()
            )
        }
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

    private fun stateFile() = rootPath.trimEnd('/') + "/" + FILE_NAME

    private fun readState(): Map<String, PluginState> {
        val file = stateFile()
        if (!fileAccess.isFile(file)) return emptyMap()
        return runCatching {
            json.decodeFromString<PluginStateFile>(fileAccess.readFile(file)).plugins
        }.getOrElse { e ->
            FileLogger.w(TAG, "插件状态文件损坏，按空状态处理: $file: ${e.message}")
            emptyMap()
        }
    }

    private fun writeState(plugins: Map<String, PluginState>) {
        runCatching {
            fileAccess.writeFile(stateFile(), json.encodeToString(PluginStateFile(plugins)))
        }.onFailure { FileLogger.e(TAG, "保存插件状态失败: ${stateFile()}", it) }
    }

    companion object {
        const val FILE_NAME = "state.json"
        private const val TAG = "PluginStateStore"
    }
}
