package com.aicode.feature.plugin.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.hooks.domain.HookDefinition
import com.aicode.feature.hooks.domain.HooksConfig
import com.aicode.feature.plugin.data.PluginState
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 没能装上的项。带 [detail]（路径 / 名称）而不是拼好的句子：
 * 文案在界面层按语言取，日志层用中文，两头都不必各自硬编码。
 */
data class ReleaseConflict(val kind: ConflictKind, val detail: String)

enum class ConflictKind {
    /** 目标位置已有同名内容，跳过（不覆盖）。 */
    TARGET_EXISTS,

    /** 清单声明的内容在插件目录里不存在。 */
    SOURCE_MISSING,

    /** 复制 / 写入失败。 */
    WRITE_FAILED,

    /** MCP 名称不符合宿主命名规范。 */
    MCP_NAME_INVALID,

    /** 已有同名 MCP 服务，跳过。 */
    MCP_EXISTS,

    /** MCP 配置写入失败。 */
    MCP_WRITE_FAILED,

    /** 钩子事件宿主不支持。 */
    HOOK_EVENT_UNSUPPORTED,

    /** 同一脚本已注册过钩子，跳过。 */
    HOOK_EXISTS,

    /** 容器未就绪，环境准备没跑。 */
    PROVISION_SKIPPED,

    /** 环境准备脚本执行失败。 */
    PROVISION_FAILED,

    /** 声明了场景但没给安装脚本。 */
    PROVISION_NO_SCRIPT
}

/** 装配结果：装上了什么、哪些被跳过。 */
data class ReleaseOutcome(
    val ownedFiles: List<String> = emptyList(),
    val ownedMcpServers: List<String> = emptyList(),
    val ownedHooks: List<String> = emptyList(),
    val ownedProvisionMarkers: List<String> = emptyList(),
    val conflicts: List<ReleaseConflict> = emptyList()
) {
    val ok: Boolean get() = conflicts.isEmpty()
}

/**
 * 插件能力的装配与摘除。
 *
 * 设计原则：**直写宿主既有目录，不另建平行体系**。技能进 `~/.aicode/skills`、MCP 进全局 mcp.json、
 * 钩子进 `~/.aicode/hooks.json`，装完之后宿主原有管理页零改动就能看到——这是「装完像自带功能」的前提。
 *
 * 代价是必须逐条记账：删的时候只能删自己装的那几项，绝不能按目录整片清（用户可能有同名资源）。
 * 记账分两类：文件进 `ownedFiles`，MCP 条目 / 钩子 / provision 标记各自进对应清单（见 [PluginState]）。
 *
 * 撞名一律**跳过并上报**，不覆盖：覆盖会静默毁掉用户已有的技能或 MCP 配置，且卸载时无从还原。
 */
class PluginResourceReleaser(
    private val fileAccess: FileAccessProvider,
    private val hostConfig: PluginHostConfig,
    private val commandEngine: CommandEngine,
    private val stateStore: PluginStateStore
) {

    /**
     * 装配插件声明的全部能力。可重复调用：先按旧账摘一遍再重新装，结果与装一次相同。
     */
    suspend fun attach(plugin: InstalledPlugin): ReleaseOutcome {
        detach(plugin.id)

        val ownedFiles = mutableListOf<String>()
        val ownedMcp = mutableListOf<String>()
        val ownedHooks = mutableListOf<String>()
        val ownedMarkers = mutableListOf<String>()
        val conflicts = mutableListOf<ReleaseConflict>()
        val capabilities = plugin.manifest.capabilities

        copyContentDirs(plugin, capabilities.skills, CONTENT_SKILLS, ownedFiles, conflicts)
        copyContentDirs(plugin, capabilities.agents, CONTENT_AGENTS, ownedFiles, conflicts)
        copyContentDirs(plugin, capabilities.teams, CONTENT_TEAMS, ownedFiles, conflicts)
        copyContentDirs(plugin, capabilities.scripts, CONTENT_SCRIPTS, ownedFiles, conflicts)
        copyContentDirs(plugin, capabilities.prompts, CONTENT_PROMPTS, ownedFiles, conflicts)

        capabilities.assets.forEach { asset -> copyAsset(plugin.dirPath, asset, ownedFiles, conflicts) }

        attachMcp(plugin, ownedMcp, conflicts)
        attachHooks(plugin, ownedHooks, conflicts)
        capabilities.provision?.let { prepareProvision(plugin, it, ownedMarkers, conflicts) }

        stateStore.update(plugin.id) {
            it.copy(
                ownedFiles = ownedFiles,
                ownedMcpServers = ownedMcp,
                ownedHooks = ownedHooks,
                // 已有的标记属于历史事实，不能被本轮（可能因 marker 已存在而没新增）覆盖掉
                ownedProvisionMarkers = it.ownedProvisionMarkers + ownedMarkers
            )
        }
        FileLogger.i(
            TAG,
            "插件 ${plugin.id} 装配完成：文件 ${ownedFiles.size}、MCP ${ownedMcp.size}、" +
                "钩子 ${ownedHooks.size}、跳过 ${conflicts.size}" +
                conflicts.joinToString(prefix = " [", postfix = "]") { "${it.kind}:${it.detail}" }
        )
        return ReleaseOutcome(ownedFiles, ownedMcp, ownedHooks, ownedMarkers, conflicts)
    }

    /**
     * 摘除插件装过的一切。顺序是「先摘共享配置、再删文件、最后清账」：
     * 共享配置（MCP / 钩子）残留会让工具列表指向已删文件，比文件残留更糟。
     *
     * **provision 标记不摘**：它记录的是「环境已经装好」这一事实，禁用插件并不会把容器里的
     * 依赖删掉。摘了反而会让下次启用重跑一遍安装脚本（慢，且可能不幂等）。标记只在插件目录
     * 被真正删除时才失去意义，届时随目录一起留在磁盘上也无妨。
     */
    suspend fun detach(id: String) {
        val state = stateStore.stateOf(id) ?: return
        if (state.ownedMcpServers.isNotEmpty()) detachMcp(state.ownedMcpServers)
        if (state.ownedHooks.isNotEmpty()) detachHooks(state.ownedHooks)
        state.ownedFiles.forEach { path ->
            runCatching { if (fileAccess.exists(path)) fileAccess.deleteRecursively(path) }
                .onFailure { FileLogger.w(TAG, "删除插件文件失败: $path", it) }
        }
        stateStore.update(id) {
            it.copy(
                ownedFiles = emptyList(),
                ownedMcpServers = emptyList(),
                ownedHooks = emptyList()
            )
        }
    }

    // ── 内容目录 ──

    /**
     * 把插件内某个目录下的每个条目复制到宿主对应目录：目录项整体复制，文件项按同名复制。
     * 目标已存在即视为冲突，跳过并点名。
     */
    private fun copyContentDirs(
        plugin: InstalledPlugin,
        sources: List<String>,
        targetDir: String,
        owned: MutableList<String>,
        conflicts: MutableList<ReleaseConflict>
    ) {
        sources.forEach { source ->
            val sourceDir = join(plugin.dirPath, source)
            if (!fileAccess.isDirectory(sourceDir)) {
                conflicts += ReleaseConflict(ConflictKind.SOURCE_MISSING, source)
                return@forEach
            }
            listFilesOrEmpty(sourceDir).sortedBy { it.name }.forEach { entry ->
                val target = "$targetDir/${entry.name}"
                if (fileAccess.exists(target)) {
                    conflicts += ReleaseConflict(ConflictKind.TARGET_EXISTS, target)
                    return@forEach
                }
                runCatching { copyRecursively(join(sourceDir, entry.name), target) }
                    .onSuccess { owned += target }
                    .onFailure {
                        FileLogger.w(TAG, "复制插件内容失败: ${entry.name} -> $target", it)
                        conflicts += ReleaseConflict(ConflictKind.WRITE_FAILED, target)
                    }
            }
        }
    }

    private fun copyAsset(
        pluginDir: String,
        asset: AssetMapping,
        owned: MutableList<String>,
        conflicts: MutableList<ReleaseConflict>
    ) {
        val source = join(pluginDir, asset.from)
        if (!fileAccess.exists(source)) {
            conflicts += ReleaseConflict(ConflictKind.SOURCE_MISSING, asset.from)
            return
        }
        val targets: List<Pair<String, String>> = if (fileAccess.isDirectory(source)) {
            listFilesOrEmpty(source).map { join(source, it.name) to "${asset.to}/${it.name}" }
        } else {
            listOf(source to asset.to)
        }
        targets.sortedBy { it.second }.forEach { (file, target) ->
            if (fileAccess.exists(target)) {
                conflicts += ReleaseConflict(ConflictKind.TARGET_EXISTS, target)
                return@forEach
            }
            runCatching { copyRecursively(file, target) }
                .onSuccess { owned += target }
                .onFailure {
                    FileLogger.w(TAG, "释放资源失败: ${file.substringAfterLast('/')} -> $target", it)
                    conflicts += ReleaseConflict(ConflictKind.WRITE_FAILED, target)
                }
        }
    }

    /** 文件读字节、目录递归；全程经 [FileAccessProvider]，本地与远程 SFTP 同一套行为。 */
    private fun copyRecursively(source: String, target: String) {
        if (fileAccess.isDirectory(source)) {
            fileAccess.mkdirs(target)
            listFilesOrEmpty(source).forEach { child -> copyRecursively(join(source, child.name), join(target, child.name)) }
        } else {
            fileAccess.writeBytes(target, fileAccess.readBytes(source), overwrite = false)
        }
    }

    private fun listFilesOrEmpty(dir: String) =
        runCatching { fileAccess.listFiles(dir) }.getOrDefault(emptyList())

    private fun join(base: String, name: String) = base.trimEnd('/') + "/" + name.trimStart('/')

    // ── MCP ──

    private suspend fun attachMcp(
        plugin: InstalledPlugin,
        owned: MutableList<String>,
        conflicts: MutableList<ReleaseConflict>
    ) {
        val specs = plugin.manifest.capabilities.mcp
        if (specs.isEmpty()) return
        val current = hostConfig.mcpServers().toMutableList()
        val names = current.map { it.name }.toMutableSet()

        specs.forEach { spec ->
            if (!McpServerConfig.isValidName(spec.name)) {
                conflicts += ReleaseConflict(ConflictKind.MCP_NAME_INVALID, spec.name)
                return@forEach
            }
            if (!names.add(spec.name)) {
                conflicts += ReleaseConflict(ConflictKind.MCP_EXISTS, spec.name)
                return@forEach
            }
            current += McpServerConfig(
                name = spec.name,
                command = spec.command,
                args = spec.args,
                url = spec.url,
                env = spec.env,
                enabled = spec.enabled,
                enabledTools = spec.enabledTools.toSet()
            )
            owned += spec.name
        }
        if (owned.isEmpty()) return

        runCatching { hostConfig.setMcpServers(current) }
            .onFailure {
                FileLogger.e(TAG, "写入 MCP 配置失败", it)
                owned.forEach { name ->
                    conflicts += ReleaseConflict(ConflictKind.MCP_WRITE_FAILED, name)
                }
                owned.clear()
            }
    }

    private suspend fun detachMcp(names: List<String>) {
        runCatching {
            hostConfig.setMcpServers(hostConfig.mcpServers().filterNot { it.name in names })
        }.onFailure { FileLogger.w(TAG, "移除 MCP 条目失败: $names", it) }
    }

    // ── 钩子 ──

    private suspend fun attachHooks(
        plugin: InstalledPlugin,
        owned: MutableList<String>,
        conflicts: MutableList<ReleaseConflict>
    ) {
        val specs = plugin.manifest.capabilities.hooks
        if (specs.isEmpty()) return
        val config = hostConfig.hooks().toMutableMap()
        val existingScripts = config.values.flatten().map { it.script }.toMutableSet()

        specs.forEach { spec ->
            if (spec.event !in SUPPORTED_HOOK_EVENTS) {
                conflicts += ReleaseConflict(ConflictKind.HOOK_EVENT_UNSUPPORTED, spec.event)
                return@forEach
            }
            val script = pluginScriptPath(plugin.id, spec.script)
            if (!existingScripts.add(script)) {
                conflicts += ReleaseConflict(ConflictKind.HOOK_EXISTS, script)
                return@forEach
            }
            val list = config.getOrPut(spec.event) { emptyList() }.toMutableList()
            list += HookDefinition(
                matcher = spec.matcher,
                script = script,
                timeoutMs = spec.timeoutMs.toLong()
            )
            config[spec.event] = list
            owned += hookKey(spec.event, script)
        }
        if (owned.isNotEmpty()) hostConfig.setHooks(config)
    }

    private fun detachHooks(keys: List<String>) {
        val config = hostConfig.hooks().toMutableMap()
        var changed = false
        keys.forEach { key ->
            val parts = key.split(KEY_SEPARATOR, limit = 2)
            if (parts.size != 2) return@forEach
            val (event, script) = parts
            val list = config[event] ?: return@forEach
            val remaining = list.filterNot { it.script == script }
            if (remaining.size != list.size) {
                changed = true
                if (remaining.isEmpty()) config.remove(event) else config[event] = remaining
            }
        }
        if (changed) hostConfig.setHooks(config)
    }

    /** 事件名与脚本路径合成一个键，逗号在路径里可能出现，用不可见分隔符。 */
    private fun hookKey(event: String, script: String) = "$event$KEY_SEPARATOR$script"

    private fun pluginScriptPath(id: String, relative: String) =
        "$PLUGIN_ROOT/$id/${relative.trimStart('/')}"

    // ── 环境准备 ──

    /**
     * 跑一次安装，成功后写 marker。marker 存在即视为已准备（与内置开发环境同一套幂等思路）。
     * 容器没就绪不算失败、只记一条跳过：装插件本身不该要求用户先起容器。
     *
     * 一律走插件目录内的 `script`：宿主不再为任何插件保留特权安装入口，
     * 插件要复用容器工具链就在自己的脚本里调用。
     */
    private suspend fun prepareProvision(
        plugin: InstalledPlugin,
        spec: ProvisionSpec,
        owned: MutableList<String>,
        conflicts: MutableList<ReleaseConflict>
    ) {
        val marker = spec.marker ?: "$PLUGIN_DATA_DIR/${plugin.id}/.provisioned"
        if (fileAccess.exists(marker)) return
        val command = "sh ${pluginScriptPath(plugin.id, spec.script)}"
        val result = runCatching {
            commandEngine.runCommandSyncIfReady(command, timeoutMs = PROVISION_TIMEOUT_MS)
        }.getOrNull()
        if (result == null) {
            conflicts += ReleaseConflict(ConflictKind.PROVISION_SKIPPED, spec.scenario)
            return
        }
        if (result.exitCode != 0) {
            conflicts += ReleaseConflict(ConflictKind.PROVISION_FAILED, spec.scenario)
            return
        }
        runCatching { fileAccess.writeFile(marker, "${plugin.id}\n") }
            .onSuccess { owned += marker }
            .onFailure { conflicts += ReleaseConflict(ConflictKind.WRITE_FAILED, marker) }
    }

    companion object {
        private const val TAG = "PluginResourceReleaser"

        /** 插件自身目录（容器视角）。 */
        const val PLUGIN_ROOT = "~/.aicode/plugins"

        /** 插件私有数据（marker 等），不参与内容发布。 */
        const val PLUGIN_DATA_DIR = "~/.aicode/.plugin-data"

        /** 宿主内置场景的统一入口（App 启动时提取到 `~/.aicode/`）。 */




        private const val KEY_SEPARATOR = "\u0000"
        private const val HOOKS_PATH = "~/.aicode/hooks.json"
        private const val CONTENT_SKILLS = "~/.aicode/skills"
        private const val CONTENT_AGENTS = "~/.aicode/agents"
        private const val CONTENT_TEAMS = "~/.aicode/teams"
        private const val CONTENT_SCRIPTS = "~/.aicode/scripts"
        private const val CONTENT_PROMPTS = "~/.aicode/prompts"

        private val SUPPORTED_HOOK_EVENTS = setOf(
            HooksConfig.EVENT_PRE_TOOL_USE,
            HooksConfig.EVENT_POST_TOOL_USE
        )

        private const val PROVISION_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
