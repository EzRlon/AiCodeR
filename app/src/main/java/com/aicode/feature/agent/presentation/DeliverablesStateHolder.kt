package com.aicode.feature.agent.presentation

import com.aicode.core.util.ExternalFileActions
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.workspace.domain.FileAccessProvider
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
    val timestamp: Long,
    /** 所属消息 id；删除时需要回写该消息的附件列表。 */
    val messageId: String = ""
)

/**
 * 「交付物」页数据源（左栏交付物标签）：聚合当前工作区的会话附件，并在真机
 * `下载/AiCodeR/` 维护一份副本供外部应用打开。
 *
 * 从 AIAgentViewModel 拆出的又一独立域：只依赖 AgentMessageDao / FileAccessProvider /
 * 与「当前工作区路径」（由 VM 注入提供函数）。
 */
class DeliverablesStateHolder @Inject constructor(
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
                            DeliverableItem(attachment, row.timestamp, row.messageId)
                        )
                    }
                }
            }
        }.onFailure { FileLogger.w(TAG, "读取消息附件失败", it) }
        // 不再对全部交付物预导出到真机 下载/AiCodeR/：那会给每个历史交付物留一份常驻副本
        // （纯浪费空间），而真正需要真机文件的动作（MT 打开 / 文件管理器查看 / 分享 / 安装）
        // 都会在按下时按需导出（见 attachmentActionEntries → prepareLocal → exportToSharedFile）。
        // 文件已不在（被垃圾清理/手动删除）的条目自动同步移除：列表反映真实可用的交付物。
        return byPath.values
            .filter { fileAccessProvider.exists(it.attachment.containerPath) }
            .sortedByDescending { it.timestamp }
    }

    /**
     * 从交付列表移除一条（不删除磁盘文件）。
     *
     * 交付列表的事实源是会话消息的附件列，所以这里的语义是「把这条附件从它所属消息里摘掉」，
     * 该行其余附件保持不变；摘空则写 null，让 [AgentMessageDao.messagesWithAttachments] 的
     * 查询条件自然排除该行。宿主文件与真机副本都保留，供用户继续打开/分享。
     */
    suspend fun removeFromList(item: DeliverableItem) = withContext(Dispatchers.IO) {
        if (item.messageId.isBlank()) return@withContext
        val raw = agentMessageDao.attachmentsJsonOf(item.messageId) ?: return@withContext
        val remaining = AgentMessageEntity.decodeAttachments(raw).filterNot {
            it.containerPath == item.attachment.containerPath
        }
        agentMessageDao.updateAttachmentsJson(
            item.messageId,
            if (remaining.isEmpty()) null else AgentMessageEntity.encodeAttachments(remaining)
        )
        _deliverables.value = _deliverables.value.filterNot {
            it.attachment.containerPath == item.attachment.containerPath
        }
        FileLogger.i(TAG, "已从交付列表移除: ${item.attachment.fileName}")
    }

    /**
     * 彻底删除：交付列表条目 + 容器/工作区文件（同一份，容器内 `/root/workspace`
     * 就是工作区目录的 bind 挂载）+ 真机 `下载/AiCodeR` 副本。
     *
     * 三条路径都清掉才算「完全删除」，否则会留下用户看不见但仍占空间的残留。
     * 目录走递归删除（[FileAccessProvider.deleteRecursively]）。
     */
    suspend fun deleteCompletely(item: DeliverableItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            removeFromList(item)

            val containerPath = item.attachment.containerPath
            if (containerPath.isNotBlank() && fileAccessProvider.exists(containerPath)) {
                if (fileAccessProvider.isDirectory(containerPath)) {
                    fileAccessProvider.deleteRecursively(containerPath)
                } else {
                    fileAccessProvider.delete(containerPath)
                }
            }

            // 真机副本：按显示名匹配 `下载/AiCodeR` 下该文件的全部变体（唯一名会带 -1/-2 后缀）
            ExternalFileActions.findExportedAll(item.attachment.fileName).forEach { exported ->
                runCatching { exported.delete() }
                    .onFailure { FileLogger.w(TAG, "删除真机副本失败: ${exported.absolutePath}", it) }
            }
        }.onSuccess {
            FileLogger.i(TAG, "已彻底删除交付物: ${item.attachment.fileName}")
        }.onFailure {
            FileLogger.e(TAG, "彻底删除交付物失败: ${item.attachment.fileName}", it)
        }
    }
}
