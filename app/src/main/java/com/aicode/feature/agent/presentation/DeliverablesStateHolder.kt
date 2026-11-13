package com.aicode.feature.agent.presentation

import android.content.Context
import com.aicode.core.util.ExternalFileActions
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.presentation.component.resolveLocalFile
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 一条交付物：附件元数据 + 产生时间（用于倒序排列与分组）。 */
data class DeliverableItem(
    val attachment: AgentAttachment,
    val timestamp: Long
)

/**
 * 「交付物」页数据源（左栏交付物标签）：聚合当前工作区的会话附件，并在真机
 * `下载/AiCodeR/` 维护一份副本供外部应用打开。
 *
 * 从 AIAgentViewModel 拆出的又一独立域：只依赖 AgentMessageDao / FileAccessProvider /
 * context 与「当前工作区路径」（由 VM 注入提供函数）。
 */
class DeliverablesStateHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val agentMessageDao: AgentMessageDao,
    private val fileAccessProvider: FileAccessProvider
) {
    private companion object {
        const val TAG = "Deliverables"
    }

    private lateinit var coroutineScope: CoroutineScope
    private lateinit var workspacePathProvider: () -> String

    private val _deliverables = MutableStateFlow<List<DeliverableItem>>(emptyList())
    val deliverables: StateFlow<List<DeliverableItem>> = _deliverables.asStateFlow()

    private val _deliverablesScanning = MutableStateFlow(false)
    val deliverablesScanning: StateFlow<Boolean> = _deliverablesScanning.asStateFlow()

    private var deliverablesJob: Job? = null

    /** 由 VM 注入作用域与「当前工作区路径」读取函数；重复调用无副作用。 */
    fun attach(scope: CoroutineScope, workspacePathProvider: () -> String) {
        if (::coroutineScope.isInitialized) return
        coroutineScope = scope
        this.workspacePathProvider = workspacePathProvider
    }

    /** 工作区切换时清空：交付物按工作区聚合，下次进入交付物页重新扫描。 */
    fun clear() {
        _deliverables.value = emptyList()
    }

    /** 刷新交付物：进入交付物页时调用（扫描只在用户打开该页时发生，重入忽略）。 */
    fun refresh() {
        if (deliverablesJob?.isActive == true) return
        deliverablesJob = coroutineScope.launch {
            _deliverablesScanning.value = true
            try {
                _deliverables.value = withContext(Dispatchers.IO) { loadDeliverables() }
            } finally {
                _deliverablesScanning.value = false
            }
        }
    }

    /**
     * 聚合当前工作区的会话附件（AI 交付出来的文件与安装包），去重后按时间倒序。
     *
     * 只认会话附件：交付事件就是「AI 把它发进了会话」。工作区里存在同后缀文件并不等于交付——
     * 构建中间产物、历史归档、往批留下的包都在工作区里，按后缀递归盲扫会把它们全当成交付物，
     * 混进与本次开发无关的安装包。工作区产物的去处是「设置 → 存储 → 工作区产物」。
     */
    private suspend fun loadDeliverables(): List<DeliverableItem> {
        val workspacePath = workspacePathProvider()
        val byPath = LinkedHashMap<String, DeliverableItem>()
        runCatching {
            agentMessageDao.messagesWithAttachments(workspacePath).forEach { row ->
                AgentMessageEntity.decodeAttachments(row.attachmentsJson).forEach { attachment ->
                    if (attachment.containerPath.isNotBlank()) {
                        byPath.putIfAbsent(
                            attachment.containerPath,
                            DeliverableItem(attachment, row.timestamp)
                        )
                    }
                }
            }
        }.onFailure { FileLogger.w(TAG, "读取消息附件失败", it) }
        // 把交付物复制一份到真机存储 下载/AiCodeR/（不是容器映射）：交付页展示与
        // 「MT 管理器打开」「文件管理器中查看」都用这份真机副本，长按菜单才能真正跳转。
        withContext(Dispatchers.IO) {
            byPath.values.forEach { item ->
                runCatching {
                    val local = resolveLocalFile(item.attachment, fileAccessProvider) ?: return@runCatching
                    val name = item.attachment.fileName.ifBlank { local.name }
                    val existing = ExternalFileActions.findExported(name)
                    if (existing == null || existing.length() != local.length()) {
                        ExternalFileActions.exportToSharedFile(context, local, name)
                    }
                }.onFailure { FileLogger.w(TAG, "导出交付物到真机失败: ${item.attachment.fileName}", it) }
            }
        }
        return byPath.values.sortedByDescending { it.timestamp }
    }
}
