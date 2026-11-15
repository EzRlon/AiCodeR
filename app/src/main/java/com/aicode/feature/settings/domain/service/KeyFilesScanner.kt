package com.aicode.feature.settings.domain.service

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.domain.model.KEY_FILE_SUFFIXES
import com.aicode.feature.settings.domain.model.KeyFile
import com.aicode.feature.settings.domain.model.KeySource
import com.aicode.feature.settings.domain.model.classifyKeyFile
import com.aicode.feature.settings.domain.model.isSigningConfigName
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥文件扫描：统一密钥库（`~/.aicode/keys/`）、SSH 目录（`~/.ssh/`）与当前工作区中的
 * 签名密钥文件（[KEY_FILE_SUFFIXES]）。走 [FileAccessProvider]，本地 / 远程模式路径语义一致。
 *
 * 两个目录以 `~` 形式交给 [FileAccessProvider] 按当前执行环境展开（先例
 * [com.aicode.feature.agent.domain.skill.GlobalDirectorySkillSource]）：本地映射宿主
 * filesDir（容器内即 `/root/.aicode/keys`、`/root/.ssh`），远程为服务器用户 home 下同名
 * 路径——不能写死 `/root`，否则远程非 root 用户扫不到任何密钥。
 *
 * 各目录级的读取失败（目录不存在、权限不足）按「该来源为空」处理，不阻断整体扫描。
 */
@Singleton
class KeyFilesScanner @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val executionModeHolder: ExecutionModeHolder,
) {
    /** 统一密钥库（`~/.aicode/keys`；映射宿主 filesDir/aicode/keys，独立于 rootfs）。导入密钥也写到这里。 */
    internal fun libraryDir(): String = "~/.aicode/keys"

    /** SSH 密钥目录（当前执行环境 home 下的 `.ssh`）。 */
    private fun sshDir(): String = "~/.ssh"

    private companion object {
        /**
         * 工作区扫描深度与排除目录：密钥一般在项目浅层；深扫依赖/构建/元数据目录
         * 又慢又无意义（远程模式下更会放大 SFTP 往返）。
         */
        private const val WORKSPACE_SCAN_DEPTH = 5
        private val EXCLUDED_SEGMENTS = setOf(
            ".git", ".aicode", ".gradle", ".kotlin", ".idea",
            "node_modules", "venv", ".venv", "build", "dist", "out", "target"
        )

        /** 指纹计算的大小上限：超过则不读内容（指纹只作识别用途，扫描优先要快）。 */
        private const val FINGERPRINT_MAX_BYTES = 4L * 1024 * 1024
    }

    /** 扫描三处来源，按「来源 → 名称」升序返回。 */
    suspend fun scan(): List<KeyFile> = withContext(Dispatchers.IO) {
        buildList {
            addAll(scanDirectory(libraryDir(), KeySource.LIBRARY))
            addAll(scanDirectory(sshDir(), KeySource.SSH))
            addAll(scanWorkspace())
        }
    }

    private fun scanDirectory(dir: String, source: KeySource): List<KeyFile> {
        val entries = runCatching {
            if (fileAccess.exists(dir) && fileAccess.isDirectory(dir)) fileAccess.listFiles(dir) else emptyList()
        }.getOrDefault(emptyList())
        return entries
            .filter { !it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .map { toKeyFile("$dir/${it.name}", it.name, source) }
    }

    /** 工作区密钥文件。远程模式跳过：SFTP 深扫代价高，发布密钥一般在本地侧管理。 */
    private fun scanWorkspace(): List<KeyFile> {
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) return emptyList()
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val found = runCatching { fileAccess.listFilesRecursive(root, WORKSPACE_SCAN_DEPTH) }
            .getOrDefault(emptyList())
        return found
            .filter { rel ->
                // 证书本体按扩展名识别；keystore.properties 这类口令配置按精确文件名识别，
                // 与发布资产包同口径（否则「导出全部」里仍缺了口令）。
                KEY_FILE_SUFFIXES.any { rel.lowercase().endsWith(it) } ||
                    isSigningConfigName(rel.substringAfterLast('/'))
            }
            .filter { rel -> rel.split('/').none { it in EXCLUDED_SEGMENTS } }
            .sortedBy { it.lowercase() }
            .map { rel -> toKeyFile("$root/$rel", rel.substringAfterLast('/'), KeySource.WORKSPACE) }
    }

    /**
     * 把单个文件包装成扫描条目。供「发布就绪检查命中的配置」补齐进导出清单：
     * 配置可能是 `<证书同名>.properties`（不在 [SIGNING_CONFIG_NAMES] 里），
     * 单靠文件名扫描兜不住，只能由就绪检查的结果反向补齐。
     */
    suspend fun describeFile(containerPath: String, asSigningConfig: Boolean = false): KeyFile? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!fileAccess.exists(containerPath) || fileAccess.isDirectory(containerPath)) return@runCatching null
                // 带上目录分隔符，避免 ~/.aicode/keys-backup 之类前缀相同但并非密钥库的路径
                val source = when {
                    containerPath.startsWith(libraryDir().trimEnd('/') + "/") -> KeySource.LIBRARY
                    containerPath.startsWith(sshDir().trimEnd('/') + "/") -> KeySource.SSH
                    else -> KeySource.WORKSPACE
                }
                val file = toKeyFile(containerPath, containerPath.substringAfterLast('/'), source)
                if (asSigningConfig) {
                    file.copy(category = com.aicode.feature.settings.domain.model.KeyCategory.RELEASE, isSigningConfig = true)
                } else {
                    file
                }
            }.getOrNull()
        }

    private fun toKeyFile(containerPath: String, name: String, source: KeySource): KeyFile {
        // 读取失败以 -1 表示「未知」（区别于真实的 0 字节/ 时间）；指纹只对已知且不超限的大小计算。
        val size = runCatching { fileAccess.fileSize(containerPath) }.getOrDefault(-1L)
        val lastModified = runCatching { fileAccess.lastModified(containerPath) }.getOrDefault(-1L)
        val fingerprint = if (size in 0..FINGERPRINT_MAX_BYTES) {
            runCatching { fileAccess.fingerprint(containerPath) }.getOrNull()
        } else {
            null
        }
        return KeyFile(
            name = name,
            containerPath = containerPath,
            source = source,
            category = classifyKeyFile(source, name),
            size = size,
            lastModified = lastModified,
            fingerprint = fingerprint,
            isSigningConfig = source != KeySource.SSH && isSigningConfigName(name),
        )
    }
}
