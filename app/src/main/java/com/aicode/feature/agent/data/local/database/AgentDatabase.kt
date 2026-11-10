package com.aicode.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.FailureEventDao
import com.aicode.feature.agent.data.local.dao.PermissionAuditDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.AgentMessageFtsEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.local.entity.FailureEventEntity
import com.aicode.feature.agent.data.local.entity.PermissionAuditEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.annotation.data.local.dao.AnnotationDao
import com.aicode.feature.annotation.data.local.entity.AnnotationEntity
import com.aicode.feature.backup.data.local.dao.BackupTargetDao
import com.aicode.feature.backup.data.local.entity.BackupTargetEntity
import com.aicode.feature.fixledger.data.local.dao.FixLedgerDao
import com.aicode.feature.fixledger.data.local.entity.FixLedgerEntity
import com.aicode.feature.memorygraph.data.local.dao.GraphDao
import com.aicode.feature.memorygraph.data.local.entity.GraphEdgeEntity
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeEntity
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.data.local.entity.RemoteMountEntity

@Database(
    entities = [AgentMessageEntity::class, AgentMessageFtsEntity::class, ChatSessionEntity::class, AIProviderEntity::class, RemoteConnectionEntity::class, RemoteMountEntity::class, TodoItemEntity::class, CheckpointEntity::class, CheckpointFileSnapshotEntity::class, LlmCallRecordEntity::class, BackupTargetEntity::class, AnnotationEntity::class, PermissionAuditEntity::class, FixLedgerEntity::class, GraphNodeEntity::class, GraphEdgeEntity::class, FailureEventEntity::class],
    version = AgentDatabase.SCHEMA_VERSION,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun aiProviderDao(): AIProviderDao
    abstract fun remoteConnectionDao(): RemoteConnectionDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun llmCallRecordDao(): LlmCallRecordDao

    abstract fun failureEventDao(): FailureEventDao
    abstract fun backupTargetDao(): BackupTargetDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun permissionAuditDao(): PermissionAuditDao
    abstract fun fixLedgerDao(): FixLedgerDao
    abstract fun graphDao(): GraphDao

    companion object {
        const val SCHEMA_VERSION = 75

        /** 数据库文件名（落在 `databases/` 下，另有 Room 默认 WAL 模式产生的 `-wal`/`-shm`）。 */
        const val DATABASE_NAME = "aicode_agent_db"
    }
}
