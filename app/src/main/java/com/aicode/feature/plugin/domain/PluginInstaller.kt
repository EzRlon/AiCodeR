package com.aicode.feature.plugin.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginState
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 安装失败的原因。UI 按它挑文案，不把底层异常直接甩给用户。 */
enum class PluginInstallError {
    /** 文件不是 zip / 压缩包损坏。 */
    BAD_PACKAGE,

    /** 包太大（条目数或解压体积超限）。 */
    TOO_LARGE,

    /** 包里找不到 `plugin.json`。 */
    MANIFEST_MISSING,

    /** 清单本身不合法（字段缺失、权限未知、路径越界等）。 */
    MANIFEST_INVALID,

    /** 同 id 的插件已经装过。 */
    ALREADY_INSTALLED,

    /** 写盘失败（权限、空间、远程断开）。 */
    WRITE_FAILED
}

sealed interface PluginInstallResult {
    data class Success(val manifest: PluginManifest) : PluginInstallResult
    data class Failed(val error: PluginInstallError, val detail: String = "") : PluginInstallResult
}

/**
 * 插件包安装与卸载。
 *
 * ### 包格式
 *
 * `.zip`（后缀随意，商店分发时叫 `.aicode-plugin` 也行），内容满足其一：
 * - `plugin.json` 在压缩包**根目录**；
 * - 所有文件都在**同一个顶层目录**下（`<id>/plugin.json`，即直接压缩插件目录的结果）。
 *
 * 顶层目录之外的条目（`__MACOSX/`、`.DS_Store`）一律忽略，而不是让整包失败——这是
 * macOS 压缩出来的包最常见的「多余内容」。
 *
 * ### 安全边界
 *
 * 解压前逐条校验路径：拒绝绝对路径、`..`、反斜杠与 NUL；限制条目数 4000、
 * 单条 64MB、总计 128MB。插件跑在容器里但**解压发生在宿主进程**，一个 `../` 就能
 * 写到 App 私有目录之外，所以这里必须挡死（zip slip）。
 *
 * ### 与「手工拷目录」的关系
 *
 * 手工把目录放进 `~/.aicode/plugins/` 依然有效（扫描不看安装来源）。安装器只是把
 * 「校验清单 + 落盘 + 记状态」这套动作做成一步，安装后**默认不启用**，仍需用户去详情页
 * 同意免责并打开开关——安装与授权是两件事。
 */
class PluginInstaller(
    private val fileAccess: FileAccessProvider,
    private val pluginsRoot: String,
    private val stateStore: PluginStateStore
) {

    /** 从 zip 字节安装。返回清单或失败原因；任何失败都不会在插件根目录留下半装目录。 */
    suspend fun installFromZip(bytes: ByteArray): PluginInstallResult = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext PluginInstallResult.Failed(PluginInstallError.BAD_PACKAGE)

        val entries = runCatching { readEntries(bytes) }.getOrElse { e ->
            FileLogger.w(TAG, "插件包解压失败", e)
            return@withContext PluginInstallResult.Failed(PluginInstallError.BAD_PACKAGE)
        } ?: return@withContext PluginInstallResult.Failed(PluginInstallError.TOO_LARGE)

        // 一个可用条目都没有 = 根本不是 zip（ZipInputStream 对垃圾字节只会静默返回空表）。
        // 必须在「找不到清单」之前判定，否则用户看到的是「缺少插件清单」，误导性很大。
        if (entries.isEmpty()) {
            return@withContext PluginInstallResult.Failed(PluginInstallError.BAD_PACKAGE)
        }

        // 定位 plugin.json 并确定包根前缀：根目录 or 唯一顶层目录。
        val manifestEntry = entries.firstOrNull { it.path == MANIFEST_NAME }
            ?: entries.firstOrNull {
                it.path.count { ch -> ch == '/' } == 1 && it.path.endsWith("/$MANIFEST_NAME")
            }
            ?: return@withContext PluginInstallResult.Failed(PluginInstallError.MANIFEST_MISSING)
        val rootPrefix = manifestEntry.path.removeSuffix(MANIFEST_NAME)

        val manifestText = runCatching { manifestEntry.bytes.toString(Charsets.UTF_8) }
            .getOrElse { return@withContext PluginInstallResult.Failed(PluginInstallError.BAD_PACKAGE) }
        val manifest = when (val parsed = PluginManifestParser.parse(manifestText)) {
            is PluginManifestResult.Invalid ->
                return@withContext PluginInstallResult.Failed(
                    PluginInstallError.MANIFEST_INVALID,
                    parsed.errors.joinToString("；")
                )

            is PluginManifestResult.Valid -> parsed.manifest
        }

        val id = manifest.id
        val dest = joinPath(pluginsRoot, id)
        if (fileAccess.exists(dest)) {
            return@withContext PluginInstallResult.Failed(PluginInstallError.ALREADY_INSTALLED, id)
        }

        val written = runCatching {
            fileAccess.mkdirs(dest)
            entries.forEach { entry ->
                if (!entry.path.startsWith(rootPrefix)) return@forEach
                val relative = entry.path.removePrefix(rootPrefix)
                if (relative.isEmpty()) return@forEach
                val target = joinPath(dest, relative)
                if (entry.path.endsWith("/")) {
                    fileAccess.mkdirs(target)
                } else {
                    fileAccess.writeBytes(target, entry.bytes)
                }
            }
        }.isSuccess

        if (!written) {
            // 半装目录比没装更糟：它会被扫描到一个不完整的插件。失败就整目录删掉。
            runCatching { fileAccess.deleteRecursively(dest) }
            return@withContext PluginInstallResult.Failed(PluginInstallError.WRITE_FAILED)
        }

        runCatching {
            stateStore.update(id) { existing ->
                existing.copy(
                    installedAt = System.currentTimeMillis(),
                    version = manifest.version
                )
            }
        }.onFailure { FileLogger.w(TAG, "写入插件安装状态失败: $id", it) }

        FileLogger.i(TAG, "插件已安装: $id v${manifest.version}")
        PluginInstallResult.Success(manifest)
    }

    /**
     * 卸载：先摘能力（派发器注入的 `detach` 负责 MCP/钩子/已释放文件），再删目录、清状态。
     *
     * 顺序不能反：目录删了就再也读不到清单，`detach` 无从知道该摘哪些 MCP 条目与文件。
     * 摘失败的条目留在原处不会导致「残留半装」——状态已删，下次扫描就当它从没装过。
     */
    suspend fun uninstall(id: String, detach: suspend (String) -> Unit) {
        withContext(Dispatchers.IO) {
            runCatching { detach(id) }
                .onFailure { FileLogger.w(TAG, "卸载前摘除插件能力失败: $id", it) }
            runCatching { fileAccess.deleteRecursively(joinPath(pluginsRoot, id)) }
                .onFailure { FileLogger.w(TAG, "删除插件目录失败: $id", it) }
            runCatching { stateStore.remove(id) }
                .onFailure { FileLogger.w(TAG, "清除插件状态失败: $id", it) }
        }
        FileLogger.i(TAG, "插件已卸载: $id")
    }

    // ── 解压与校验 ──

    private data class Entry(val path: String, val bytes: ByteArray)

    /**
     * 读出全部条目并做路径/体积校验。返回 null 表示超限。
     *
     * 只接受目录与普通文件：JVM 的 `ZipEntry` 不暴露符号链接位，因此不存在「解压出软链
     * 再借它写出包外」的路径；真正需要防的是路径本身。
     */
    private fun readEntries(bytes: ByteArray): List<Entry>? {
        val result = ArrayList<Entry>()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val rawName = entry.name.replace('\\', '/')
                if (!isSafePath(rawName)) {
                    FileLogger.w(TAG, "插件包含非法路径，拒绝安装: $rawName")
                    throw IllegalArgumentException("unsafe entry: $rawName")
                }
                // macOS 等系统附加的元数据：忽略而不是让整包失败。
                if (rawName.startsWith("__MACOSX/") || rawName.endsWith("/.DS_Store") ||
                    rawName == ".DS_Store"
                ) {
                    zip.closeEntry()
                    continue
                }
                if (++count > MAX_ENTRIES) return null
                if (entry.isDirectory) {
                    result += Entry(rawName.trimEnd('/') + "/", ByteArray(0))
                } else {
                    val buffer = ByteArrayOutputStream()
                    zip.copyTo(buffer)
                    val data = buffer.toByteArray()
                    if (data.size > MAX_ENTRY_BYTES) return null
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) return null
                    result += Entry(rawName, data)
                }
                zip.closeEntry()
            }
        }
        return result
    }

    /** 拒绝绝对路径、上跳、NUL 与空段异常（`a//b` 放行，无害）。 */
    private fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\u0000')) return false
        val segments = path.split('/')
        return segments.none { it == ".." }
    }

    private fun joinPath(base: String, name: String): String =
        base.trimEnd('/') + "/" + name.trimStart('/')

    private companion object {
        const val TAG = "PluginInstaller"
        const val MANIFEST_NAME = "plugin.json"

        /** 条目数上限：正常插件几十个文件，4000 足够宽松，又能挡住「小体积巨量条目」的 zip 炸弹。 */
        const val MAX_ENTRIES = 4_000

        /** 单个文件上限 64MB。 */
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        /** 解压总量上限 128MB。 */
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    }
}
