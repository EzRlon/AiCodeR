package com.aicode.feature.plugin.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginState
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.File

/** 已安装的插件：清单 + 目录 + 状态，三者一起给界面用。 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    /** 容器视角的插件目录（如 `~/.aicode/plugins/<id>`）；本地与远程同一套路径。 */
    val dirPath: String,
    /**
     * 本地模式下对应的宿主目录，供「在文件管理器中打开」与本地占用统计使用。
     * 远程模式下插件在服务器上，没有本地路径，为 null（此时只展示 [dirPath]）。
     */
    val localDir: File?,
    val state: PluginState
) {
    val id: String get() = manifest.id
    val enabled: Boolean get() = state.enabled
}

/** 扫到了一个坏的插件目录。不静默跳过：用户得知道哪个目录为什么没生效。 */
data class PluginProblem(val dirName: String, val errors: List<String>)

data class PluginScanResult(
    val plugins: List<InstalledPlugin> = emptyList(),
    val problems: List<PluginProblem> = emptyList()
)

/**
 * 插件注册表：扫描插件根目录下的各插件目录，把清单与状态合成一份可用的列表。
 *
 * 目录一律以**容器路径**经 [FileAccessProvider] 访问：本地模式落 App 私有目录，
 * 远程 SSH 模式落服务器用户 home 下的 `~/.aicode/plugins`——与技能/子代理扫描同一套逻辑，
 * 否则远程模式下插件列表恒空。
 *
 * 只做「读」与状态变更；内容的释放/摘除是 `PluginResourceReleaser` 的职责，
 * 两者分开是为了让扫描与状态可单测，且卸载顺序（先摘能力、再删文件、最后清状态）可控。
 */
class PluginRegistry(
    private val fileAccess: FileAccessProvider,
    private val pluginsRoot: String,
    private val stateStore: PluginStateStore
) {

    /**
     * 扫描全部插件目录。
     *
     * 目录名必须与清单 `id` 一致。有了这条约束，同一个 id 就不可能被两个目录同时声明
     * （目录名唯一，而每个目录只能声明与自己同名的 id），不需要再另做重名去重。
     * 目录不存在不是错误（首次运行就是这样）。
     */
    fun scan(): PluginScanResult {
        // 根目录不存在（首次运行）、远程未连接都可能让列举失败：按空结果处理，不向上抛。
        val dirs = runCatching { fileAccess.listFiles(pluginsRoot) }
            .onFailure { FileLogger.w(TAG, "列举插件根目录失败: $pluginsRoot", it) }
            .getOrDefault(emptyList())
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .sortedBy { it.name }
        if (dirs.isEmpty()) return PluginScanResult()

        val plugins = mutableListOf<InstalledPlugin>()
        val problems = mutableListOf<PluginProblem>()

        dirs.forEach { dir ->
            val dirPath = join(pluginsRoot, dir.name)
            when (val result = PluginManifestParser.parseDirectory(fileAccess, dirPath)) {
                is PluginManifestResult.Invalid ->
                    problems += PluginProblem(dir.name, result.errors)

                is PluginManifestResult.Valid -> {
                    val manifest = result.manifest
                    if (manifest.id != dir.name) {
                        // 不一致时宁可不用：否则状态、释放清单、卸载目标会全部对不上号
                        problems += PluginProblem(
                            dir.name,
                            listOf("目录名与清单 id 不一致（目录 ${dir.name} / id ${manifest.id}）")
                        )
                    } else {
                        plugins += InstalledPlugin(
                            manifest = manifest,
                            dirPath = dirPath,
                            localDir = dir.localFile,
                            state = stateStore.stateOf(manifest.id) ?: PluginState()
                        )
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            FileLogger.w(TAG, "插件扫描发现 ${problems.size} 个问题目录: " +
                problems.joinToString { "${it.dirName}(${it.errors.size} 条)" })
        }
        return PluginScanResult(plugins = plugins, problems = problems)
    }

    /** 只取已启用的插件，按 id 排序（渲染顺序稳定）。 */
    fun enabledPlugins(): List<InstalledPlugin> = scan().plugins.filter { it.enabled }.sortedBy { it.id }

    fun pluginDir(id: String): String = join(pluginsRoot, id)

    /**
     * 启用/禁用。**未同意免责的插件不允许启用**——免责是插件的准入条件，不能绕过。
     * 返回是否真的改成了目标状态。
     */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val target = scan().plugins.firstOrNull { it.id == id } ?: return false
        if (enabled && target.state.consentAt == null) {
            FileLogger.w(TAG, "插件 $id 尚未接受免责声明，拒绝启用")
            return false
        }
        stateStore.update(id) { it.copy(enabled = enabled) }
        return true
    }

    /** 记录用户接受免责声明，并写入安装版本与时间。 */
    fun recordConsent(id: String, version: String, atMillis: Long) {
        stateStore.update(id) {
            it.copy(consentAt = atMillis, installedAt = it.installedAt ?: atMillis, version = version)
        }
    }

    fun recordOwnedFiles(id: String, paths: List<String>) {
        stateStore.update(id) { it.copy(ownedFiles = paths) }
    }

    /** 卸载收尾：只清状态。内容摘除由 `PluginResourceReleaser` 先做。 */
    fun forget(id: String) = stateStore.remove(id)

    private companion object {
        const val TAG = "PluginRegistry"

        fun join(root: String, name: String): String = root.trimEnd('/') + "/" + name
    }
}
