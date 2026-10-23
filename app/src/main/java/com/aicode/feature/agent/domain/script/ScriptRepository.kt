package com.aicode.feature.agent.domain.script

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 脚本清单条目：名称（含后缀）、运行解释器、描述与上次运行结果。 */
data class ScriptInfo(
    val name: String,
    val interpreter: String,
    val description: String?,
    val lastRunAt: Long?,
    val lastExitCode: Int?
)

/**
 * 用户脚本仓库：管理配置目录下的 `scripts/`——宿主为 `filesDir/aicode/scripts`，容器内经绑定即
 * `/root/.aicode/scripts/`，脚本因此能直接在容器内执行，并随备份/迁移走。
 *
 * 脚本是容器内可执行单元（`.sh` / `.py` / `.js` / `.mjs`），解释器由后缀决定，本仓库只负责
 * 读写与清单，不负责执行。启动时 [ContainerInstaller.extractScripts] 会补齐 assets 中的内置
 * 示例脚本（已存在则不覆盖），用户与 AI 固化的脚本与它们同放一处。
 */
@Singleton
class ScriptRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val executionModeHolder: ExecutionModeHolder
) {

    /** 脚本目录（不存在则创建）。 */
    fun dir(): File = File(containerInstaller.aicodeDir, SCRIPTS_DIR).apply { mkdirs() }

    /**
     * 本仓库不可用的原因；null 表示可用。
     *
     * 远程 SSH 模式下宿主这一侧读写的始终是 App 私有目录，而命令执行落在远端服务器，
     * 两边不是同一份文件（脚本目录尚未同步），会让「固化成功」之后运行必然失败、
     * 远端已有脚本也列不出来。故该模式下整条链路一律拒绝，由调用方展示原因。
     */
    fun unavailableReason(): String? = if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
        "远程 SSH 模式下暂不支持用户脚本：脚本目录尚未同步到服务器（当前仅支持本地容器）。"
    } else {
        null
    }

    /** 容器内绝对路径，交给命令执行链路时使用。名称不合法即抛，避免把模型原始输入直接拼进路径。 */
    fun containerPath(name: String): String {
        require(isValidName(name)) { "脚本名不合法：$name" }
        return "$CONTAINER_SCRIPTS_DIR/$name"
    }

    /**
     * 列出可运行脚本，按名排序；隐藏文件（如索引）、非脚本文件、名称不合规（如带空格）的文件忽略——
     * 后者能列出来但按名解析必失败（[findScript] 过 [isValidName]），呈现出来只会变成「可见但跑不了」。
     */
    fun list(): List<ScriptInfo> {
        val runs = readIndex().runs
        return dir().listFiles().orEmpty()
            .filter { it.isFile && isValidName(it.name) }
            .mapNotNull { file ->
                val interpreter = resolveInterpreter(file.name) ?: return@mapNotNull null
                val run = runs[file.name]
                ScriptInfo(
                    name = file.name,
                    interpreter = interpreter,
                    description = firstCommentDescription(file),
                    lastRunAt = run?.at,
                    lastExitCode = run?.exit
                )
            }
            .sortedBy { it.name }
    }

    /**
     * 按名解析脚本文件：同名精确匹配优先，其次把不带后缀的名字补全为唯一后缀匹配。
     * 名称先过 [isValidName] 的字符集校验，因此不会因 `../` 之类的输入越出脚本目录。
     */
    fun resolve(name: String): File? = findScript(dir(), name)

    /** 读取脚本正文；不存在或读取失败返回 null。 */
    fun read(name: String): String? {
        val file = resolve(name) ?: return null
        return runCatching { file.readText() }.getOrElse {
            FileLogger.w(TAG, "读取脚本失败: ${file.name}", it)
            null
        }
    }

    /**
     * 固化脚本：把 [content] 写入 `scripts/[name]`（覆盖同名文件）。
     *
     * 名称或正文大小不合法时抛 [IllegalArgumentException]，由调用方转成面向用户的提示。
     * 先写临时文件再改名，避免写入中断留下半截脚本被后续当成可运行脚本。
     */
    fun save(name: String, content: String): File {
        val trimmed = name.trim()
        require(isValidName(trimmed)) {
            "脚本名不合法：$name（只允许字母、数字与 . _ -，且后缀须为 " +
                "${INTERPRETERS.keys.joinToString(" / ") { ".$it" }} 之一）"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SCRIPT_BYTES) {
            "脚本正文过大：上限 ${MAX_SCRIPT_BYTES / 1024} KB（当前 ${bytes.size / 1024} KB）"
        }

        val dir = dir()
        val target = File(dir, trimmed)
        // 临时名带随机后缀：同批并行固化多个脚本不会互相踩同一份中间文件。
        val tmp = File(dir, ".$trimmed.${System.nanoTime().toString(36)}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        target.setExecutable(true, false)
        FileLogger.i(TAG, "固化脚本 ${target.name}（${content.length} 字符）")
        return target
    }

    /**
     * 删除脚本，并清掉索引里的「上次运行」记录。
     *
     * 找不到脚本时返回 false（名称非法与不存在都走这条，不抛异常）；删文件失败也返回 false，
     * 由调用方提示。
     */
    fun delete(name: String): Boolean {
        val file = resolve(name) ?: return false
        if (!file.delete()) return false
        runCatching { removeFromIndex(file.name) }
            .onFailure { FileLogger.w(TAG, "清理脚本索引失败: ${file.name}", it) }
        FileLogger.i(TAG, "删除脚本 ${file.name}")
        return true
    }

    /** 把某个脚本从索引里移除（没有记录时不动索引文件）。 */
    @Synchronized
    private fun removeFromIndex(name: String) {
        val index = readIndex()
        if (name !in index.runs) return
        File(dir(), INDEX_FILE).writeText(JSON.encodeToString(index.copy(runs = index.runs - name)))
    }

    /**
     * 记录一次运行结果，供清单展示「上次运行」。记录失败只告警，不影响运行本身的返回。
     * 整个「读索引 → 合并 → 写回」加锁：同批并行运行多个脚本时不会互相覆盖丢记录。
     */
    @Synchronized
    fun recordRun(name: String, exitCode: Int?) {
        runCatching {
            val index = readIndex()
            val updated = index.copy(
                runs = index.runs + (name to ScriptRunRecord(at = System.currentTimeMillis(), exit = exitCode))
            )
            File(dir(), INDEX_FILE).writeText(JSON.encodeToString(updated))
        }.onFailure { FileLogger.w(TAG, "记录脚本运行失败: $name", it) }
    }

    @Synchronized
    private fun readIndex(): ScriptIndex {
        val file = File(dir(), INDEX_FILE)
        if (!file.isFile) return ScriptIndex()
        return runCatching { JSON.decodeFromString<ScriptIndex>(file.readText()) }.getOrElse {
            FileLogger.w(TAG, "脚本索引解析失败，按空处理: ${it.message}")
            ScriptIndex()
        }
    }

    /** 正文开头若干行里的首条注释作为描述（跳过 shebang 与编码声明）。 */
    private fun firstCommentDescription(file: File): String? = runCatching {
        file.bufferedReader().use { reader ->
            repeat(COMMENT_SCAN_LINES) {
                val line = reader.readLine() ?: return@use null
                commentText(line)?.let { text -> return@use text }
            }
            null
        }
    }.getOrNull()

    companion object {
        const val SCRIPTS_DIR = "scripts"

        /** 容器内脚本目录（宿主配置目录经绑定后的位置）。 */
        const val CONTAINER_SCRIPTS_DIR = WorkspacePathMapper.AICODE_ROOT + "/" + SCRIPTS_DIR

        private const val TAG = "ScriptRepository"
        private const val INDEX_FILE = ".index.json"
        private const val MAX_SCRIPT_BYTES = 64 * 1024
        private const val COMMENT_SCAN_LINES = 20
        private const val MAX_DESCRIPTION_CHARS = 100

        /** `.sh` 的解释器：容器 provision 后即默认 shell（执行时问引擎要默认 shell，见 `ScriptRunner`）。 */
        const val SHELL_INTERPRETER = "bash"

        /**
         * 后缀 → 容器内解释器。只收录容器基础环境或对应场景能提供的运行时。
         * `.sh` 用 [SHELL_INTERPRETER]（bash）：脚本里常见的 `#!/bin/bash` 与 bashism（数组、`[[ ]]`）
         * 在 sh 下会失败（shebang 不参与执行，解释器由本表决定）。
         */
        private val INTERPRETERS = mapOf(
            "sh" to SHELL_INTERPRETER,
            "py" to "python3",
            "js" to "node",
            "mjs" to "node"
        )

        private val NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }

        private val CODING_DECLARATION = Regex("^[-*#]*\\s*coding[:=]")

        /** 后缀对应的解释器；不支持的后缀返回 null。 */
        fun resolveInterpreter(fileName: String): String? =
            INTERPRETERS[fileName.substringAfterLast('.', "").lowercase()]

        /** 名称是否可作为脚本文件名（字符集 + 受支持后缀）。 */
        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name) && resolveInterpreter(name) != null

        /**
         * 在 [dir] 中按名找脚本：同名精确匹配优先，否则取「去后缀同名」的唯一匹配（便于
         * `run_script name=cleanup` 命中 `cleanup.sh`）；多个后缀同名时不猜，返回 null。
         */
        fun findScript(dir: File, name: String): File? {
            val trimmed = name.trim()
            if (!NAME_REGEX.matches(trimmed)) return null
            val candidates = dir.listFiles().orEmpty()
                .filter { it.isFile && !it.name.startsWith(".") && resolveInterpreter(it.name) != null }
            candidates.firstOrNull { it.name == trimmed }?.let { return it }
            return candidates.filter { it.name.substringBeforeLast('.') == trimmed }.singleOrNull()
        }

        /** 从一行取注释文本（`#` 或 `//`）；非注释行、shebang 与编码声明返回 null。 */
        fun commentText(line: String): String? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#!")) return null
            val body = when {
                trimmed.startsWith("#") -> trimmed.trimStart('#').trim()
                trimmed.startsWith("//") -> trimmed.trimStart('/').trim()
                else -> return null
            }
            if (body.isEmpty() || CODING_DECLARATION.containsMatchIn(body)) return null
            return body.take(MAX_DESCRIPTION_CHARS)
        }
    }
}

/** 脚本运行索引（目录内 `.index.json`），只存「上次运行」这一事实。 */
@Serializable
internal data class ScriptIndex(val runs: Map<String, ScriptRunRecord> = emptyMap())

@Serializable
internal data class ScriptRunRecord(val at: Long, val exit: Int? = null)
