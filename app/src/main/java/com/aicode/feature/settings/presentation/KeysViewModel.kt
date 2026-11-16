package com.aicode.feature.settings.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.KeyFile
import com.aicode.feature.settings.domain.model.KeySource
import com.aicode.feature.settings.domain.model.ReleaseReadiness
import com.aicode.feature.settings.domain.service.KeyFilesScanner
import com.aicode.feature.settings.domain.service.ReleaseReadinessChecker
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** 密钥管理页状态。 */
data class DevKeysUiState(
    val scanning: Boolean = false,
    val entries: List<KeyFile> = emptyList(),
    val importing: Boolean = false,
    val exporting: Boolean = false,
    /** 发布就绪检查结果；null = 尚未检查或当前无证书。 */
    val readiness: ReleaseReadiness? = null,
    /** 一次性提示（导入 / 导出结果）；界面 Toast 后调 [KeysViewModel.consumeMessage] 清掉。 */
    val message: String? = null,
)

/**
 * 密钥管理：扫描统一密钥库 / SSH 目录 / 工作区中的密钥文件，提供导入（SAF 多选到统一密钥库）
 * 与导出（zip 打包 / 单文件）能力。文件访问全走 [FileAccessProvider]（本地 / 远程一致）。
 */
@HiltViewModel
class KeysViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scanner: KeyFilesScanner,
    private val readinessChecker: ReleaseReadinessChecker,
    private val fileAccess: FileAccessProvider,
    private val workspaceRepository: com.aicode.feature.workspace.data.repository.WorkspaceRepository,
) : ViewModel() {

    /** 当前工作区即当前项目；页面只读展示，不再提供独立项目切换器。 */
    val currentProject = workspaceRepository.current

    private companion object {
        const val MANIFEST_ENTRY = "manifest.txt"
    }

    private val _state = MutableStateFlow(DevKeysUiState())
    val state: StateFlow<DevKeysUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        refresh()
        // 当前工作区就是当前项目：切换工作区后自动重扫，不再维护第二套项目选择逻辑。
        viewModelScope.launch {
            workspaceRepository.current
                .drop(1)
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    /** 重新扫描三处来源（进入页面 / 导入完成时调用），顺带刷新发布就绪检查。 */
    fun refresh() {
        scanJob?.cancel()
        // 先清掉上一项目的就绪结论：切换项目/重新检查期间不能继续显示旧项目的「已就绪」，
        // 否则用户可能在扫描完成前导出旧项目的资产。
        _state.update { it.copy(scanning = true, readiness = null) }
        scanJob = viewModelScope.launch {
            val entries = scanner.scan()
            val project = workspaceRepository.current.value
            // 文件探测、读取配置、keytool 全部是 IO（远程模式还是 SFTP 往返）：不能压在主线程，
            // 否则进入签名页/切项目/点重新检查会有明显卡顿甚至 ANR。
            val (projectConfig, readiness) = withContext(Dispatchers.IO) {
                // 多项目：Gradle 实际读取的是 app/keystore.properties；其它位置只用于迁移诊断。
                val gradleConfigPath =
                    "${com.aicode.feature.workspace.domain.WorkspacePathMapper.CONTAINER_ROOT}/app/keystore.properties"
                val config = listOf(
                    gradleConfigPath,
                    "${com.aicode.feature.workspace.domain.WorkspacePathMapper.CONTAINER_ROOT}/keystore.properties",
                    "${com.aicode.feature.workspace.domain.WorkspacePathMapper.CONTAINER_ROOT}/signing.properties"
                ).firstOrNull { fileAccess.exists(it) }
                // 配置里 storeFile 指向的证书优先；否则回退到统一密钥库/工作区的第一张发布证书。
                val declaredStorePath = runCatching {
                    val content = config?.let { fileAccess.readFile(it) } ?: return@runCatching null
                    val raw = readinessChecker.parseKeystoreProperties(content)["storeFile"] ?: return@runCatching null
                    readinessChecker.resolveStoreFile(raw, config)
                }.getOrNull()
                val releaseJks = entries.firstOrNull { it.containerPath == declaredStorePath }
                    // storeFile 指向扫描范围外的证书（如同一项目自定义目录）时，按路径补一条描述
                    ?: declaredStorePath?.let { scanner.describeFile(it) }
                    ?: entries
                        // 口令配置也归 RELEASE，但它不是证书本体：挑证书时排除
                        .filter { it.category == com.aicode.feature.settings.domain.model.KeyCategory.RELEASE && !it.isSigningConfig }
                        // 统一密钥库优先（导入的发布证书应归位），其次工作区
                        .sortedBy { if (it.source == KeySource.LIBRARY) 0 else 1 }
                        .firstOrNull()
                val checked = runCatching {
                    readinessChecker.check(
                        jksPath = releaseJks?.containerPath,
                        // 只把 Gradle 真正读取的位置标为「合规」；root/证书同目录配置会显示为非阻断警告
                        projectConfigPath = gradleConfigPath,
                        projectName = project?.name
                    )
                }.onFailure { FileLogger.w("KeysVM", "发布就绪检查失败: ${it.message}") }
                    .getOrNull()
                config to checked
            }
            // 「导出全部」与发布资产包同口径：就绪检查认定的配置必须进清单，
            // 包括 `<证书同名>.properties` 这类不在候选名里的配置。
            val configPath = readiness?.configPath
            val allEntries = if (configPath != null && entries.none { it.containerPath == configPath }) {
                entries + listOfNotNull(scanner.describeFile(configPath, asSigningConfig = true))
            } else {
                entries
            }
            _state.update { it.copy(scanning = false, entries = allEntries, readiness = readiness) }
        }
    }

    /** 消费一次性提示（界面 Toast 后调用）。 */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** SAF 多选导入：逐个复制进统一密钥库（重名自动改名），完成后刷新列表。 */
    fun importKeys(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.importing) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val (okCount, failedCount) = withContext(Dispatchers.IO) {
                var ok = 0
                var failed = 0
                val usedNames = HashSet<String>()
                uris.forEach { uri ->
                    val rawName = queryDisplayName(uri)
                    if (rawName.isBlank()) {
                        failed++
                    } else {
                        runCatching { importOne(uri, rawName, usedNames) }.onSuccess { ok++ }.onFailure { failed++ }
                    }
                }
                ok to failed
            }
            val message = when {
                failedCount == 0 -> context.getString(R.string.dev_keys_import_success, okCount)
                okCount > 0 -> context.getString(R.string.dev_keys_import_partial, okCount, failedCount)
                else -> context.getString(R.string.dev_keys_import_failed, failedCount)
            }
            _state.update { it.copy(importing = false, message = message) }
            refresh()
        }
    }

    /**
     * 导出全部密钥为 zip（含清单）到所选输出流。
     *
     * 注意：清单本身不含密码，但 zip 内可能带 `keystore.properties` 等**明文口令配置**，
     * 调用方必须向用户讲清这一点（与「打包发布资产」同口径）。
     */
    fun exportAllTo(output: OutputStream) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val entries = _state.value.entries
            runCatching {
                withContext(Dispatchers.IO) { writeZip(output, entries) }
            }.onSuccess {
                _state.update { it.copy(exporting = false, message = context.getString(R.string.dev_keys_export_success)) }
            }.onFailure {
                _state.update { it.copy(exporting = false, message = context.getString(R.string.dev_keys_export_failed)) }
            }
        }
    }

    /** 导出单个密钥文件到所选输出流。 */
    fun exportOneTo(key: KeyFile, output: OutputStream) {
        if (_state.value.exporting) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    output.use { it.write(fileAccess.readBytes(key.containerPath)) }
                }
            }.onSuccess {
                _state.update { it.copy(message = context.getString(R.string.dev_keys_export_success)) }
            }.onFailure {
                _state.update { it.copy(message = context.getString(R.string.dev_keys_export_failed)) }
            }
        }
    }

    /**
     * 打包「发布资产包」：证书 + 配套口令配置 + 恢复说明 + 逐文件 SHA-256 清单。
     *
     * 与 [exportAllTo] 的区别：那个是全部密钥的盘点清单；这个只取**打正式包必需的最小集**，
     * 且带逐文件校验和与恢复命令——发布当天靠它原地恢复，不再重演「证书在、口令配置没跟上」。
     * 就绪检查未全绿时拒绝打包（缺什么打包什么只会把坑打包进 zip）。
     */
    fun exportReleaseBundleTo(output: OutputStream) {
        if (_state.value.exporting) return
        val readiness = _state.value.readiness
        if (readiness == null || !readiness.ready) {
            _state.update {
                it.copy(message = context.getString(R.string.dev_keys_bundle_not_ready))
            }
            return
        }
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { writeReleaseBundle(output, readiness) }
            }.onSuccess {
                _state.update { it.copy(exporting = false, message = context.getString(R.string.dev_keys_export_success)) }
            }.onFailure {
                FileLogger.w("KeysVM", "发布资产包导出失败", it)
                _state.update { it.copy(exporting = false, message = context.getString(R.string.dev_keys_export_failed)) }
            }
        }
    }

    private fun writeReleaseBundle(output: OutputStream, readiness: ReleaseReadiness) {
        val jksPath = readiness.keystorePath ?: error("无证书路径")
        val configPath = readiness.configPath ?: error("无配置路径")
        val jksBytes = fileAccess.readBytes(jksPath)
        val configBytes = fileAccess.readBytes(configPath)
        val jksName = jksPath.substringAfterLast('/')
        val configName = configPath.substringAfterLast('/')
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            // 证书 + 口令配置：发布签名的最小完备集
            zip.putNextEntry(ZipEntry(jksName))
            zip.write(jksBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(configName))
            zip.write(configBytes)
            zip.closeEntry()
            // 逐文件校验和清单：恢复时逐个验证，坏一个立刻知道
            zip.putNextEntry(ZipEntry("CHECKSUMS.txt"))
            zip.write(
                buildString {
                    appendLine("# AiCodeR 发布资产校验和（SHA-256）")
                    appendLine("# 恢复后执行: sha256sum -c CHECKSUMS.txt")
                    appendLine("${sha256Hex(jksBytes)}  $jksName")
                    append("${sha256Hex(configBytes)}  $configName")
                }.toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            // 恢复说明：目标位置与自验命令，照抄即可
            zip.putNextEntry(ZipEntry("RESTORE.md"))
            zip.write(
                buildString {
                    appendLine("# 发布签名资产恢复说明")
                    appendLine()
                    appendLine("## 内容")
                    appendLine("- $jksName（签名证书）")
                    appendLine("- $configName（签名配置：storeFile / storePassword / keyAlias / keyPassword）")
                    appendLine("- CHECKSUMS.txt（逐文件 SHA-256，先校验再用）")
                    appendLine()
                    appendLine("## 恢复步骤")
                    appendLine("1. 校验: `sha256sum -c CHECKSUMS.txt`（必须全部 OK）")
                    appendLine("2. 证书放统一密钥库: `cp $jksName ~/.aicode/keys/$jksName`")
                    appendLine("3. 配置放工程: `cp $configName app/keystore.properties`")
                    appendLine("4. 自验: `app/build.gradle.kts` 的 signingConfig 读取该 properties 后，")
                    appendLine("   跑 `./gradlew assembleRelease` 确认签名生效")
                    appendLine()
                    appendLine("## 纪律")
                    appendLine("- 本包含明文口令：勿入库、勿转发；传输走加密信道。")
                    append("- 生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
                }.toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 把所选 uri 复制到统一密钥库；重名自动改名（`name-2.ext`、`name-3.ext`…）。 */
    private fun importOne(uri: Uri, displayName: String, usedNames: MutableSet<String>) {
        val target = uniqueLibraryPath(sanitizeFileName(displayName), usedNames)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null")
        input.use { fileAccess.writeStream(target, it, overwrite = false) }
    }

    /** 文件名清洗：剥路径（含 Windows 风格分隔符），过滤 `.`/`..` 等危险名。 */
    private fun sanitizeFileName(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/').trim()
        return if (base.isEmpty() || base == "." || base == "..") "key" else base
    }

    private fun uniqueLibraryPath(name: String, usedNames: MutableSet<String>): String {
        val dir = scanner.libraryDir()
        fun taken(candidate: String) = candidate in usedNames || fileAccess.exists(candidate)
        var candidate = "$dir/$name"
        if (!taken(candidate)) {
            usedNames += candidate
            return candidate
        }
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var index = 2
        while (taken(candidate)) {
            candidate = "$dir/$stem-$index$ext"
            index++
        }
        usedNames += candidate
        return candidate
    }

    /** 查询所选文件的显示名（含扩展名）；取不到时回退到 URI 末段。 */
    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    private fun writeZip(output: OutputStream, entries: List<KeyFile>) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(buildManifest(entries).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { key ->
                zip.putNextEntry(ZipEntry(zipEntryName(key)))
                zip.write(fileAccess.readBytes(key.containerPath))
                zip.closeEntry()
            }
        }
    }

    private fun zipEntryName(key: KeyFile): String = when (key.source) {
        KeySource.LIBRARY -> "keys/" + zipSafeSegment(key.name)
        KeySource.SSH -> "ssh/" + zipSafeSegment(key.name)
        KeySource.WORKSPACE -> "workspace/" + zipSafeSegment(
            key.containerPath.removePrefix(WorkspacePathMapper.CONTAINER_ROOT).trimStart('/')
        )
    }

    /** zip 条目名净化：反斜杠替换（避免跨平台解压工具的路径分隔歧义），空段兜底。 */
    private fun zipSafeSegment(segment: String): String {
        val cleaned = segment.replace('\\', '_')
        return cleaned.ifBlank { "key" }
    }

    /** 双语清单：只含文件元信息（名称/大小/时间/指纹/路径），不含任何密码。 */
    private fun buildManifest(entries: List<KeyFile>): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return buildString {
            appendLine("AiCodeR 密钥导出 / Keys Export")
            appendLine("时间 / Time: ${format.format(Date())}")
            appendLine("条目 / Entries: ${entries.size}")
            appendLine()
            KeySource.entries.forEach { source ->
                val group = entries.filter { it.source == source }
                if (group.isEmpty()) return@forEach
                appendLine("== ${sourceLabel(source)} ==")
                group.forEach { key ->
                    appendLine(
                        listOf(
                            key.name,
                            formatSize(key.size),
                            format.format(Date(key.lastModified)),
                            key.fingerprint ?: "-",
                            key.containerPath,
                        ).joinToString(" | ")
                    )
                }
                appendLine()
            }
            append("注：本清单不含任何密码；但 zip 内的签名配置（keystore.properties 等）可能含明文口令，请加密保管。")
            append(" / Note: this manifest contains no passwords, but signing configs inside the zip may contain plaintext credentials — keep the archive encrypted.")
        }
    }

    private fun sourceLabel(source: KeySource): String = when (source) {
        KeySource.LIBRARY -> "keys/（统一密钥库 ~/.aicode/keys）"
        KeySource.SSH -> "ssh/（~/.ssh）"
        KeySource.WORKSPACE -> "workspace/（工作区）"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
