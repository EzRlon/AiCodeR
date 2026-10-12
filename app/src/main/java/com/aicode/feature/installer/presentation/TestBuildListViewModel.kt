package com.aicode.feature.installer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 测试包扫描：在工作区里找 APK（含 .apks/.xapk），供一键唤起系统安装器。
 * 与 C3 沙箱列表将来可复用同一数据源。
 */
@HiltViewModel
class TestBuildListViewModel @Inject constructor(
    val fileAccess: FileAccessProvider
) : ViewModel() {

    data class ApkEntry(
        val containerPath: String,
        val fileName: String,
        val sizeBytes: Long,
        val lastModified: Long
    )

    private val _entries = MutableStateFlow<List<ApkEntry>>(emptyList())
    val entries: StateFlow<List<ApkEntry>> = _entries.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _entries.value = withContext(Dispatchers.IO) { scan() }
            _scanning.value = false
        }
    }

    private fun scan(): List<ApkEntry> {
        val root = WorkspacePathMapper.CONTAINER_ROOT
        return try {
            val hits = fileAccess.listFilesRecursive(root, SCAN_DEPTH)
                .filter { path ->
                    INSTALLABLE_EXTS.any { path.endsWith(".$it", ignoreCase = true) } &&
                        !path.contains("node_modules/") && !path.contains("/.git/")
                }
            hits.mapNotNull { rel ->
                val parent = rel.substringBeforeLast('/', "")
                val name = rel.substringAfterLast('/')
                val entry = fileAccess.listFiles(if (parent.isEmpty()) root else "$root/$parent")
                    .firstOrNull { it.name == name && !it.isDirectory }
                    ?: return@mapNotNull null
                ApkEntry(
                    containerPath = "$root/$rel",
                    fileName = name,
                    sizeBytes = entry.size,
                    lastModified = entry.lastModified
                )
            }.sortedByDescending { it.lastModified }.take(MAX_RESULTS)
        } catch (e: Exception) {
            FileLogger.w("TestBuildList", "扫描测试包失败", e)
            emptyList()
        }
    }

    private companion object {
        /** app/build/outputs/apk/<flavor>/<variant>/x.apk 约 6 层，留余量。 */
        const val SCAN_DEPTH = 8
        const val MAX_RESULTS = 50
        val INSTALLABLE_EXTS = listOf("apk", "apks", "xapk")
    }
}
