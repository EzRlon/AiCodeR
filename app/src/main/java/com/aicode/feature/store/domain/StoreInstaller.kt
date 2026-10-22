package com.aicode.feature.store.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillImportError
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.store.data.StoreApiClient
import com.aicode.feature.store.data.StoreItem
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 技能仓库定位（从详情 repoUrl 解析）。 */
internal data class GitHubSource(
    val owner: String,
    val repo: String,
    val ref: String?,
    val path: String
)

/**
 * 技能安装计划：文件已下载、安全扫描已出结论，尚未写盘；确认后交 [StoreInstaller.commitSkillInstall]。
 */
data class SkillInstallPlan(
    val item: StoreItem,
    val files: Map<String, ByteArray>,
    val scan: ScanResult
)

/**
 * 在线商店安装器：把商店条目落到本地（**安装 ≠ 启用**）。
 *
 * - 技能：下载 repoUrl 指向的 GitHub 目录（contents API 递归）→ 内存 zip →
 *   复用 [SkillRepository.importZip]（校验/写盘/防越界同 App 内导入）→ 写入禁用名单（默认不生效）；
 *   写盘前经 [StoreInstallScanner] 内容扫描（[planSkillInstall] 扫描、[commitSkillInstall] 落盘）；
 * - MCP（仅 streamable-http / sse 远程型）：追加 [McpServerConfig]（enabled=false）到全局配置。
 *
 * 失败统一抛出带中文信息的异常，UI 直接展示。
 */
@Singleton
class StoreInstaller @Inject constructor(
    private val api: StoreApiClient,
    private val skillRepository: SkillRepository,
    private val skillConfig: SkillConfigRepository,
    private val mcpConfig: McpConfigRepository,
    private val scanner: StoreInstallScanner
) {
    private companion object {
        const val TAG = "StoreInstaller"
        const val MAX_FILES = 50
        const val MAX_TOTAL_BYTES = 8L shl 20          // 单技能总下载上限
        const val MAX_SINGLE_FILE_BYTES = 1 shl 20     // 单文件上限
        const val MAX_DEPTH = 3
        val GITHUB_URL_REGEX = Regex("""https?://github\.com/([^/\s]+)/([^/\s]+)/(?:tree|blob)/([^/\s]+)(?:/(.*))?""")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 安装技能第一阶段：下载全部文件并做安全扫描（不写盘）。扫描命中 WARN/BLOCK 时
     * 由调用方挂起等待用户确认，确认后用 [commitSkillInstall] 完成安装（无需重新下载）。
     */
    suspend fun planSkillInstall(item: StoreItem): SkillInstallPlan {
        val detail = api.skillDetail(item.id).getOrElse {
            throw IllegalStateException("获取技能详情失败：${it.message ?: "网络错误"}")
        }
        val src = parseGitHubSource(detail.repoUrl)
            ?: throw IllegalStateException("该技能未提供可下载的仓库地址（repoUrl），请打开详情页手动安装")
        val files = downloadDirectory(src)
        val scan = withContext(Dispatchers.Default) { scanner.scanFiles(files) }
        return SkillInstallPlan(item, files, scan)
    }

    /** 安装技能第二阶段：内存 zip → 导入（全局作用域，默认禁用）。返回实际写入的技能名。 */
    suspend fun commitSkillInstall(plan: SkillInstallPlan): String {
        val item = plan.item
        // 内存 zip 同样不能在主线程跑（中端机大技能可掉帧数百 ms），与扫描同调度
        val zip = withContext(Dispatchers.Default) { zipFiles(topDir = item.id, files = plan.files) }
        // 写盘与配置更新切到 IO：调用方（VM）在主线程调度，文件操作不能阻塞 UI
        val report = withContext(Dispatchers.IO) {
            skillRepository.importZip(zip.inputStream(), fallbackName = item.name, scope = SkillScope.GLOBAL)
        }
        val imported = report.imported.firstOrNull()
        if (imported == null) {
            val reason = report.fatal?.let { fatalMessage(it) }
                ?: report.failures.firstOrNull()?.let { failure -> "${failure.name}：${fatalMessage(failure.error)}" }
                ?: "导入失败"
            throw IllegalStateException(reason)
        }
        // 安装 ≠ 启用：新装技能默认禁用，由用户在技能页或商店页显式启用
        withContext(Dispatchers.IO) {
            skillConfig.setDisabled(imported, disabled = true, scope = SkillScope.GLOBAL)
        }
        FileLogger.i(TAG, "已安装技能（待启用）: $imported")
        return imported
    }

    /** 安装 MCP 前的内容扫描：可扫文本 = 条目名称 / 描述 / 远程地址。 */
    fun scanMcp(item: StoreItem): ScanResult = scanner.scanTexts(
        listOf(
            "名称" to item.name,
            "描述" to item.description,
            "远程地址" to item.remoteUrl.orEmpty()
        )
    )

    /** 安装 MCP 服务器（仅远程 http/sse 型，默认禁用）。返回写入的配置名。 */
    suspend fun installMcp(item: StoreItem): String {
        val remote = item.remoteUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("该服务器仅提供本地包（npm/docker），暂不支持一键安装，可打开来源页手动配置")
        val name = sanitizeMcpName(item)
        val servers = mcpConfig.getGlobalServers()
        if (servers.any { it.name == name }) {
            throw IllegalStateException("已存在同名 MCP 服务器「$name」")
        }
        val config = McpServerConfig(name = name, url = remote, enabled = false)
        mcpConfig.setGlobalServers(servers + config)
        FileLogger.i(TAG, "已安装 MCP（待启用）: $name -> $remote")
        return name
    }

    /** 从 GitHub repoUrl 解析 owner/repo/ref/path；非 GitHub 或形态不符返回 null。 */
    internal fun parseGitHubSource(url: String): GitHubSource? {
        val m = GITHUB_URL_REGEX.find(url.trim()) ?: return null
        val owner = m.groupValues[1]
        val repo = m.groupValues[2].removeSuffix(".git")
        val ref = m.groupValues[3].takeIf { it.isNotBlank() }
        val path = m.groupValues[4].trim('/')
        return GitHubSource(owner, repo, ref, path)
    }

    /** 递归下载 contents API 目录下的全部文件（相对技能根的路径 → 字节）。 */
    private suspend fun downloadDirectory(src: GitHubSource): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var count = 0
        var total = 0L

        suspend fun walk(path: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
            val refPart = src.ref?.let { "?ref=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            val apiUrl = "https://api.github.com/repos/${src.owner}/${src.repo}/contents/$encodedPath$refPart"
            val raw = api.fetchText(apiUrl).getOrElse {
                throw IllegalStateException("读取仓库目录失败：${it.message ?: "网络错误"}")
            }
            val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrElse {
                throw IllegalStateException("仓库返回数据异常")
            }
            for (el in arr) {
                val obj = el.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val fullPath = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (type) {
                    "file" -> {
                        if (++count > MAX_FILES) throw IllegalStateException("技能文件数超出上限（$MAX_FILES）")
                        val downloadUrl = obj["download_url"]?.jsonPrimitive?.contentOrNull ?: continue
                        val bytes = api.fetchBytes(downloadUrl, MAX_SINGLE_FILE_BYTES).getOrElse {
                            throw IllegalStateException("下载文件失败：${it.message ?: "网络错误"}")
                        }
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) throw IllegalStateException("技能体积超出上限")
                        val rel = fullPath.removePrefix(src.path).trimStart('/')
                        if (rel.isNotBlank()) files[rel] = bytes
                    }
                    "dir" -> walk(fullPath, depth + 1)
                }
            }
        }
        walk(src.path, 0)
        if (files.isEmpty()) throw IllegalStateException("技能目录为空或不存在")
        return files
    }

    /** 把文件集打包为内存 zip（顶级目录 = [topDir]，与导入链路约定一致）。 */
    private fun zipFiles(topDir: String, files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            files.forEach { (rel, bytes) ->
                zos.putNextEntry(ZipEntry("$topDir/$rel"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** MCP 配置名 sanitize：ASCII 字母数字下划线连字符（拼进工具名用）；不合法时回退源 id。 */
    internal fun sanitizeMcpName(item: StoreItem): String {
        fun clean(s: String) = s.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
        val fromName = clean(item.name)
        if (fromName.isNotBlank() && McpServerConfig.isValidName(fromName)) return fromName
        val fromId = clean(item.id)
        return fromId.ifBlank { "mcp-server" }
    }

    private fun fatalMessage(err: SkillImportError): String = when (err) {
        SkillImportError.INVALID_NAME -> "技能名称不合法"
        SkillImportError.NAME_CONFLICT -> "已存在同名技能"
        SkillImportError.EMPTY_CONTENT -> "技能内容为空"
        SkillImportError.NO_SKILL_FOUND -> "仓库中未找到技能文件（SKILL.md）"
        SkillImportError.INVALID_ARCHIVE -> "下载内容无法识别"
        SkillImportError.UNSUPPORTED_FILE -> "不支持的文件类型"
        SkillImportError.IO_FAILED -> "写入失败"
    }
}