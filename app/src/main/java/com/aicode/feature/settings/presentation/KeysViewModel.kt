package com.aicode.feature.settings.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.settings.domain.model.KeyFile
import com.aicode.feature.settings.domain.model.KeySource
import com.aicode.feature.settings.domain.service.KeyFilesScanner
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
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
    private val fileAccess: FileAccessProvider,
) : ViewModel() {

    private companion object {
        const val MANIFEST_ENTRY = "manifest.txt"
    }

    private val _state = MutableStateFlow(DevKeysUiState())
    val state: StateFlow<DevKeysUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        refresh()
    }

    /** 重新扫描三处来源（进入页面 / 导入完成时调用）。 */
    fun refresh() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = true) }
        scanJob = viewModelScope.launch {
            val entries = scanner.scan()
            _state.update { it.copy(scanning = false, entries = entries) }
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

    /** 导出全部密钥为 zip（含清单，不含密码）到所选输出流。 */
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
            appendLine("AiCode 密钥导出 / Keys Export")
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
            append("注：本清单不含任何密码。 / No passwords included.")
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
